package com.campusguard.moderation;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditLogger;
import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.media.MediaService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The transactional steps of moving a case through the queue.
 *
 * <p>Separate from the worker that loops over them so that each step commits on
 * its own. Claiming and analysing in one transaction would hold the row locks
 * taken by {@code SELECT ... FOR UPDATE SKIP LOCKED} for as long as the engine
 * takes to answer, which is fine for term matching and very much not fine for a
 * network call to a model.
 */
@Service
public class ModerationCaseProcessor {

    private static final Logger log = LoggerFactory.getLogger(ModerationCaseProcessor.class);

    private final ModerationCaseRepository caseRepository;
    private final ContentLocator contentLocator;
    private final EngineRegistry engines;
    private final AuditLogger auditLogger;
    private final MediaService mediaService;
    private final Counter degradationCounter;
    private final Timer analysisTimer;

    public ModerationCaseProcessor(
            ModerationCaseRepository caseRepository,
            ContentLocator contentLocator,
            EngineRegistry engines,
            AuditLogger auditLogger,
            MediaService mediaService,
            MeterRegistry meterRegistry) {
        this.caseRepository = caseRepository;
        this.contentLocator = contentLocator;
        this.engines = engines;
        this.auditLogger = auditLogger;
        this.mediaService = mediaService;
        this.degradationCounter = Counter.builder("campusguard.moderation.engine.degradations")
                .description("Primary moderation engine failures handled by fallback")
                .register(meterRegistry);
        this.analysisTimer = Timer.builder("campusguard.moderation.analysis")
                .description("End-to-end automated analysis latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * Take up to {@code batchSize} queued cases and mark them as being worked on.
     *
     * <p>The claim commits before any analysis starts. From that point the case is
     * no longer {@code QUEUED}, so no other worker will pick it up even though the
     * database locks are long gone.
     */
    @Transactional
    public List<UUID> claimBatch(int batchSize) {
        List<ModerationCase> claimed = caseRepository.claimQueued(batchSize);

        for (ModerationCase moderationCase : claimed) {
            moderationCase.markAnalysing();
            auditLogger.record(
                    AuditActorType.SYSTEM,
                    null,
                    AuditLogger.CASE_CLAIMED,
                    moderationCase.getTargetType(),
                    moderationCase.getTargetId(),
                    Map.of("caseId", moderationCase.getId().toString()));
        }

        return claimed.stream().map(ModerationCase::getId).toList();
    }

    /**
     * Judge one case and hand it to a human.
     *
     * <p>Every path out of here ends in {@code AWAITING_REVIEW}, including the
     * ones where the engine failed or the content has since gone. A case that
     * cannot be judged is a case a person should look at, not one that quietly
     * stops moving.
     */
    @Transactional
    public void analyse(UUID caseId) {
        ModerationCase moderationCase = caseRepository.findById(caseId).orElse(null);
        if (moderationCase == null || moderationCase.getStatus() != CaseStatus.ANALYSING) {
            return;
        }

        Optional<ContentLocator.ModeratedContent> content =
                contentLocator.find(moderationCase.getTargetType(), moderationCase.getTargetId());

        if (content.isEmpty()) {
            // The author removed it between the report and now. Still a decision
            // for a person: the report may justify action against the account
            // even though the content is gone.
            moderationCase.recordVerdict(
                    "none",
                    ModerationVerdict.escalate("The reported content is no longer available."));
            recordOutcome(moderationCase, Map.of("reason", "content-unavailable"));
            return;
        }

        ModerationEngine primary = engines.primary();
        ModerationRequest request = toRequest(content.get()).forCase(caseId);

        long startedAt = System.nanoTime();
        ModerationVerdict verdict;
        String engineName;
        boolean degraded = false;

        try {
            verdict = primary.evaluate(request);
            engineName = primary.name();

        } catch (RuntimeException ex) {
            log.warn("Engine {} failed on case {}.", primary.name(), caseId, ex);
            auditLogger.record(
                    AuditActorType.ENGINE,
                    null,
                    AuditLogger.ANALYSIS_FAILED,
                    moderationCase.getTargetType(),
                    moderationCase.getTargetId(),
                    Map.of("caseId", caseId.toString(), "engine", primary.name(),
                            "error", String.valueOf(ex.getMessage())));

            Degradation degradation = degrade(moderationCase, request, primary);
            verdict = degradation.verdict();
            engineName = degradation.engineName();
            degraded = true;
            degradationCounter.increment();
        }

        long millis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        analysisTimer.record(Duration.ofMillis(millis));

        moderationCase.recordVerdict(engineName, verdict);

        Map<String, Object> payload = new HashMap<>();
        payload.put("latencyMs", millis);
        payload.put("degraded", degraded);
        recordOutcome(moderationCase, payload);
    }

    /**
     * Return cases whose worker never came back.
     *
     * <p>This is what makes the queue durable rather than merely asynchronous: a
     * process killed mid-analysis leaves rows in {@code ANALYSING} that nothing
     * would otherwise ever look at again.
     */
    @Transactional
    public int requeueStalled(Duration stalledAfter) {
        List<ModerationCase> stalled = caseRepository.findStalled(Instant.now().minus(stalledAfter));
        stalled.forEach(ModerationCase::requeue);
        if (!stalled.isEmpty()) {
            log.info("Returned {} stalled case(s) to the queue.", stalled.size());
        }
        return stalled.size();
    }

    /**
     * What happens when the primary engine fails.
     *
     * <p>Falling back to the rule engine rather than escalating everything is the
     * difference between a degraded service and a stalled one. If a model is
     * unavailable for an hour, escalating every case in that hour hands a human
     * queue the entire traffic of the forum; the rule engine still filters the
     * obvious cases, and the reviewer sees which verdicts came from where.
     *
     * <p>The engine name recorded is the one that actually answered. Attributing a
     * rule-engine verdict to the model would corrupt exactly the comparison the
     * evaluation exists to make.
     */
    private Degradation degrade(
            ModerationCase moderationCase, ModerationRequest request, ModerationEngine primary) {

        ModerationEngine fallback = engines.fallback();

        if (fallback == primary) {
            // Nothing left to fall back to: the rule engine itself failed.
            return new Degradation(
                    "none",
                    ModerationVerdict.escalate(
                            "Automated analysis is unavailable. This case needs a human."));
        }

        try {
            ModerationVerdict verdict = fallback.evaluate(request);

            auditLogger.record(
                    AuditActorType.ENGINE,
                    null,
                    AuditLogger.ENGINE_DEGRADED,
                    moderationCase.getTargetType(),
                    moderationCase.getTargetId(),
                    Map.of(
                            "caseId", moderationCase.getId().toString(),
                            "from", primary.name(),
                            "to", fallback.name()));

            return new Degradation(fallback.name(), verdict);

        } catch (RuntimeException ex) {
            log.error("Fallback engine {} also failed on case {}.", fallback.name(), moderationCase.getId(), ex);
            return new Degradation(
                    "none",
                    ModerationVerdict.escalate(
                            "Automated analysis is unavailable. This case needs a human."));
        }
    }

    private record Degradation(String engineName, ModerationVerdict verdict) {
    }

    private void recordOutcome(ModerationCase moderationCase, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new HashMap<>(extraPayload);
        payload.put("caseId", moderationCase.getId().toString());
        payload.put("engine", moderationCase.getEngine());
        payload.put("decision", String.valueOf(moderationCase.getDecision()));
        payload.put("confidence", String.valueOf(moderationCase.getConfidence()));
        payload.put("ruleCodes", moderationCase.getRuleCodes());

        auditLogger.record(
                AuditActorType.ENGINE,
                null,
                AuditLogger.VERDICT_RECORDED,
                moderationCase.getTargetType(),
                moderationCase.getTargetId(),
                payload);
    }

    private ModerationRequest toRequest(ContentLocator.ModeratedContent content) {
        ModerationRequest request = ModerationRequest.of(
                content.targetType(), content.targetId(), content.title(), content.body(), content.authorId());
        if (content.mediaId() == null) return request;
        MediaService.StoredMedia stored = mediaService.read(content.mediaId());
        return request.withMedia(new ModerationRequest.MediaInput(
                stored.metadata().getContentType(), stored.bytes(), stored.metadata().getSha256()));
    }
}
