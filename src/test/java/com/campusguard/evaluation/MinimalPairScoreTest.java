package com.campusguard.evaluation;

import static com.campusguard.moderation.ModerationDecision.ALLOW;
import static com.campusguard.moderation.ModerationDecision.ESCALATE;
import static com.campusguard.moderation.ModerationDecision.REMOVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.ModerationDecision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A pair is right only when both halves are, and the point of that is what it
 * does to an engine that is not really reading.
 *
 * <p>The failure this catches is an engine keying on topic: anything about exams
 * is suspicious, anything about lost keys is fine. On a dataset where topic
 * predicts the label that scores well, and per-sample accuracy cannot see it.
 * Against pairs it scores at zero, because it gives both halves of every pair
 * the same answer and each pair has two different correct answers.
 */
class MinimalPairScoreTest {

    @Test
    void countsAPairRightOnlyWhenBothHalvesAre() {
        EvaluationResult.PairScore score = scoreOf(
                pair("p1", ALLOW, ALLOW, REMOVE, REMOVE),
                pair("p2", ALLOW, ALLOW, REMOVE, ALLOW),
                pair("p3", ALLOW, REMOVE, REMOVE, ALLOW));

        assertThat(score.bothRight()).isEqualTo(1);
        assertThat(score.oneRight()).isEqualTo(1);
        assertThat(score.bothWrong()).isEqualTo(1);
        assertThat(score.scored()).isEqualTo(3);
        assertThat(score.accuracy()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * The whole reason the metric exists. Every pair answered identically, which
     * is 50% of the samples and none of the pairs.
     */
    @Test
    void givesNothingToAnEngineThatAnswersByTopic() {
        EvaluationResult result = resultOf(
                pair("p1", ALLOW, ALLOW, REMOVE, ALLOW),
                pair("p2", ALLOW, ALLOW, ESCALATE, ALLOW),
                pair("p3", REMOVE, REMOVE, ALLOW, REMOVE));

        assertThat(result.matrix().accuracy()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.pairScore().accuracy()).isZero();
        assertThat(result.pairScore().oneRight()).isEqualTo(3);
    }

    /**
     * A timeout is not a confusion, and half a pair cannot be scored either way.
     * Counting it wrong would let a bad afternoon at the provider read as an
     * engine that cannot tell two posts apart.
     */
    @Test
    void setsAsideAPairWithAFailedCallInsteadOfCallingItWrong() {
        EvaluationResult.PairScore score = scoreOf(
                pair("p1", ALLOW, ALLOW, REMOVE, REMOVE),
                List.of(outcome("p2a", "p2", ALLOW, ALLOW, null), outcome("p2b", "p2", REMOVE, null, "timed out")));

        assertThat(score.bothRight()).isEqualTo(1);
        assertThat(score.scored()).isEqualTo(1);
        assertThat(score.incomplete()).isEqualTo(1);
        assertThat(score.accuracy()).isEqualTo(1.0);
    }

    /** A dataset half-converted to pairs should report the gap, not average over it. */
    @Test
    void setsAsideAHalfPairWithNoPartner() {
        EvaluationResult.PairScore score = scoreOf(List.of(outcome("lonely", "p9", ALLOW, ALLOW, null)));

        assertThat(score.scored()).isZero();
        assertThat(score.incomplete()).isEqualTo(1);
    }

    @Test
    void readsAsZeroOnADatasetWithoutPairs() {
        EvaluationResult.PairScore score = scoreOf(List.of(outcome("s1", null, ALLOW, ALLOW, null)));

        assertThat(score.scored()).isZero();
        assertThat(score.incomplete()).isZero();
        assertThat(score.accuracy()).isZero();
    }

    // --- fixtures ---

    private List<SampleOutcome> pair(
            String pairId,
            ModerationDecision firstExpected,
            ModerationDecision firstActual,
            ModerationDecision secondExpected,
            ModerationDecision secondActual) {

        return List.of(
                outcome(pairId + "a", pairId, firstExpected, firstActual, null),
                outcome(pairId + "b", pairId, secondExpected, secondActual, null));
    }

    @SafeVarargs
    private EvaluationResult.PairScore scoreOf(List<SampleOutcome>... groups) {
        return resultOf(groups).pairScore();
    }

    @SafeVarargs
    private EvaluationResult resultOf(List<SampleOutcome>... groups) {
        List<SampleOutcome> all = new ArrayList<>();
        for (List<SampleOutcome> group : groups) {
            all.addAll(group);
        }

        ConfusionMatrix matrix = new ConfusionMatrix();
        all.stream()
                .filter(outcome -> !outcome.failed())
                .forEach(outcome -> matrix.record(outcome.expected(), outcome.actual()));

        return new EvaluationResult("model/v1", "held-out", EngineRunStatus.OK, matrix, all, null, null);
    }

    private SampleOutcome outcome(
            String id, String pairId, ModerationDecision expected, ModerationDecision actual, String error) {
        return new SampleOutcome(
                id, pairId, "NORMAL", SampleProvenance.AUTHORED, expected, actual, 0.9, List.of(), "because",
                "excerpt", 1000, null, null, error);
    }
}
