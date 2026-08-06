package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts of predicted against actual, and the metrics derived from them.
 *
 * <p>Pure arithmetic with no Spring and no database, which is what makes it worth
 * unit testing: the numbers this produces are the whole argument for or against
 * an engine, and a quietly wrong denominator here would be invisible in every
 * integration test in the project.
 */
public final class ConfusionMatrix {

    private final Map<ModerationDecision, Map<ModerationDecision, Integer>> counts = new EnumMap<>(ModerationDecision.class);
    private int total;

    public void record(ModerationDecision actual, ModerationDecision predicted) {
        counts.computeIfAbsent(actual, key -> new EnumMap<>(ModerationDecision.class))
                .merge(predicted, 1, Integer::sum);
        total++;
    }

    public int count(ModerationDecision actual, ModerationDecision predicted) {
        return counts.getOrDefault(actual, Map.of()).getOrDefault(predicted, 0);
    }

    public int total() {
        return total;
    }

    /**
     * Every label that appears as either a true label or a prediction.
     *
     * <p>Predicted-but-never-correct labels are included on purpose: an engine
     * that invents a class nobody labelled has made errors, and dropping that
     * class would hide them by averaging over fewer terms.
     */
    public List<ModerationDecision> labels() {
        Set<ModerationDecision> present = new LinkedHashSet<>();
        for (ModerationDecision decision : ModerationDecision.values()) {
            boolean appears = counts.containsKey(decision)
                    || counts.values().stream().anyMatch(row -> row.containsKey(decision));
            if (appears) {
                present.add(decision);
            }
        }
        return List.copyOf(present);
    }

    public ClassMetrics metricsFor(ModerationDecision label) {
        int truePositives = count(label, label);

        int predictedAsLabel = 0;
        for (ModerationDecision actual : ModerationDecision.values()) {
            predictedAsLabel += count(actual, label);
        }
        int falsePositives = predictedAsLabel - truePositives;

        int actuallyLabel = 0;
        for (ModerationDecision predicted : ModerationDecision.values()) {
            actuallyLabel += count(label, predicted);
        }
        int falseNegatives = actuallyLabel - truePositives;

        double precision = ratio(truePositives, truePositives + falsePositives);
        double recall = ratio(truePositives, truePositives + falseNegatives);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

        return new ClassMetrics(label, actuallyLabel, truePositives, falsePositives, falseNegatives, precision, recall, f1);
    }

    /**
     * Unweighted mean of the per-class F1 scores.
     *
     * <p>Macro rather than micro because the classes are wildly unbalanced: most
     * content is fine, so a micro average would be dominated by ALLOW and an
     * engine that never removed anything would still score well.
     */
    public double macroF1() {
        List<ModerationDecision> labels = labels();
        if (labels.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (ModerationDecision label : labels) {
            sum += metricsFor(label).f1();
        }
        return sum / labels.size();
    }

    public double accuracy() {
        if (total == 0) {
            return 0;
        }
        int correct = 0;
        for (ModerationDecision label : ModerationDecision.values()) {
            correct += count(label, label);
        }
        return (double) correct / total;
    }

    public List<ClassMetrics> allMetrics() {
        List<ClassMetrics> metrics = new ArrayList<>();
        for (ModerationDecision label : labels()) {
            metrics.add(metricsFor(label));
        }
        return metrics;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    public record ClassMetrics(
            ModerationDecision label,
            int support,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1) {
    }
}
