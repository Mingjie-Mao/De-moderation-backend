package com.campusguard.evaluation;

import static com.campusguard.moderation.ModerationDecision.ALLOW;
import static com.campusguard.moderation.ModerationDecision.REMOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which engines the report puts side by side.
 *
 * <p>Getting this wrong is quiet. A duplicated pair reads as a real second
 * comparison, and a missing one simply is not there to be noticed — and the
 * missing one is the interesting one, because two prompt versions differ only in
 * their wording and nothing else in the run isolates it.
 */
class BenchmarkPairingTest {

    private final BenchmarkService service =
            new BenchmarkService(
                    null, null, new EngineComparator(), null, null,
                    new EvaluationProperties(java.util.Map.of(), 0, 1, java.time.Duration.ZERO));

    @Test
    void comparesTheOnlyCandidateAgainstTheBaselineExactlyOnce() {
        List<EngineComparator.Comparison> pairs = service.comparePairs(List.of(named("keyword-v1"), named("model/v1")));

        assertThat(pairs)
                .extracting(EngineComparator.Comparison::baselineEngine, EngineComparator.Comparison::candidateEngine)
                .containsExactly(tuple("keyword-v1", "model/v1"));
    }

    /**
     * Every engine is measured against the floor, and each prompt version against
     * the one before it. The second pair is what says whether a rewording helped;
     * the first is what says whether either is worth running at all.
     */
    @Test
    void addsTheNeighbourPairOnlyFromTheThirdEngineOnwards() {
        List<EngineComparator.Comparison> pairs =
                service.comparePairs(List.of(named("keyword-v1"), named("model/v1"), named("model/v2")));

        assertThat(pairs)
                .extracting(EngineComparator.Comparison::baselineEngine, EngineComparator.Comparison::candidateEngine)
                .containsExactly(
                        tuple("keyword-v1", "model/v1"),
                        tuple("keyword-v1", "model/v2"),
                        tuple("model/v1", "model/v2"));
    }

    /** An engine that never answered has nothing to compare, only a reason. */
    @Test
    void dropsEveryPairInvolvingAnEngineThatNeverRan() {
        List<EngineComparator.Comparison> pairs = service.comparePairs(
                List.of(named("keyword-v1"), unavailable("model/v1"), named("model/v2")));

        assertThat(pairs)
                .extracting(EngineComparator.Comparison::baselineEngine, EngineComparator.Comparison::candidateEngine)
                .containsExactly(tuple("keyword-v1", "model/v2"));
    }

    @Test
    void comparesNothingWhenOnlyTheBaselineRan() {
        assertThat(service.comparePairs(List.of(named("keyword-v1")))).isEmpty();
    }

    private EvaluationResult named(String engine) {
        SampleOutcome outcome = new SampleOutcome(
                "s1", null, "NORMAL", SampleProvenance.AUTHORED, REMOVE, ALLOW, 0.8, List.of(), "because",
                "excerpt", 100, null, null, null);
        return new EvaluationResult(
                engine, "dataset", EngineRunStatus.OK, new ConfusionMatrix(), List.of(outcome), null, null);
    }

    private EvaluationResult unavailable(String engine) {
        return EvaluationResult.unavailable(engine, "dataset", "no credentials");
    }
}
