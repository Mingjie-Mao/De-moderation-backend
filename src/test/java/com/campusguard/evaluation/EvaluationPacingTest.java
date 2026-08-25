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
import org.junit.jupiter.api.Test;

/**
 * The throttle is there to stay under a provider's per-minute ceiling, so it has
 * to apply to engines that have a provider and to no others.
 *
 * <p>This was wrong once, and silently: the rule engine was paced like everything
 * else, which added sixteen minutes to a two-hundred-sample run and produced no
 * symptom other than a benchmark that felt slow. Nothing in the report would have
 * shown it.
 */
class EvaluationPacingTest {

    private static final Duration LONG_ENOUGH_TO_NOTICE = Duration.ofSeconds(10);

    @Test
    void doesNotThrottleAnEngineThatCallsNobody() {
        long elapsedMillis = timeRun(local("keyword-v1"), LONG_ENOUGH_TO_NOTICE);

        // Four samples at a ten-second interval would be thirty seconds.
        assertThat(elapsedMillis).isLessThan(1_000);
    }

    @Test
    void throttlesAnEngineThatSpendsSomebodyElsesQuota() {
        Duration interval = Duration.ofMillis(150);

        long elapsedMillis = timeRun(remote("model-v1"), interval);

        // Three gaps between four samples; the first sample is not made to wait.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(3 * interval.toMillis());
    }

    private long timeRun(ModerationEngine engine, Duration interval) {
        EvaluationProperties properties = new EvaluationProperties(Map.of(), 0, interval);
        ModerationProperties moderation = new ModerationProperties(
                engine.name(), engine.name(), 20, Duration.ofSeconds(2), Duration.ofMinutes(5));
        EvaluationRunner runner = new EvaluationRunner(
                new EngineRegistry(List.of(engine), List.of(), moderation),
                new EvaluationUsageCollector(),
                new CostEstimator(properties),
                properties);

        long startedAt = System.nanoTime();
        runner.run(engine.name(), dataset());
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private EvaluationDataset.Loaded dataset() {
        return new EvaluationDataset.Loaded(
                "test",
                List.of(sample("s1"), sample("s2"), sample("s3"), sample("s4")),
                false);
    }

    private LabelledSample sample(String id) {
        return new LabelledSample(
                id, "NORMAL", ModerationDecision.ALLOW, "t", "ordinary", SampleProvenance.AUTHORED, null);
    }

    private ModerationEngine local(String name) {
        return engine(name, false);
    }

    private ModerationEngine remote(String name) {
        return engine(name, true);
    }

    private ModerationEngine engine(String name, boolean external) {
        return new ModerationEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean callsAnExternalService() {
                return external;
            }

            @Override
            public ModerationVerdict evaluate(ModerationRequest request) {
                return ModerationVerdict.allow(0.5, "nothing matched");
            }
        };
    }
}
