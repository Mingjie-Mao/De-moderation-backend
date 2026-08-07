package com.campusguard.evaluation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/**
 * Turns token counts into money, when and only when a price has been configured.
 *
 * <p>Returns null rather than zero for an engine with no configured price. Zero is
 * a claim — it is the correct answer for the rule engine, which makes no calls —
 * and printing it for an unpriced model would say the model is free.
 */
@Component
public class CostEstimator {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    private final EvaluationProperties properties;

    public CostEstimator(EvaluationProperties properties) {
        this.properties = properties;
    }

    public BigDecimal estimate(String engineName, OptionalInt promptTokens, OptionalInt completionTokens) {
        // An engine that reports no usage made no billable calls. That is a fact
        // about the rule engine, not a missing price.
        if (promptTokens.isEmpty() && completionTokens.isEmpty()) {
            return BigDecimal.ZERO;
        }

        EvaluationProperties.TokenPrice price = properties.pricing().get(engineName);
        if (price == null) {
            return null;
        }

        BigDecimal input = new BigDecimal(promptTokens.orElse(0))
                .divide(PER_MILLION, MathContext.DECIMAL64)
                .multiply(price.inputPerMillion());

        BigDecimal output = new BigDecimal(completionTokens.orElse(0))
                .divide(PER_MILLION, MathContext.DECIMAL64)
                .multiply(price.outputPerMillion());

        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }
}
