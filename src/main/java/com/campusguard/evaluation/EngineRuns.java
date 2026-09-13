package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * One engine measured more than once on the same data.
 *
 * <p>Why this exists. An earlier run of `v1` on the 192-sample set scored macro-F1
 * 0.636; an identical run of identical code on identical data scored 0.617. Same
 * prompt, same samples, {@code temperature: 0.0}. Nothing was wrong with either
 * number — a provider's model is not a pure function, and the report had no way
 * to say so. So every comparison in the file was being read to three decimal
 * places against an instrument whose own reading moves by two hundredths, and a
 * two-point "improvement" was indistinguishable from the same prompt asked twice.
 *
 * <p>What it changes: a metric stops being a number and becomes a mean with a
 * range around it, and the range is the smallest difference the harness can
 * honestly call a difference. A gap narrower than the spread is not a finding,
 * and with this in the report nobody has to remember that.
 *
 * <p>Runs where the engine was unavailable are excluded from every average rather
 * than counted as zero. An engine that answered nothing on one of three attempts
 * has two measurements and a reliability problem, not a third measurement of
 * zero.
 */
public record EngineRuns(String engineName, List<EvaluationResult> runs) {

    public EngineRuns {
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("An engine needs at least one run.");
        }
        runs = List.copyOf(runs);
    }

    public int runCount() {
        return runs.size();
    }

    public boolean repeated() {
        return runs.size() > 1;
    }

    /** The runs that produced numbers. */
    public List<EvaluationResult> measured() {
        return runs.stream().filter(run -> run.status() != EngineRunStatus.UNAVAILABLE).toList();
    }

    /**
     * The single run that stands for the set everywhere a run is needed whole:
     * the confusion matrix, the per-sample table, the comparison against another
     * engine.
     *
     * <p>The median by macro-F1, not the first and not the best. The first is an
     * arbitrary choice presented as a measurement; the best is the same arbitrary
     * choice with a thumb on it, and publishing it would make every engine look
     * as good as its luckiest attempt. The median is a real run — one that
     * actually happened, whose per-sample answers are internally consistent —
     * sitting where the distribution sits. An average of three confusion matrices
     * would not correspond to any run at all, and could not be traced to a row in
     * the CSV.
     *
     * <p>With an even number of runs it is the lower of the two middles, so the
     * choice is deterministic rather than a coin toss between two files.
     */
    public EvaluationResult representative() {
        List<EvaluationResult> measured = measured();
        if (measured.isEmpty()) {
            return runs.getFirst();
        }
        List<EvaluationResult> sorted = new ArrayList<>(measured);
        sorted.sort(Comparator.comparingDouble(run -> run.matrix().macroF1()));
        return sorted.get((sorted.size() - 1) / 2);
    }

    public Spread macroF1() {
        return spread(run -> run.matrix().macroF1());
    }

    public Spread accuracy() {
        return spread(run -> run.matrix().accuracy());
    }

    public Spread recall(ModerationDecision decision) {
        return spread(run -> run.matrix().metricsFor(decision).recall());
    }

    public Spread pairAccuracy() {
        return spread(run -> run.pairScore().accuracy());
    }

    public Spread meanLatencyMillis() {
        return spread(EvaluationResult::meanMillis);
    }

    /** Null when no run reported usage, so "free" and "not told" stay distinct. */
    public Spread promptTokensPerSample() {
        List<EvaluationResult> measured = measured().stream()
                .filter(run -> run.totalPromptTokens().isPresent() && run.judgedCount() > 0)
                .toList();
        if (measured.isEmpty()) {
            return null;
        }
        return spreadOf(measured, run -> (double) run.totalPromptTokens().getAsInt() / run.judgedCount());
    }

    /**
     * How often the engine changed its mind about the same sample.
     *
     * <p>The aggregate spread says the score moves; this says where it moves. A
     * macro-F1 that is stable across runs because the same eleven samples flip in
     * opposite directions each time is a different animal from one that is stable
     * because the engine is, and only a per-sample count separates them. It is
     * also the honest denominator for a prompt comparison: a change that moves
     * fewer samples than the engine moves on its own has not been shown to do
     * anything.
     */
    public Instability instability() {
        Map<String, Set<ModerationDecision>> answers = new LinkedHashMap<>();
        for (EvaluationResult run : measured()) {
            for (SampleOutcome outcome : run.outcomes()) {
                if (!outcome.failed()) {
                    answers.computeIfAbsent(outcome.sampleId(), key -> new LinkedHashSet<>()).add(outcome.actual());
                }
            }
        }

        List<String> unstable = answers.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .toList();

        return new Instability(answers.size(), unstable);
    }

    private Spread spread(ToDoubleFunction<EvaluationResult> metric) {
        return spreadOf(measured(), metric);
    }

    private Spread spreadOf(List<EvaluationResult> measured, ToDoubleFunction<EvaluationResult> metric) {
        if (measured.isEmpty()) {
            return new Spread(0, 0, 0, 0);
        }
        double[] values = measured.stream().mapToDouble(metric).toArray();
        double sum = 0;
        double min = values[0];
        double max = values[0];
        for (double value : values) {
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new Spread(sum / values.length, min, max, values.length);
    }

    /**
     * A metric as the harness can actually report it.
     *
     * @param observations how many runs are behind the mean. One means the range
     *     is zero because nothing was repeated, not because the engine is steady,
     *     and a reader has to be able to tell those apart.
     */
    public record Spread(double mean, double min, double max, int observations) {

        public double range() {
            return max - min;
        }

        public boolean single() {
            return observations <= 1;
        }
    }

    /**
     * @param unstableSampleIds listed rather than counted, because the samples an
     *     engine cannot answer twice the same way are the first place to look when
     *     deciding whether a disagreement in the report is worth arguing about
     */
    public record Instability(int samples, List<String> unstableSampleIds) {

        public Instability {
            unstableSampleIds = List.copyOf(unstableSampleIds);
        }

        public int unstable() {
            return unstableSampleIds.size();
        }

        public double rate() {
            return samples == 0 ? 0 : (double) unstable() / samples;
        }
    }
}
