package com.campusguard.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {

    @Test
    void multipliesTokensByTheConfiguredPricePerMillion() {
        CostEstimator estimator = estimatorWith(Map.of(
                "gemini-v1", new EvaluationProperties.TokenPrice(new BigDecimal("0.30"), new BigDecimal("2.50"))));

        BigDecimal cost = estimator.estimate("gemini-v1", OptionalInt.of(1_000_000), OptionalInt.of(200_000));

        // 1M input at 0.30 plus 0.2M output at 2.50.
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.80"));
    }

    /**
     * An unpriced engine reports no cost rather than a cost of zero. Zero is a
     * claim, and the claim "this model is free" is one the tool must not make on
     * its own.
     */
    @Test
    void reportsNoCostRatherThanZeroWhenNoPriceIsConfigured() {
        CostEstimator estimator = estimatorWith(Map.of());

        assertThat(estimator.estimate("gemini-v1", OptionalInt.of(1000), OptionalInt.of(500))).isNull();
    }

    /**
     * An engine that reported no usage made no billable calls, which is a fact
     * about the rule engine rather than a missing price.
     */
    @Test
    void reportsZeroForAnEngineThatMadeNoCalls() {
        CostEstimator estimator = estimatorWith(Map.of());

        assertThat(estimator.estimate("keyword-v1", OptionalInt.empty(), OptionalInt.empty()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private CostEstimator estimatorWith(Map<String, EvaluationProperties.TokenPrice> pricing) {
        return new CostEstimator(new EvaluationProperties(pricing, 0, 1, java.time.Duration.ZERO));
    }
}
