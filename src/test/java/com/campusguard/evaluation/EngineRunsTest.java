package com.campusguard.evaluation;

import static com.campusguard.moderation.ModerationDecision.ALLOW;
import static com.campusguard.moderation.ModerationDecision.ESCALATE;
import static com.campusguard.moderation.ModerationDecision.REMOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusguard.moderation.ModerationDecision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic that turns several runs into a number with a precision.
 *
 * <p>Worth testing without a model or a database because these are the figures
 * that decide whether a prompt change gets kept. A mean that quietly included an
 * unavailable run as a zero, or a "representative" that silently picked the best
 * attempt, would flatter every engine in the report and nothing else in the
 * project would notice.
 */
class EngineRunsTest {

    @Test
    void reportsAMeanAndTheObservedRange() {
        EngineRuns runs = runsScoring(0.60, 0.70, 0.65);

        assertThat(runs.macroF1().mean()).isCloseTo(0.65, within());
        assertThat(runs.macroF1().min()).isCloseTo(0.60, within());
        assertThat(runs.macroF1().max()).isCloseTo(0.70, within());
        assertThat(runs.macroF1().range()).isCloseTo(0.10, within());
        assertThat(runs.macroF1().observations()).isEqualTo(3);
    }

    /**
     * A single run has no range. Reporting one as zero would claim a precision
     * nothing measured — the opposite of what this class is for.
     */
    @Test
    void saysSoRatherThanClaimingZeroSpreadFromOneRun() {
        EngineRuns runs = runsScoring(0.65);

        assertThat(runs.repeated()).isFalse();
        assertThat(runs.macroF1().single()).isTrue();
        assertThat(runs.macroF1().range()).isZero();
    }

    /**
     * The median, so the published figure is a run that happened and sits where
     * the distribution sits. The best attempt would make every engine look as
     * good as its luckiest one.
     */
    @Test
    void standsOnTheMedianRunRatherThanTheBestOrTheFirst() {
        EngineRuns runs = runsScoring(0.90, 0.50, 0.70);

        assertThat(runs.representative().matrix().macroF1()).isCloseTo(0.70, within());
    }

    @Test
    void breaksAnEvenNumberOfRunsTowardsTheLowerMiddle() {
        EngineRuns runs = runsScoring(0.50, 0.60, 0.70, 0.80);

        assertThat(runs.representative().matrix().macroF1()).isCloseTo(0.60, within());
    }

    /**
     * An engine that answered nothing on one attempt has two measurements and a
     * reliability problem, not a third measurement of zero.
     */
    @Test
    void leavesAnUnavailableRunOutOfEveryAverage() {
        List<EvaluationResult> mixed = new ArrayList<>(scoring(0.60, 0.80));
        mixed.add(EvaluationResult.unavailable("model/v1", "set", "no credentials"));

        EngineRuns runs = new EngineRuns("model/v1", mixed);

        assertThat(runs.runCount()).isEqualTo(3);
        assertThat(runs.measured()).hasSize(2);
        assertThat(runs.macroF1().mean()).isCloseTo(0.70, within());
        assertThat(runs.macroF1().observations()).isEqualTo(2);
    }

    @Test
    void fallsBackToTheUnavailableRunWhenNothingWasMeasured() {
        EngineRuns runs = new EngineRuns(
                "model/v1", List.of(EvaluationResult.unavailable("model/v1", "set", "no credentials")));

        assertThat(runs.representative().status()).isEqualTo(EngineRunStatus.UNAVAILABLE);
        assertThat(runs.macroF1().observations()).isZero();
    }

    /**
     * The count that separates "the score is steady" from "the engine is steady".
     * Two runs can average identically while disagreeing about every sample.
     */
    @Test
    void countsTheSamplesTheEngineDidNotAnswerTheSameWayTwice() {
        EvaluationResult first = resultWith(
                outcome("s1", ALLOW, ALLOW), outcome("s2", REMOVE, REMOVE), outcome("s3", ESCALATE, ALLOW));
        EvaluationResult second = resultWith(
                outcome("s1", ALLOW, ALLOW), outcome("s2", REMOVE, ESCALATE), outcome("s3", ESCALATE, REMOVE));

        EngineRuns.Instability instability = new EngineRuns("model/v1", List.of(first, second)).instability();

        assertThat(instability.samples()).isEqualTo(3);
        assertThat(instability.unstableSampleIds()).containsExactly("s2", "s3");
        assertThat(instability.rate()).isCloseTo(2.0 / 3, within());
    }

