package com.campusguard.evaluation;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalInt;

/**
 * What one engine scored on one dataset, plus what it cost to find out.
 *
 * <p>Everything except the confusion matrix is derived from {@link #outcomes},
 * so the aggregate numbers and the per-sample table cannot disagree with each
 * other.
 *
 * @param unavailableReason set only when the engine answered nothing, so the
 *     report can say "no API key" instead of printing a column of zeros that
 *     reads like a terrible model
 */
public record EvaluationResult(
        String engineName,
        String datasetName,
        EngineRunStatus status,
        ConfusionMatrix matrix,
        List<SampleOutcome> outcomes,
        BigDecimal estimatedCost,
        String unavailableReason) {

    public int sampleCount() {
        return outcomes.size();
    }

    /** Samples the engine answered, which is the denominator for every score below. */
    public int judgedCount() {
        return (int) outcomes.stream().filter(outcome -> !outcome.failed()).count();
    }

    public int errorCount() {
        return (int) outcomes.stream().filter(SampleOutcome::failed).count();
    }

    /**
     * The same scoring restricted to one source of samples.
     *
     * <p>A wide gap between the two is the most useful single number this report
     * produces about its own dataset: it means the authored half is easier than
     * the real half, and the headline score is flattering by however much.
     */
    public ConfusionMatrix matrixFor(SampleProvenance provenance) {
        ConfusionMatrix restricted = new ConfusionMatrix();
        outcomes.stream()
                .filter(outcome -> !outcome.failed() && outcome.provenance() == provenance)
                .forEach(outcome -> restricted.record(outcome.expected(), outcome.actual()));
        return restricted;
    }

    public List<SampleOutcome> misses() {
        return outcomes.stream().filter(SampleOutcome::misjudged).toList();
    }

    public long percentileMicros(int percentile) {
        List<Long> sorted = outcomes.stream()
                .filter(outcome -> !outcome.failed())
                .map(SampleOutcome::latencyMicros)
                .sorted()
                .toList();

        if (sorted.isEmpty()) {
            return 0;
        }
        // Nearest-rank: always a timing that actually occurred, rather than an
        // interpolation between two that did.
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return sorted.get(index);
    }

    public double percentileMillis(int percentile) {
        return percentileMicros(percentile) / 1000.0;
    }

    public double meanMillis() {
        return outcomes.stream()
                .filter(outcome -> !outcome.failed())
                .mapToLong(SampleOutcome::latencyMicros)
                .average()
                .orElse(0)
                / 1000.0;
    }

    /** Empty rather than zero when the engine reports no usage, so "free" and "unknown" stay distinct. */
    public OptionalInt totalPromptTokens() {
        return sumTokens(SampleOutcome::promptTokens);
    }

    public OptionalInt totalCompletionTokens() {
        return sumTokens(SampleOutcome::completionTokens);
    }

    private OptionalInt sumTokens(java.util.function.Function<SampleOutcome, Integer> field) {
        List<Integer> values = outcomes.stream().map(field).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? OptionalInt.empty() : OptionalInt.of(values.stream().mapToInt(Integer::intValue).sum());
    }

    public static EvaluationResult unavailable(String engineName, String datasetName, String reason) {
        return new EvaluationResult(
                engineName, datasetName, EngineRunStatus.UNAVAILABLE, new ConfusionMatrix(), List.of(), null, reason);
    }
}
