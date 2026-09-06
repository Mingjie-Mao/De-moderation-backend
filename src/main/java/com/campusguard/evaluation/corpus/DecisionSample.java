package com.campusguard.evaluation.corpus;

import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One decision a moderator actually made, in a shape something can be trained or
 * measured on later.
 *
 * <p>The evaluation set this project ships is 192 samples, every one of them
 * written for the purpose. This is the other kind: content a student posted, an
 * engine's opinion of it, and what a person did about it. It costs nothing to
 * collect because the system already records all three, and it cannot be
 * collected retroactively — a corpus only exists if somebody started keeping it.
 *
 * <p><b>What cannot be recovered here.</b> The engine's output space is
 * {@code ALLOW / REMOVE / ESCALATE}; a person's is {@code NONE / HIDE / DELETE /
 * BAN}. The first two map cleanly, and ESCALATE does not map at all: it means "a
 * person should look at this", and by the time there is a final action a person
 * has looked at every case. So {@link #label()} is null for no row, but no row
 * can ever carry ESCALATE, and ESCALATE recall is the weakest number this
 * project publishes. What is exported instead is {@link #hard()}, the signals
 * that a case was genuinely difficult, so that ESCALATE labels can be added by a
 * person later rather than invented by a query now.
 *
 * @param contentHash SHA-256 of the text. Lets duplicates be found and a row be
 *     matched back to its case without carrying an id that means nothing outside
 *     this database.
 * @param authorKey a stable pseudonym, not a username. Repeat-offender structure
 *     is most of what makes this corpus interesting, and it survives hashing;
 *     the name does not need to.
 * @param label the engine-space label this decision implies, or null when the
 *     action does not determine one
 * @param hard the decision took a second look — revised, appealed, or slow. A
 *     candidate for an ESCALATE label, not an ESCALATE label.
 * @param priorResolvedCases how many resolved cases this author already had when
 *     this one was decided. The feature an investigation exists to supply, and
 *     the one a single-content classifier can never see.
 */
public record DecisionSample(
        String caseId,
        String targetType,
        String contentHash,
        String authorKey,
        String title,
        String body,
        String engine,
        ModerationDecision engineDecision,
        BigDecimal engineConfidence,
        String engineRationale,
        List<String> ruleCodes,
        FinalAction finalAction,
        Instant decidedAt,
        long minutesToDecision,
        ModerationDecision label,
        LabelBasis basis,
        boolean engineAgreed,
        boolean hard,
        int priorResolvedCases) {

    /**
     * The engine-space label a final action implies.
     *
     * <p>NONE means the report was wrong and the content stays: that is ALLOW.
     * Everything else took the content down, which is REMOVE, whatever the
     * severity. The distinction between HIDE, DELETE and BAN is about how far the
     * consequence reaches, not about whether the content broke a rule, and
     * flattening it here keeps this label in the space the engine is scored in.
     */
    public static ModerationDecision labelFor(FinalAction action) {
        if (action == null) {
            return null;
        }
        return action == FinalAction.NONE ? ModerationDecision.ALLOW : ModerationDecision.REMOVE;
    }
}
