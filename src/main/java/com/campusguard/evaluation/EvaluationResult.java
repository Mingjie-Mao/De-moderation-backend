package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import java.util.List;

/**
 * What one engine scored on one dataset.
 *
 * @param latenciesMicros per-sample timings, kept raw so percentiles can be taken
 *     from them. A mean would hide exactly the tail that matters when the engine
 *     becomes a network call.
 *     <p>Microseconds rather than milliseconds because term matching runs in well
 *     under a millisecond, and a baseline that reports 0 ms cannot be compared
 *     against anything. The comparison is the point: a model that scores better
 *     is only worth it at some latency, and that trade needs both numbers.
 */
public record EvaluationResult(
        String engineName,
        String datasetName,
        int sampleCount,
        ConfusionMatrix matrix,
        List<Long> latenciesMicros,
        List<Miss> misses) {

    public long percentileMicros(int percentile) {
        if (latenciesMicros.isEmpty()) {
            return 0;
        }
        List<Long> sorted = latenciesMicros.stream().sorted().toList();
        // Nearest-rank: the smallest value at or above which the given share of
        // observations falls. Chosen over interpolation because it always returns
        // a timing that actually happened.
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return sorted.get(index);
    }

    public double percentileMillis(int percentile) {
        return percentileMicros(percentile) / 1000.0;
    }

    /** A sample the engine got wrong, kept for the failure-mode section of the report. */
    public record Miss(
            String sampleId,
            String category,
            ModerationDecision expected,
            ModerationDecision actual,
            String excerpt,
            String rationale) {
    }
}
