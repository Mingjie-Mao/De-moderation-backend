package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Puts two engines' answers side by side, sample by sample.
 *
 * <p>Aggregate scores say which engine is better on average and stop there. They
 * cannot say whether a higher-scoring engine fixed the failures of the other or
 * merely traded them for different ones, and that distinction is the whole
 * argument for adopting it. A model that is three points better overall but wrong
 * on everything the rules got right has not replaced the rules; it has swapped
 * one set of complaints for another.
 */
@Component
public class EngineComparator {

    public Comparison compare(EvaluationResult baseline, EvaluationResult candidate) {
        Map<String, SampleOutcome> baselineById = byId(baseline);
        Map<String, SampleOutcome> candidateById = byId(candidate);

        List<SampleDelta> candidateFixed = new ArrayList<>();
        List<SampleDelta> candidateBroke = new ArrayList<>();
        List<SampleDelta> bothWrong = new ArrayList<>();
        int bothRight = 0;

        for (Map.Entry<String, SampleOutcome> entry : baselineById.entrySet()) {
            SampleOutcome before = entry.getValue();
            SampleOutcome after = candidateById.get(entry.getKey());
            if (after == null) {
                continue;
            }

            // A failed call is not a wrong answer, so it is excluded from a
            // comparison that is about judgement rather than availability.
            if (before.failed() || after.failed()) {
                continue;
            }

            SampleDelta delta = new SampleDelta(
                    before.sampleId(),
                    before.category(),
                    before.expected(),
                    before.actual(),
                    after.actual(),
                    before.excerpt(),
                    after.rationale());

            if (before.correct() && after.correct()) {
                bothRight++;
            } else if (!before.correct() && after.correct()) {
                candidateFixed.add(delta);
            } else if (before.correct()) {
                candidateBroke.add(delta);
            } else {
                bothWrong.add(delta);
            }
        }

        return new Comparison(
                baseline.engineName(), candidate.engineName(), bothRight, candidateFixed, candidateBroke, bothWrong);
    }

    private Map<String, SampleOutcome> byId(EvaluationResult result) {
        return result.outcomes().stream()
                .collect(Collectors.toMap(
                        SampleOutcome::sampleId, Function.identity(), (first, second) -> first, LinkedHashMap::new));
    }

    /**
     * @param candidateFixed samples the baseline got wrong and the candidate got
     *     right: the case for the change
     * @param candidateBroke samples the baseline got right and the candidate got
     *     wrong: the cost of it, and the column nobody publishes voluntarily
     */
    public record Comparison(
            String baselineEngine,
            String candidateEngine,
            int bothRight,
            List<SampleDelta> candidateFixed,
            List<SampleDelta> candidateBroke,
            List<SampleDelta> bothWrong) {

        public int netGain() {
            return candidateFixed.size() - candidateBroke.size();
        }
    }

    public record SampleDelta(
            String sampleId,
            String category,
            ModerationDecision expected,
            ModerationDecision baselineSaid,
            ModerationDecision candidateSaid,
            String excerpt,
            String candidateRationale) {
    }
}
