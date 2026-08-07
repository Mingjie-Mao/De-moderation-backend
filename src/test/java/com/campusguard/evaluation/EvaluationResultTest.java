package com.campusguard.evaluation;

import static com.campusguard.moderation.ModerationDecision.ALLOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationResultTest {

    /**
     * Nearest-rank, so every reported percentile is a timing that actually
     * occurred rather than an interpolation between two that did.
     */
    @Test
    void reportsPercentilesByNearestRank() {
        EvaluationResult result = resultWithLatencies(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        assertThat(result.percentileMicros(50)).isEqualTo(5);
        assertThat(result.percentileMicros(95)).isEqualTo(10);
    }

    @Test
    void isNotFooledByUnsortedInput() {
        assertThat(resultWithLatencies(9, 1, 5, 3, 7).percentileMicros(50)).isEqualTo(5);
    }

    /**
     * Term matching answers in well under a millisecond, so a baseline reported in
     * whole milliseconds would be 0 and could not be compared against anything.
     */
    @Test
    void convertsToFractionalMilliseconds() {
        assertThat(resultWithLatencies(420).percentileMillis(50)).isEqualTo(0.42);
    }

    @Test
    void returnsZeroWithNoSamples() {
        assertThat(resultWithLatencies().percentileMicros(95)).isZero();
    }

    /**
     * A failed call has a latency, but it is the latency of a failure. Letting it
     * into the percentile would mean a provider timing out at ten seconds quietly
     * improves or wrecks the reported speed of the answers that did arrive.
     */
    @Test
    void excludesFailedCallsFromLatencyAndCounts() {
        EvaluationResult result = new EvaluationResult(
                "engine",
                "dataset",
                EngineRunStatus.DEGRADED,
                new ConfusionMatrix(),
                List.of(outcome("s1", 10, null), outcome("s2", 20, null), outcome("s3", 10_000_000, "timed out")),
                null,
                null);

        assertThat(result.sampleCount()).isEqualTo(3);
        assertThat(result.judgedCount()).isEqualTo(2);
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.percentileMicros(95)).isEqualTo(20);
    }

    /** Absent rather than zero, so an engine that makes no calls stays distinct from one that reports nothing. */
    @Test
    void reportsTokensAsAbsentWhenNothingReportedThem() {
        assertThat(resultWithLatencies(5).totalPromptTokens()).isEmpty();
    }

    private EvaluationResult resultWithLatencies(long... latenciesMicros) {
        List<SampleOutcome> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < latenciesMicros.length; i++) {
            outcomes.add(outcome("s" + i, latenciesMicros[i], null));
        }
        return new EvaluationResult(
                "test-engine", "test-dataset", EngineRunStatus.OK, new ConfusionMatrix(), outcomes, null, null);
    }

    private SampleOutcome outcome(String id, long latencyMicros, String error) {
        return new SampleOutcome(
                id, "NORMAL", SampleProvenance.AUTHORED, ALLOW, error == null ? ALLOW : null, 0.9, List.of(), "ok", "excerpt",
                latencyMicros, null, null, error);
    }
}
