package com.campusguard.evaluation;

import static com.campusguard.moderation.ModerationDecision.ALLOW;
import static com.campusguard.moderation.ModerationDecision.ESCALATE;
import static com.campusguard.moderation.ModerationDecision.REMOVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.ModerationDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The comparison exists to answer the question an average cannot: did the
 * candidate fix the baseline's mistakes, or swap them for different ones?
 */
class EngineComparatorTest {

    private final EngineComparator comparator = new EngineComparator();

    @Test
    void separatesWhatTheCandidateFixedFromWhatItBroke() {
        EvaluationResult baseline = resultOf(
                outcome("s1", REMOVE, REMOVE),
                outcome("s2", REMOVE, ALLOW),
                outcome("s3", ALLOW, ALLOW),
                outcome("s4", ESCALATE, REMOVE));

        EvaluationResult candidate = resultOf(
                outcome("s1", REMOVE, REMOVE),
                outcome("s2", REMOVE, REMOVE),
                outcome("s3", ALLOW, REMOVE),
                outcome("s4", ESCALATE, ALLOW));

        EngineComparator.Comparison comparison = comparator.compare(baseline, candidate);

        assertThat(comparison.bothRight()).isEqualTo(1);
        assertThat(comparison.candidateFixed()).extracting(EngineComparator.SampleDelta::sampleId)
                .containsExactly("s2");
        assertThat(comparison.candidateBroke()).extracting(EngineComparator.SampleDelta::sampleId)
                .containsExactly("s3");
        assertThat(comparison.bothWrong()).extracting(EngineComparator.SampleDelta::sampleId)
                .containsExactly("s4");
        assertThat(comparison.netGain()).isZero();
    }

    /**
     * Two engines can score identically and be wrong about entirely different
     * content. A summary would call that a tie; only the sample-level view shows
     * that switching would trade one set of complaints for another.
     */
    @Test
    void showsATradeEvenWhenTheNetIsZero() {
        EvaluationResult baseline = resultOf(outcome("a", REMOVE, ALLOW), outcome("b", ALLOW, ALLOW));
        EvaluationResult candidate = resultOf(outcome("a", REMOVE, REMOVE), outcome("b", ALLOW, REMOVE));

        EngineComparator.Comparison comparison = comparator.compare(baseline, candidate);

        assertThat(comparison.netGain()).isZero();
        assertThat(comparison.candidateFixed()).hasSize(1);
        assertThat(comparison.candidateBroke()).hasSize(1);
    }

    /**
     * A call that failed is not a wrong answer. Counting it as one would make an
     * outage look like a quality regression and send the reader after the wrong
     * problem.
     */
    @Test
    void ignoresSamplesWhereEitherEngineFailedToAnswer() {
        EvaluationResult baseline = resultOf(outcome("s1", REMOVE, ALLOW), outcome("s2", REMOVE, ALLOW));
        EvaluationResult candidate = resultOf(outcome("s1", REMOVE, REMOVE), failed("s2", REMOVE));

        EngineComparator.Comparison comparison = comparator.compare(baseline, candidate);

        assertThat(comparison.candidateFixed()).hasSize(1);
        assertThat(comparison.candidateBroke()).isEmpty();
        assertThat(comparison.bothWrong()).isEmpty();
    }

    @Test
    void ignoresSamplesOnlyOneEngineSaw() {
        EvaluationResult baseline = resultOf(outcome("shared", REMOVE, ALLOW), outcome("only-baseline", ALLOW, ALLOW));
        EvaluationResult candidate = resultOf(outcome("shared", REMOVE, REMOVE));

        EngineComparator.Comparison comparison = comparator.compare(baseline, candidate);

        assertThat(comparison.bothRight()).isZero();
        assertThat(comparison.candidateFixed()).hasSize(1);
    }

    private EvaluationResult resultOf(SampleOutcome... outcomes) {
        return new EvaluationResult(
                "engine", "dataset", EngineRunStatus.OK, new ConfusionMatrix(), List.of(outcomes), null, null);
    }

    private SampleOutcome outcome(String id, ModerationDecision expected, ModerationDecision actual) {
        return new SampleOutcome(id, "NORMAL", expected, actual, 0.8, List.of(), "because", "excerpt", 100, null, null, null);
    }

    private SampleOutcome failed(String id, ModerationDecision expected) {
        return new SampleOutcome(id, "NORMAL", expected, null, 0, List.of(), null, "excerpt", 100, null, null, "timed out");
    }
}
