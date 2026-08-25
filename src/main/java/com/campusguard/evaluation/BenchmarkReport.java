package com.campusguard.evaluation;

import com.campusguard.moderation.engine.ai.EngineInvocationStats;
import java.time.Instant;
import java.util.List;

/**
 * One benchmark run across every engine, and everything derived from it.
 *
 * @param comparisons each engine after the first, compared against the first.
 *     The first engine is the baseline by convention, which is why the rule
 *     engine is listed before any model.
 * @param productionStats what the engines have done on real traffic, so effect,
 *     latency and cost can be weighed together rather than accuracy alone
 * @param starterDataset true when the run used the bundled sample set, which
 *     exists to prove the harness works and measures nothing
 */
public record BenchmarkReport(
        Instant generatedAt,
        String datasetName,
        int sampleCount,
        boolean starterDataset,
        List<EvaluationResult> results,
        List<EngineComparator.Comparison> comparisons,
        List<EngineInvocationStats> productionStats) {
}
