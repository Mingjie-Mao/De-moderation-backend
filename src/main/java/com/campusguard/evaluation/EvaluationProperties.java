package com.campusguard.evaluation;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pricing token prices per engine name. Empty by default and deliberately
 *     so: a plausible-looking default price would put a number in the report that
 *     nobody checked, and a cost figure invented by the tool that reports it is
 *     worse than a blank. Unset engines are reported as having no configured
 *     price rather than as costing nothing.
 * @param warmupSamples how many samples to run and discard before measuring.
 *     Without this the first timing carries class loading and JIT compilation,
 *     which for an in-process engine is two orders of magnitude above its real
 *     cost and drags the mean above the 95th percentile — a number that is
 *     visibly nonsense and quietly wrong. Kept small because for a model-backed
 *     engine each warm-up sample is a real billable call, and there the network
 *     dominates anyway.
 */
@ConfigurationProperties(prefix = "campusguard.evaluation")
public record EvaluationProperties(
        @DefaultValue Map<String, TokenPrice> pricing, @DefaultValue("3") int warmupSamples) {

    /** Prices are quoted per million tokens because that is how providers publish them. */
    public record TokenPrice(
            @DefaultValue("0") BigDecimal inputPerMillion, @DefaultValue("0") BigDecimal outputPerMillion) {
    }
}
