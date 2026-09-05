package com.campusguard.moderation.investigation;

import com.campusguard.common.ConflictException;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.moderation.engine.ai.ModelCallException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loop: look something up, decide whether that was enough, write a brief.
 *
 * <p>A bounded state machine rather than an autonomous agent. What the model
 * chooses is which of four read-only lookups happens next and when to stop
 * early; what it cannot choose is how many times, what else it might reach, or
 * whether anything is written down. Those are the parts that would make this
 * expensive or dangerous, and none of them is delegated.
 *
 * <p>Every lookup runs in a read-only transaction, established by
 * {@link ToolRegistry} rather than here. That is what makes "the assistant
 * cannot change anything" a fact about the database rather than a property of
 * the tools that somebody has to keep true. It is deliberately not one
 * transaction around the whole investigation: that would hold a connection open
 * across several calls to a model, and would also refuse the recording of those
 * calls, which is a write this class is supposed to make.
 *
 * <p>Every exit returns a brief. A reviewer with a queue behind them should not
 * meet an error page because a provider was slow, so a timeout becomes a
 * sentence saying the investigation did not finish, and the reviewer carries on
 * with what they had before — which is everything they have today.
 */
public class CaseInvestigator {

    private static final Logger log = LoggerFactory.getLogger(CaseInvestigator.class);

    private final ToolCallingPort port;
    private final ToolRegistry tools;
    private final InvestigationPrompt prompt;
    private final BriefParser parser;
    private final AiInvocationRecorder recorder;
    private final AdminModerationService cases;
    private final ModerationCaseRepository caseRepository;
    private final InvestigatorProperties properties;

    public CaseInvestigator(
            ToolCallingPort port,
            ToolRegistry tools,
            InvestigationPrompt prompt,
            BriefParser parser,
            AiInvocationRecorder recorder,
            AdminModerationService cases,
            ModerationCaseRepository caseRepository,
            InvestigatorProperties properties) {
        this.port = port;
        this.tools = tools;
        this.prompt = prompt;
        this.parser = parser;
        this.recorder = recorder;
        this.cases = cases;
        this.caseRepository = caseRepository;
        this.properties = properties;
    }

    public InvestigationBrief investigate(UUID caseId) {
        requireAwaitingReview(caseId);

        // Distinguishes this run from any earlier one on the same case. Without
        // it the first step of a second investigation is byte-for-byte the first
        // step of the first — same case, same prompt version, same call number —
        // and the invocation table's uniqueness constraint rejects the insert.
        //
        // That constraint is right for an engine, where asking the same question
        // twice about one case is a double charge for an answer already held. It
        // is wrong here: a reviewer clicking Investigate again, after new reports
        // or a colleague's note, is doing something legitimate, and the second
        // run is a distinct event that should be recorded as one.
        UUID investigationId = UUID.randomUUID();

        List<ToolCallingPort.Message> history =
                new ArrayList<>(List.of(new ToolCallingPort.Message.Prompt(prompt.opening(cases.get(caseId)))));

        // Everything the tools have actually shown. The brief may cite from here
        // and nowhere else.
        Set<UUID> disclosed = new LinkedHashSet<>();

        int callNumber = 0;
        int corrections = 0;

        for (int step = 1; step <= properties.maxSteps(); step++) {
            ToolCallingPort.Response response;
            try {
                response = call(caseId, investigationId, history, ++callNumber);
            } catch (ModelCallException ex) {
                log.warn("Investigation of case {} stopped at step {}: {}", caseId, step, ex.getMessage());
                return partial(disclosed, ex);
            }

            if (response.turn() instanceof ToolCallingPort.Turn.Finished finished) {
                try {
                    return parser.parse(finished.text(), disclosed);

                } catch (InvalidBriefException ex) {
                    if (corrections++ >= properties.maxCorrections()) {
                        log.warn("Investigation of case {} could not produce a usable brief: {}",
                                caseId, ex.getMessage());
                        return new InvestigationBrief.Inconclusive(
                                "The assistant could not produce a brief that held up to checking. "
                                        + "Review this case without it.");
                    }

                    // The complaint goes back verbatim. A model told which case it
                    // invented corrects that specific claim; one told "invalid" rewrites
                    // the whole brief and often invents something else.
                    history.add(new ToolCallingPort.Message.Prompt(
                            ex.getMessage() + "\nAnswer again as a single JSON object that fixes this."));
                    step--;
                    continue;
                }
            }

            List<ToolCall> calls = ((ToolCallingPort.Turn.CallTools) response.turn()).calls();

            if (calls.isEmpty()) {
                // Neither a brief nor a lookup. Treated as a malformed turn rather
                // than as a step, so it cannot silently drain the budget.
                if (corrections++ >= properties.maxCorrections()) {
                    return new InvestigationBrief.Inconclusive(
                            "The assistant did not answer. Review this case without it.");
                }
                history.add(new ToolCallingPort.Message.Prompt(
                        "You returned neither a tool call nor a brief. Call a tool, or write your brief."));
                step--;
                continue;
            }

            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : calls) {
                ToolResult result = tools.execute(caseId, call);
                disclosed.addAll(result.disclosedCaseIds());
                results.add(result);
            }

            history.add(new ToolCallingPort.Message.ToolRequest(calls));
            history.add(new ToolCallingPort.Message.ToolOutcome(results));
        }

