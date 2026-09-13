package com.campusguard.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.ModerationProperties;
import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.moderation.engine.ai.AiInvocationRepository;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Repeating a run is a decision about somebody's bill, so what it actually costs
 * has to be pinned down rather than assumed.
 *
 * <p>Two things here are easy to get wrong in a way no other test would catch.
 * Warming up before every run would add three billable calls per repetition for
 * no measurement, and re-attempting an engine that has no credentials would ask a
 * provider to refuse the same request three times.
 */
class BenchmarkRunsTest {

    @Test
    void measuresEachEngineAsManyTimesAsConfigured() throws Exception {
        CountingEngine engine = new CountingEngine("model/v1", true);

        BenchmarkReport report = benchmark(engine, 3, 0).run(null, List.of("model/v1"));

        assertThat(engine.calls()).isEqualTo(3 * DATASET.size());
        assertThat(report.runCount()).isEqualTo(3);
        assertThat(report.repeated()).isTrue();
        assertThat(report.repeats()).singleElement().satisfies(runs ->
                assertThat(runs.measured()).hasSize(3));
    }

    /**
     * The JVM is warm after the first run and the discarded samples are billable,
     * so three runs cost 3n + warmup, not 3(n + warmup).
     */
    @Test
    void warmsUpOnceRatherThanBeforeEveryRun() throws Exception {
        CountingEngine engine = new CountingEngine("model/v1", true);

        benchmark(engine, 3, 2).run(null, List.of("model/v1"));

        assertThat(engine.calls()).isEqualTo(3 * DATASET.size() + 2);
    }

    /** One run, one entry, and a range reported as absent rather than as zero. */
    @Test
    void staysAtOneRunByDefault() throws Exception {
        CountingEngine engine = new CountingEngine("model/v1", true);

        BenchmarkReport report = benchmark(engine, 1, 0).run(null, List.of("model/v1"));

        assertThat(engine.calls()).isEqualTo(DATASET.size());
        assertThat(report.repeated()).isFalse();
        assertThat(report.repeats().getFirst().macroF1().single()).isTrue();
    }

    @Test
    void doesNotAskAnUnregisteredEngineThreeTimes() throws Exception {
        CountingEngine present = new CountingEngine("model/v1", true);

        BenchmarkReport report = benchmark(present, 3, 0).run(null, List.of("model/absent"));

        assertThat(report.repeats()).singleElement().satisfies(runs -> {
            assertThat(runs.runCount()).isEqualTo(1);
            assertThat(runs.measured()).isEmpty();
        });
        assertThat(present.calls()).isZero();
    }

    /**
     * The published run is one of the runs. Everything downstream — the confusion
     * matrix, the CSV rows, the comparison against another engine — comes from
     * it, so it has to be a coherent record of something that happened.
     */
    @Test
    void publishesOneOfTheRunsAndNotAnAverageOfThem() throws Exception {
        BenchmarkReport report = benchmark(new CountingEngine("model/v1", true), 3, 0).run(null, List.of("model/v1"));

        EvaluationResult published = report.results().getFirst();
        assertThat(report.repeats().getFirst().runs()).contains(published);
        assertThat(published.outcomes()).hasSize(DATASET.size());
    }

    // --- fixtures ---

    private static final List<LabelledSample> DATASET = List.of(
            sample("s1", ModerationDecision.ALLOW),
            sample("s2", ModerationDecision.REMOVE),
            sample("s3", ModerationDecision.ALLOW),
            sample("s4", ModerationDecision.ESCALATE));

    private BenchmarkService benchmark(ModerationEngine engine, int runs, int warmupSamples) {
        EvaluationProperties properties =
                new EvaluationProperties(Map.of(), warmupSamples, runs, Duration.ZERO);
        ModerationProperties moderation = new ModerationProperties(
                engine.name(), engine.name(), 20, Duration.ofSeconds(2), Duration.ofMinutes(5));
        EngineRegistry registry = new EngineRegistry(List.of(engine), List.of(), moderation);

        return new BenchmarkService(
                fixedDataset(),
                new EvaluationRunner(registry, new EvaluationUsageCollector(), new CostEstimator(properties), properties),
                new EngineComparator(),
                registry,
                noInvocations(),
                properties);
    }

    /** Returns the in-memory set whatever path it is handed. */
    private EvaluationDataset fixedDataset() {
        return new EvaluationDataset(null) {
            @Override
            public Loaded load(String path) {
                return new Loaded("test", DATASET, false);
            }
        };
    }

    /**
     * A proxy rather than a mocking framework, which this project does not use
     * anywhere else. Only {@code statsByEngine} is reached from a benchmark run,
     * and an empty list is what a database with no model calls in it returns.
     */
    private AiInvocationRepository noInvocations() {
        return (AiInvocationRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {AiInvocationRepository.class},
                (proxy, method, args) -> method.getName().equals("statsByEngine") ? List.of() : null);
    }

    private static LabelledSample sample(String id, ModerationDecision expected) {
        return new LabelledSample(
                id, "NORMAL", expected, "title", "body of " + id, SampleProvenance.AUTHORED, null, null);
    }

    /**
     * Answers correctly and counts how many times it was asked. Correctness does
     * not matter to any assertion here; the call count is the whole point.
     */
    private static final class CountingEngine implements ModerationEngine {

        private final String name;
        private final boolean external;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingEngine(String name, boolean external) {
            this.name = name;
            this.external = external;
        }

        int calls() {
            return calls.get();
        }

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
            calls.incrementAndGet();
            ModerationDecision decision = DATASET.stream()
                    .filter(sample -> request.body().equals("body of " + sample.id()))
                    .map(LabelledSample::expected)
                    .findFirst()
                    .orElse(ModerationDecision.ALLOW);
            return new ModerationVerdict(decision, 0.9, "because", List.of());
        }
    }
}
