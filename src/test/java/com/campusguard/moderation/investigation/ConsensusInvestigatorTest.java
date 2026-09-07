package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.FinalAction;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What repeated asking is actually for.
 *
 * <p>Not the vote. The investigation set puts stability at 0.938, so a majority
 * changes few answers. What it changes is where {@link EvidenceStrength} comes
 * from: it stops being the model's opinion of its own certainty — which was 0.85
 * in seven briefs out of nine when it was a number, and a claim when it became a
 * band — and becomes a count of how often three runs agreed.
 *
 * <p>Plain unit tests. Nothing here needs a database or a model, because none of
 * the behaviour worth pinning involves either.
 */
class ConsensusInvestigatorTest {

    @Test
    void reportsUnanimityAsSettledWhateverTheRunsClaimed() {
        // Every run graded itself OPEN. Three identical answers say otherwise, and
        // the count is what the reviewer is told.
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(complete(FinalAction.HIDE, EvidenceStrength.OPEN, "a"),
                        complete(FinalAction.HIDE, EvidenceStrength.OPEN, "b"),
                        complete(FinalAction.HIDE, EvidenceStrength.OPEN, "c")),
                3);

        InvestigationBrief.Complete brief = (InvestigationBrief.Complete) consensus.investigate(UUID.randomUUID());

        assertThat(brief.recommendation()).isEqualTo(FinalAction.HIDE);
        assertThat(brief.evidenceStrength()).isEqualTo(EvidenceStrength.SETTLED);
    }

    @Test
    void reportsATwoToOneSplitAsLeaning() {
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(complete(FinalAction.BAN, EvidenceStrength.SETTLED, "ban"),
                        complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "hide"),
                        complete(FinalAction.BAN, EvidenceStrength.SETTLED, "ban again")),
                3);

        InvestigationBrief.Complete brief = (InvestigationBrief.Complete) consensus.investigate(UUID.randomUUID());

        assertThat(brief.recommendation()).isEqualTo(FinalAction.BAN);
        // Each run was sure. Together they were not, and that is the honest report.
        assertThat(brief.evidenceStrength()).isEqualTo(EvidenceStrength.LEANING);
    }

    /**
     * Three different answers is the case the assistant could not decide, and
     * saying so is the most useful thing it can do. It is also the one thing it
     * could never say honestly while grading itself.
     */
    @Test
    void reportsAThreeWaySplitAsOpen() {
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(complete(FinalAction.NONE, EvidenceStrength.SETTLED, "none"),
                        complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "hide"),
                        complete(FinalAction.BAN, EvidenceStrength.SETTLED, "ban")),
                3);

        assertThat(((InvestigationBrief.Complete) consensus.investigate(UUID.randomUUID())).evidenceStrength())
                .isEqualTo(EvidenceStrength.OPEN);
    }

    /** Ties go to the more lenient outcome: a split assistant should not be what pushes a case towards action. */
    @Test
    void breaksATieTowardsTheMilderOutcome() {
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(complete(FinalAction.BAN, EvidenceStrength.SETTLED, "ban"),
                        complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "hide")),
                2);

        assertThat(((InvestigationBrief.Complete) consensus.investigate(UUID.randomUUID())).recommendation())
                .isEqualTo(FinalAction.HIDE);
    }

    /**
     * One run's words, not a splice of several.
     *
     * <p>Taking a summary from one run and a recommendation from another would
     * produce reasoning that never argued for its own conclusion, which is worse
     * than either run alone.
     *
     * <p>Asserted as an invariant rather than by naming the winning brief. The
     * runs are concurrent, so which of the two agreeing briefs arrives first is
     * not fixed and is not the property worth pinning; that every field came from
     * the same brief is.
     */
    @Test
    void keepsTheProseThatArguedForTheWinningAnswer() {
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "argues for hiding"),
                        complete(FinalAction.BAN, EvidenceStrength.SETTLED, "argues for banning"),
                        complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "hiding again")),
                3);

        InvestigationBrief.Complete brief = (InvestigationBrief.Complete) consensus.investigate(UUID.randomUUID());

        assertThat(brief.recommendation()).isEqualTo(FinalAction.HIDE);
        assertThat(brief.summary()).isIn("argues for hiding", "hiding again");

        // The fixture writes each brief's counter-evidence from its own summary,
        // so this holds only if both came out of one brief.
        assertThat(brief.counterEvidence()).isEqualTo("against " + brief.summary());
    }

    /** One run failing is not the investigation failing; it counts against the vote by not being in it. */
    @Test
    void carriesOnWhenOneRunDoesNotConclude() {
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "one"),
                        new InvestigationBrief.Inconclusive("The model gave up."),
                        complete(FinalAction.HIDE, EvidenceStrength.SETTLED, "two")),
                3);

        InvestigationBrief.Complete brief = (InvestigationBrief.Complete) consensus.investigate(UUID.randomUUID());

        assertThat(brief.recommendation()).isEqualTo(FinalAction.HIDE);
        // Two of three, not three of three: a run that never answered has not agreed.
        assertThat(brief.evidenceStrength()).isEqualTo(EvidenceStrength.LEANING);
    }

    /**
     * Nothing concluded, so nothing is concluded.
     *
     * <p>Asserted as what it must not be rather than as which failure comes back.
     * The runs are concurrent, so which of two failures a given thread picked up
     * is not fixed; that a vote among no answers does not invent one is.
     */
    @Test
    void doesNotInventABriefWhenNothingConcluded() {
        ConsensusInvestigator consensus = new ConsensusInvestigator(
                scripted(new InvestigationBrief.Inconclusive("The model gave up."),
                        new InvestigationBrief.Partial("Stopped early.", List.of(), "timeout")),
                2);

        InvestigationBrief brief = consensus.investigate(UUID.randomUUID());

        assertThat(brief).isNotInstanceOf(InvestigationBrief.Complete.class);
        assertThat(brief.summary()).isNotBlank();
    }

    @Test
    void asksExactlyAsManyTimesAsItWasTold() {
        AtomicInteger calls = new AtomicInteger();
        Investigator counting = caseId -> {
            calls.incrementAndGet();
            return complete(FinalAction.NONE, EvidenceStrength.SETTLED, "same");
        };

        new ConsensusInvestigator(counting, 3).investigate(UUID.randomUUID());

        assertThat(calls.get()).isEqualTo(3);
    }

    private InvestigationBrief.Complete complete(
            FinalAction action, EvidenceStrength strength, String summary) {
        return new InvestigationBrief.Complete(
                summary, action, strength, "against " + summary, List.of());
    }

    /**
     * Hands out the scripted briefs in order.
     *
     * <p>Synchronised because the runs are concurrent, and a queue read by three
     * virtual threads at once would otherwise make this test flaky for reasons
     * that have nothing to do with what it is testing.
     */
    private Investigator scripted(InvestigationBrief... briefs) {
        Deque<InvestigationBrief> queue = new ArrayDeque<>(List.of(briefs));

        return caseId -> {
            synchronized (queue) {
                InvestigationBrief next = queue.poll();
                if (next == null) {
                    throw new IllegalStateException("The consensus asked more times than the script allows.");
                }
                return next;
            }
        };
    }
}
