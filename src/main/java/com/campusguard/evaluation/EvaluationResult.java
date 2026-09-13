package com.campusguard.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * How the engine did on minimal pairs, where a pair is right only if both
     * halves are.
     *
     * <p>The question this answers and per-sample accuracy does not: can the
     * engine tell two nearly identical posts apart when the policy says they get
     * different answers. An engine that keys on topic gets one half of every pair
     * right for free, which reads as 50% accuracy on the pairs' samples and 0% on
     * the pairs themselves. The second number is the one that says whether it
     * understood anything.
     *
     * <p>A pair with a failed call in it is dropped rather than counted wrong. A
     * timeout is not a confusion, and there is no honest way to score half a pair.
     */
    public PairScore pairScore() {
        Map<String, List<SampleOutcome>> byPair = new LinkedHashMap<>();
        outcomes.stream()
                .filter(SampleOutcome::paired)
                .forEach(outcome -> byPair.computeIfAbsent(outcome.pairId(), key -> new ArrayList<>()).add(outcome));

        int bothRight = 0;
        int oneRight = 0;
        int bothWrong = 0;
        int incomplete = 0;

        for (List<SampleOutcome> pair : byPair.values()) {
            if (pair.size() != 2 || pair.stream().anyMatch(SampleOutcome::failed)) {
                incomplete++;
                continue;
            }
            long right = pair.stream().filter(SampleOutcome::correct).count();
            if (right == 2) {
                bothRight++;
            } else if (right == 1) {
                oneRight++;
            } else {
                bothWrong++;
            }
        }

        return new PairScore(bothRight, oneRight, bothWrong, incomplete);
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

    /**
     * @param oneRight the interesting failure. A pair where exactly one half is
     *     right is the engine giving both halves the same answer, which is what
     *     keying on topic looks like from the outside.
     * @param incomplete pairs that could not be scored because a call failed or
     *     the dataset carries an odd half. Reported rather than folded into the
     *     denominator, so a run with provider trouble cannot look like a run with
     *     a confused engine.
     */
    public record PairScore(int bothRight, int oneRight, int bothWrong, int incomplete) {

        public int scored() {
            return bothRight + oneRight + bothWrong;
        }

        /** Zero when there are no pairs, which is what a dataset without them should read as. */
        public double accuracy() {
            return scored() == 0 ? 0 : (double) bothRight / scored();
        }
    }

    public static EvaluationResult unavailable(String engineName, String datasetName, String reason) {
        return new EvaluationResult(
                engineName, datasetName, EngineRunStatus.UNAVAILABLE, new ConfusionMatrix(), List.of(), null, reason);
    }
}
