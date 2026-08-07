package com.campusguard.evaluation;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scores an engine against labelled data.
 *
 * <p>Takes an engine by name rather than the configured one, so the same code
 * measures the rule baseline and any model added later. That is the point of
 * having run it before a model existed: without a floor to compare against, a
 * model's score answers "is it good?" when the question that decides whether to
 * ship it is "is it better than what we already had, by how much, and at what
 * latency and cost?".
 *
 * <p>Failures are contained at the sample. One request that times out costs one
 * data point, not the run; an engine that fails every sample is reported as
 * unavailable rather than as scoring zero, because those two look identical on a
 * table and call for opposite responses.
 */
@Service
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final EngineRegistry engines;
    private final EvaluationUsageCollector usageCollector;
    private final CostEstimator costEstimator;
    private final EvaluationProperties properties;

    public EvaluationRunner(
            EngineRegistry engines,
            EvaluationUsageCollector usageCollector,
            CostEstimator costEstimator,
            EvaluationProperties properties) {
        this.engines = engines;
        this.usageCollector = usageCollector;
        this.costEstimator = costEstimator;
        this.properties = properties;
    }

    public EvaluationResult run(String engineName, EvaluationDataset.Loaded dataset) {
        ModerationEngine engine;
        try {
            engine = engines.require(engineName);
        } catch (IllegalArgumentException ex) {
            // Not registered at all, which is what a model with no credentials
            // looks like: its bean was never created. Reported and skipped so the
            // rest of the benchmark still runs.
            log.warn("Engine '{}' is not available; skipping it.", engineName);
            return EvaluationResult.unavailable(engineName, dataset.name(), ex.getMessage());
        }

        warmUp(engine, dataset);

        List<SampleOutcome> outcomes = new ArrayList<>();
        for (LabelledSample sample : dataset.samples()) {
            outcomes.add(evaluateOne(engine, sample));
        }

        ConfusionMatrix matrix = new ConfusionMatrix();
        outcomes.stream()
                .filter(outcome -> !outcome.failed())
                .forEach(outcome -> matrix.record(outcome.expected(), outcome.actual()));

        int errors = (int) outcomes.stream().filter(SampleOutcome::failed).count();
        EngineRunStatus status = statusFor(outcomes.size(), errors);

        if (status == EngineRunStatus.UNAVAILABLE) {
            String reason = outcomes.stream()
                    .map(SampleOutcome::error)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse("every sample failed");
            log.warn("Engine '{}' failed every sample: {}", engineName, reason);
            return new EvaluationResult(
                    engine.name(), dataset.name(), status, matrix, outcomes, null, reason);
        }

        EvaluationResult provisional = new EvaluationResult(
                engine.name(), dataset.name(), status, matrix, outcomes, null, null);

        return new EvaluationResult(
                engine.name(),
                dataset.name(),
                status,
                matrix,
                outcomes,
                costEstimator.estimate(
                        engine.name(), provisional.totalPromptTokens(), provisional.totalCompletionTokens()),
                null);
    }

    /**
     * Runs and discards a few samples before anything is timed.
     *
     * <p>The first call through any Java code path pays for class loading and
     * interpretation before the JIT has seen it. Measured, that cost lands on
     * whichever sample happened to be first and pulls the mean above the 95th
     * percentile, which is arithmetically impossible for a real distribution and
     * is therefore a tell that the measurement is wrong rather than the engine
     * being slow.
     *
     * <p>Failures here are ignored on purpose: an unavailable engine is diagnosed
     * by the measured run, not by this.
     */
    private void warmUp(ModerationEngine engine, EvaluationDataset.Loaded dataset) {
        dataset.samples().stream().limit(Math.max(0, properties.warmupSamples())).forEach(sample -> {
            try {
                evaluateOne(engine, sample);
            } catch (RuntimeException ignored) {
                // Diagnosed by the measured run.
            }
        });
    }

    private SampleOutcome evaluateOne(ModerationEngine engine, LabelledSample sample) {
        ModerationRequest request = ModerationRequest.of(
                TargetType.POST, UUID.randomUUID(), sample.title(), sample.body(), UUID.randomUUID());

        usageCollector.begin();
        long startedAt = System.nanoTime();

        ModerationVerdict verdict = null;
        String error = null;
        List<EvaluationUsageCollector.CallUsage> usage;

        try {
            verdict = engine.evaluate(request);
        } catch (RuntimeException ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            usage = usageCollector.current();
            usageCollector.end();
        }

        long latencyMicros = Duration.ofNanos(System.nanoTime() - startedAt).toNanos() / 1_000;

        return new SampleOutcome(
                sample.id(),
                sample.category(),
                sample.provenance(),
                sample.expected(),
                verdict == null ? null : verdict.decision(),
                verdict == null ? 0 : verdict.confidence(),
                verdict == null ? List.of() : verdict.ruleCodes(),
                verdict == null ? null : verdict.rationale(),
                excerpt(sample),
                latencyMicros,
                sumTokens(usage, EvaluationUsageCollector.CallUsage::promptTokens),
                sumTokens(usage, EvaluationUsageCollector.CallUsage::completionTokens),
                error);
    }

    private EngineRunStatus statusFor(int total, int errors) {
        if (total > 0 && errors == total) {
            return EngineRunStatus.UNAVAILABLE;
        }
        return errors > 0 ? EngineRunStatus.DEGRADED : EngineRunStatus.OK;
    }

    /**
     * Null rather than zero when nothing reported usage, so an engine that makes
     * no calls stays distinguishable from a provider that did not tell us.
     */
    private Integer sumTokens(
            List<EvaluationUsageCollector.CallUsage> usage,
            java.util.function.Function<EvaluationUsageCollector.CallUsage, Integer> field) {

        List<Integer> values = usage.stream().map(field).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToInt(Integer::intValue).sum();
    }

    private String excerpt(LabelledSample sample) {
        String text = sample.title() == null || sample.title().isBlank()
                ? sample.body()
                : sample.title() + " / " + sample.body();
        String collapsed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 90 ? collapsed : collapsed.substring(0, 87) + "...";
    }
}