    /**
     * A sample the provider failed on in one run is not evidence that the engine
     * changed its mind about it.
     */
    @Test
    void doesNotCallAFailedCallAChangeOfMind() {
        EvaluationResult first = resultWith(outcome("s1", ALLOW, ALLOW));
        EvaluationResult second = resultWith(failedOutcome("s1", ALLOW));

        EngineRuns.Instability instability = new EngineRuns("model/v1", List.of(first, second)).instability();

        assertThat(instability.unstable()).isZero();
    }

    @Test
    void refusesToRepresentAnEngineThatWasNeverRun() {
        assertThatThrownBy(() -> new EngineRuns("model/v1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsTokensPerSampleAcrossRunsAndNothingWhenNobodyCounted() {
        EvaluationResult counted = resultWith(outcome("s1", ALLOW, ALLOW, 300), outcome("s2", ALLOW, ALLOW, 500));
        EvaluationResult silent = resultWith(outcome("s1", ALLOW, ALLOW), outcome("s2", ALLOW, ALLOW));

        assertThat(new EngineRuns("model/v1", List.of(counted)).promptTokensPerSample().mean())
                .isCloseTo(400, within());
        assertThat(new EngineRuns("keyword-v1", List.of(silent)).promptTokensPerSample()).isNull();
    }

    // --- fixtures ---

    private org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }

    /**
     * Builds runs whose macro-F1 is exactly the value asked for.
     *
     * <p>Symmetrically: twenty ALLOW and twenty REMOVE samples, with the same
     * number of each answered as the other. Then both labels have precision and
     * recall {@code (20 - wrong) / 20}, so both F1 scores and their unweighted
     * mean are that same fraction. An asymmetric matrix would need the closed
     * form for macro-F1 written into the test, which is the implementation
     * written twice, and a numeric search for a target cannot land on it exactly.
     */
    private EngineRuns runsScoring(double... macroF1) {
        return new EngineRuns("model/v1", scoring(macroF1));
    }

    private List<EvaluationResult> scoring(double... macroF1) {
        List<EvaluationResult> runs = new ArrayList<>();
        for (double score : macroF1) {
            runs.add(resultScoring(score));
        }
        return runs;
    }

    private static final int PER_LABEL = 20;

    private EvaluationResult resultScoring(double target) {
        long wrong = Math.round((1 - target) * PER_LABEL);
        if (Math.abs((PER_LABEL - wrong) / (double) PER_LABEL - target) > 1e-9) {
            throw new IllegalArgumentException(
                    "This fixture can only build multiples of 1/" + PER_LABEL + "; " + target + " is not one.");
        }

        List<SampleOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < PER_LABEL; i++) {
            outcomes.add(outcome("a" + i, ALLOW, i < wrong ? REMOVE : ALLOW));
            outcomes.add(outcome("r" + i, REMOVE, i < wrong ? ALLOW : REMOVE));
        }

        return new EvaluationResult(
                "model/v1", "set", EngineRunStatus.OK, matrixOf(outcomes), outcomes, null, null);
    }

    private ConfusionMatrix matrixOf(List<SampleOutcome> outcomes) {
        ConfusionMatrix matrix = new ConfusionMatrix();
        outcomes.stream()
                .filter(outcome -> !outcome.failed())
                .forEach(outcome -> matrix.record(outcome.expected(), outcome.actual()));
        return matrix;
    }

    private EvaluationResult resultWith(SampleOutcome... outcomes) {
        List<SampleOutcome> all = List.of(outcomes);
        return new EvaluationResult("model/v1", "set", EngineRunStatus.OK, matrixOf(all), all, null, null);
    }

    private SampleOutcome outcome(String id, ModerationDecision expected, ModerationDecision actual) {
        return outcome(id, expected, actual, null);
    }

    private SampleOutcome outcome(String id, ModerationDecision expected, ModerationDecision actual, Integer tokens) {
        return new SampleOutcome(
                id, null, "NORMAL", SampleProvenance.AUTHORED, expected, actual, 0.9, List.of(), "because",
                "excerpt", 1000, tokens, null, null);
    }

    private SampleOutcome failedOutcome(String id, ModerationDecision expected) {
        return new SampleOutcome(
                id, null, "NORMAL", SampleProvenance.AUTHORED, expected, null, 0, List.of(), null, "excerpt",
                1000, null, null, "timed out");
    }
}
