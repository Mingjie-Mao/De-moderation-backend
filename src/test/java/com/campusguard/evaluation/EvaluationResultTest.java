package com.campusguard.evaluation;

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
        EvaluationResult result = resultWithLatencies(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));

        assertThat(result.percentileMicros(50)).isEqualTo(5);
        assertThat(result.percentileMicros(95)).isEqualTo(10);
    }

    @Test
    void isNotFooledByUnsortedInput() {
        EvaluationResult result = resultWithLatencies(List.of(9L, 1L, 5L, 3L, 7L));

        assertThat(result.percentileMicros(50)).isEqualTo(5);
    }

    /**
     * Term matching answers in well under a millisecond, so a baseline reported in
     * whole milliseconds would be 0 and could not be compared against anything.
     */
    @Test
    void convertsToFractionalMilliseconds() {
        EvaluationResult result = resultWithLatencies(List.of(420L));

        assertThat(result.percentileMillis(50)).isEqualTo(0.42);
    }

    @Test
    void returnsZeroWithNoSamples() {
        assertThat(resultWithLatencies(List.of()).percentileMicros(95)).isZero();
    }

    private EvaluationResult resultWithLatencies(List<Long> latencies) {
        return new EvaluationResult(
                "test-engine", "test-dataset", latencies.size(), new ConfusionMatrix(), latencies, List.of());
    }
}