        log.info("Investigation of case {} used its whole budget of {} steps without concluding.",
                caseId, properties.maxSteps());

        return new InvestigationBrief.Inconclusive(
                "The assistant was still gathering evidence after %d lookups and did not reach a conclusion. "
                        .formatted(properties.maxSteps())
                        + "Review this case without it.");
    }

    /**
     * One turn, recorded whether or not it worked.
     *
     * <p>Written to the same table as every moderation call, under its own engine
     * name. Sharing the table keeps one answer to "what has this project spent";
     * separating the name keeps the assistant's several calls per case out of the
     * per-verdict figures that the evaluation set publishes.
     *
     * <p>The hash covers the history and the run it belongs to. For an engine the
     * column means "these two rows were asked the identical question", and asking
     * twice is a fault worth a constraint violation. For an investigation the
     * unit is one step of one run, because re-investigating a case is something a
     * reviewer may legitimately do and the second run is a separate event.
     *
     * <p>Within a run the history only ever grows, so a repeated hash still means
     * the model is going in circles — visible in the table afterwards, though not
     * enforced, since the call number differs. The step budget is what actually
     * stops a loop.
     */
    private ToolCallingPort.Response call(
            UUID caseId, UUID investigationId, List<ToolCallingPort.Message> history, int callNumber) {

        String engineName = "investigator/" + prompt.version();
        String historyHash = sha256(investigationId + history.toString());
        long startedAt = System.nanoTime();

        try {
            ToolCallingPort.Response response = port.next(prompt.system(), List.copyOf(history), tools.specs());

            recorder.record(
                    caseId, engineName, port.modelName(), prompt.version(), historyHash, callNumber,
                    InvocationStatus.SUCCESS, response.promptTokens(), response.completionTokens(),
                    elapsedMillis(startedAt), describe(response.turn()), null);

            return response;

        } catch (ModelCallException ex) {
            recorder.record(
                    caseId, engineName, port.modelName(), prompt.version(), historyHash, callNumber,
                    ex.status(), null, null, elapsedMillis(startedAt), null, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Half an investigation, labelled as half.
     *
     * <p>What was found before the provider stopped answering is still worth
     * reading — an author's history does not become wrong because the next call
     * timed out — as long as nobody can mistake it for a finished argument.
     */
    private InvestigationBrief partial(Set<UUID> disclosed, ModelCallException failure) {
        String reason = switch (failure.status()) {
            case TIMEOUT -> "the model did not answer in time";
            case CIRCUIT_OPEN -> "model calls are failing and this one was not sent";
            case RATE_LIMITED -> "the provider is rate limiting us";
            default -> "the model call failed";
        };

        return new InvestigationBrief.Partial(
                // Deliberately "case records" rather than "earlier cases": the set
                // includes this case when caseDetail was among the lookups, and a
                // count that called it a prior offence would be its own small lie.
                "The investigation stopped early because %s. %d case record%s had been read; nothing was "
                        .formatted(reason, disclosed.size(), disclosed.size() == 1 ? "" : "s")
                        + "concluded from them.",
                List.copyOf(disclosed),
                failure.getMessage());
    }

    private void requireAwaitingReview(UUID caseId) {
        ModerationCase moderationCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new com.campusguard.common.NotFoundException(
                        "No moderation case with id " + caseId));

        // An investigation of a case nobody is about to decide has nothing to
        // inform. Refused rather than run, because the cost is real and the value
        // is not.
        if (moderationCase.getStatus() != CaseStatus.AWAITING_REVIEW) {
            throw new ConflictException(
                    "A case can only be investigated while it is awaiting review; this one is "
                            + moderationCase.getStatus() + ".");
        }
    }

    /**
     * The raw response as stored. A turn spent calling tools is kept as the names
     * it asked for rather than as an empty answer, so a transcript read later
     * shows what the model was doing on every step and not only on the last one.
     */
    private String describe(ToolCallingPort.Turn turn) {
        return switch (turn) {
            case ToolCallingPort.Turn.Finished finished -> finished.text();
            case ToolCallingPort.Turn.CallTools callTools -> callTools.calls().stream()
                    .map(ToolCall::name)
                    .map(name -> "\"" + name + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "{\"toolCalls\":[", "]}"));
        };
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JVM.", ex);
        }
    }
}
