package com.campusguard.evaluation.investigation;

import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.investigation.EvidenceStrength;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a run of the investigation set measured.
 *
 * <p>Four numbers rather than one, because an assistant can fail in four ways
 * that a single accuracy figure would average into meaninglessness: it can
 * recommend the wrong thing, recommend a defensible thing for no stated reason,
 * recommend a different thing every time it is asked, or cost more than it is
 * worth.
 *
 * <p>Stability is here because of a specific failure. Two runs over identical
 * cases once returned BAN and HIDE for the same case, and step counts looked
 * identical, so nothing noticed for five runs. A number that would have noticed
 * belongs in the report rather than in somebody's memory.
 */
public record InvestigationBenchmarkReport(
        String promptVersion,
        String model,
        int scenarios,
        int runsEach,
        double agreement,
        double exactMatch,
        double stability,
        int unanimousScenarios,
        double grounding,
        double averageSteps,
        double averagePromptTokens,
        Map<EvidenceStrength, BandTally> evidence,
        Map<FinalAction, Integer> recommended,
        Map<FinalAction, Integer> expected,
        List<ScenarioOutcome> outcomes) {

    /**
     * @param agreement how often the recommendation was one a moderator could
     *     defend. The headline number, and deliberately not exact match: a
     *     scenario whose answer is genuinely arguable should not punish a brief
     *     for picking the other defensible answer.
     * @param stability the mean, over scenarios, of how often the modal
     *     recommendation was repeated. 1.0 means every scenario gave the same
     *     answer every time it was asked.
     * @param grounding how often the brief cited what the scenario turns on. A
     *     right answer with no citation is a guess that landed.
     * @param evidence the band each run's brief carried, in run order, and null
     *     where the run did not conclude
     * @param groundedRuns how many runs cited everything the scenario turns on.
     *     {@code grounded} needs all of them; this says how near a miss was.
     */
    public record ScenarioOutcome(
            String id,
            String shape,
            FinalAction expected,
            List<FinalAction> recommendations,
            List<EvidenceStrength> evidence,
            FinalAction modal,
            boolean acceptable,
            boolean grounded,
            int groundedRuns,
            double stability,
            double averageSteps,
            double averagePromptTokens,
            List<String> briefs) {
    }

    /**
     * Concluded runs whose brief carried one evidence band, and how many of them
     * recommended something the scenario accepts.
     *
     * <p>The measurement consensus mode exists for. With {@code runs} above one the
     * band stops being the model's grade of itself and becomes a count of how
     * often repeated runs agreed; whether that count is worth showing a reviewer
     * depends entirely on SETTLED being defensible more often than LEANING, and
     * nothing else in this report can say whether it is.
     */
    public record BandTally(int runs, int defensible) {

        public BandTally plus(boolean wasDefensible) {
            return new BandTally(runs + 1, defensible + (wasDefensible ? 1 : 0));
        }
    }

    /** Fixed-order tallies, so two reports can be read side by side without re-sorting. */
    public static Map<FinalAction, Integer> emptyTally() {
        Map<FinalAction, Integer> tally = new LinkedHashMap<>();
        for (FinalAction action : FinalAction.values()) {
            tally.put(action, 0);
        }
        return tally;
    }

    /** Every band present from the start, so one nobody reached reads 0/0 rather than going missing. */
    public static Map<EvidenceStrength, BandTally> emptyBands() {
        Map<EvidenceStrength, BandTally> bands = new LinkedHashMap<>();
        for (EvidenceStrength band : EvidenceStrength.values()) {
            bands.put(band, new BandTally(0, 0));
        }
        return bands;
    }

    /** A short block an operator can paste into a commit message or a report. */
    public String summary() {
        return """
                investigation set: %d scenarios x %d runs, %s on %s
                  agreement    %.3f   (recommendation was one a moderator could defend)
                  exact        %.3f   (recommendation was the single most likely one)
                  stability    %.3f   (%d of %d scenarios answered the same way every time)
                  grounding    %.3f   (brief cited what the scenario turns on)
                  evidence     %s   (defensible runs / runs, by the band the brief carried)
                  cost         %.1f lookups, %.0f prompt tokens per investigation
                  recommended  %s
                  expected     %s
                """
                .formatted(
                        scenarios, runsEach, promptVersion, model,
                        agreement, exactMatch, stability, unanimousScenarios, scenarios,
                        grounding, bands(), averageSteps, averagePromptTokens, recommended, expected);
    }

    private String bands() {
        return evidence.entrySet().stream()
                .map(entry -> "%s %d/%d".formatted(
                        entry.getKey(), entry.getValue().defensible(), entry.getValue().runs()))
                .collect(Collectors.joining(", "));
    }
}
