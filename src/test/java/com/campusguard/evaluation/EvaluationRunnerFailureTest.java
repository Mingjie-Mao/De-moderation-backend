package com.campusguard.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.ModerationProperties;
import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One engine must not be able to end the benchmark.
 *
 * <p>The case that matters is a model with no credentials. Left unhandled it
 * throws on the first sample and takes the baseline numbers down with it, so the
 * run that was supposed to justify adopting the model instead produces nothing at
 * all.
 */
class EvaluationRunnerFailureTest {

    private final EvaluationUsageCollector usageCollector = new EvaluationUsageCollector();
    private final EvaluationProperties properties = new EvaluationProperties(Map.of(), 0, Duration.ZERO);
    private final CostEstimator costEstimator = new CostEstimator(properties);

    @Test
    void marksAnEngineUnavailableWhenEverySampleFails() {
        EvaluationResult result = run(alwaysFailing("broken-v1"));

        assertThat(result.status()).isEqualTo(EngineRunStatus.UNAVAILABLE);
        assertThat(result.unavailableReason()).contains("API key not valid");
        assertThat(result.errorCount()).isEqualTo(result.sampleCount());
        assertThat(result.judgedCount()).isZero();
    }

    /**
     * Partial failure is its own state. Scores computed over the samples that did
     * answer are still worth reading, as long as the report says how many did not.
     */
    @Test
    void marksAnEngineDegradedWhenOnlySomeSamplesFail() {
        AtomicInteger call = new AtomicInteger();
        EvaluationResult result = run(engine("flaky-v1", request -> {
            if (call.getAndIncrement() % 2 == 0) {
                throw new IllegalStateException("upstream hiccup");
            }
            return ModerationVerdict.allow(0.9, "fine");
        }));

        assertThat(result.status()).isEqualTo(EngineRunStatus.DEGRADED);
        assertThat(result.errorCount()).isPositive();
        assertThat(result.judgedCount()).isPositive();
        assertThat(result.sampleCount()).isEqualTo(result.errorCount() + result.judgedCount());
    }

    /** An engine whose bean was never created because it had no credentials is absent, not broken. */
    @Test
    void marksAnUnregisteredEngineUnavailableWithoutThrowing() {
        EvaluationRunner runner = runnerWith(alwaysAllowing("keyword-v1"));

        EvaluationResult result = runner.run("gemini-v1", dataset());

        assertThat(result.status()).isEqualTo(EngineRunStatus.UNAVAILABLE);
        assertThat(result.unavailableReason()).contains("gemini-v1");
        assertThat(result.outcomes()).isEmpty();
    }

    /** A failed sample is recorded with no verdict, so it can never be counted as a wrong answer. */
    @Test
    void keepsFailedSamplesOutOfTheConfusionMatrix() {
        EvaluationResult result = run(alwaysFailing("broken-v1"));

        assertThat(result.matrix().total()).isZero();
        assertThat(result.outcomes()).allSatisfy(outcome -> {
            assertThat(outcome.failed()).isTrue();
            assertThat(outcome.actual()).isNull();
            assertThat(outcome.correct()).isFalse();
            assertThat(outcome.misjudged()).isFalse();
        });
    }

    private EvaluationResult run(ModerationEngine engine) {
        return runnerWith(alwaysAllowing("keyword-v1"), engine).run(engine.name(), dataset());
    }

    private EvaluationRunner runnerWith(ModerationEngine... engines) {
        ModerationProperties moderation = new ModerationProperties(
                "keyword-v1", "keyword-v1", 20, Duration.ofSeconds(2), Duration.ofMinutes(5));
        return new EvaluationRunner(
                new EngineRegistry(List.of(engines), List.of(), moderation), usageCollector, costEstimator, properties);
    }

    private EvaluationDataset.Loaded dataset() {
        return new EvaluationDataset.Loaded(
                "test",
                List.of(
                        new LabelledSample("s1", "NORMAL", ModerationDecision.ALLOW, "t", "ordinary", SampleProvenance.AUTHORED, null),
                        new LabelledSample("s2", "ABUSE", ModerationDecision.REMOVE, "t", "abusive", SampleProvenance.AUTHORED, null),
                        new LabelledSample("s3", "NORMAL", ModerationDecision.ALLOW, "t", "also fine", SampleProvenance.AUTHORED, null),
                        new LabelledSample("s4", "SPAM", ModerationDecision.REMOVE, "t", "buy now", SampleProvenance.AUTHORED, null)),
                false);
    }

    private ModerationEngine alwaysFailing(String name) {
        return engine(name, request -> {
            throw new IllegalStateException("API key not valid. Please pass a valid API key.");
        });
    }

    private ModerationEngine alwaysAllowing(String name) {
        return engine(name, request -> ModerationVerdict.allow(0.5, "nothing matched"));
    }

    private ModerationEngine engine(String name, java.util.function.Function<ModerationRequest, ModerationVerdict> body) {
        return new ModerationEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ModerationVerdict evaluate(ModerationRequest request) {
                return body.apply(request);
            }
        };
    }
}
