package com.campusguard.evaluation;

import java.math.BigDecimal;
import java.time.Duration;
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
 * @param runs how many times to measure each engine on the dataset. One by
 *     default, because repeating a run multiplies a model engine's bill by the
 *     same factor and the rule engine needs no repetition to be certain.
 *     <p>Above one, every metric is reported as a mean with the observed range
 *     beside it. That range is the harness's own precision, and without it a
 *     two-point gap between two prompts cannot be distinguished from the same
 *     prompt asked twice — which is exactly what happened to `v1`, measured at
 *     0.636 and then at 0.617 with nothing changed between the runs.
 * @param minCallInterval the shortest gap between two samples. A benchmark is the
 *     one workload that will happily exceed a provider's requests-per-minute
 *     ceiling: two hundred samples back to back is a burst no real traffic
 *     produces. Pacing keeps the run under the limit rather than discovering it
 *     through a wall of 429s, and paying for the retries.
 */
@ConfigurationProperties(prefix = "campusguard.evaluation")
public record EvaluationProperties(
        @DefaultValue Map<String, TokenPrice> pricing,
        @DefaultValue("3") int warmupSamples,
        @DefaultValue("1") int runs,
        @DefaultValue("0s") Duration minCallInterval) {

    public EvaluationProperties {
        if (runs < 1) {
            throw new IllegalArgumentException("campusguard.evaluation.runs must be at least 1.");
        }
    }

    /** Prices are quoted per million tokens because that is how providers publish them. */
    public record TokenPrice(
            @DefaultValue("0") BigDecimal inputPerMillion, @DefaultValue("0") BigDecimal outputPerMillion) {
    }
}
