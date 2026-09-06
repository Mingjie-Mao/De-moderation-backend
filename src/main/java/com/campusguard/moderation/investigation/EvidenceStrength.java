package com.campusguard.moderation.investigation;

/**
 * How far the evidence settles the question. Three bands, not a number.
 *
 * <p>v1 and v2 asked for a number between 0 and 1 and got 0.85 in seven briefs
 * out of nine. v2's anchor helped only where the model was genuinely torn; the
 * rest of the time it landed on the same habitual value, because a continuous
 * range always has one to land on.
 *
 * <p>The deeper problem was that the number was false precision to begin with.
 * The engine's confidence has an evaluation set behind it — a macro-F1, a
 * confusion matrix, per-class recall — so its two decimals mean something. This
 * one has nothing of the sort, and a reviewer reading 0.85 has no way to know
 * that. A band cannot be read as a measurement and cannot be defaulted into with
 * two decimals of spurious detail.
 *
 * <p>Ordered from most to least settled, so a reviewer scanning a queue can sort
 * by it.
 */
public enum EvidenceStrength {

    /** The record points one way, and being overruled would be a surprise. */
    SETTLED,

    /** The record favours one outcome, but a reviewer could reasonably choose another. */
    LEANING,

    /**
     * Two or more outcomes are equally defensible on what was found.
     *
     * <p>The most useful of the three when it is true. A reviewer who knows the
     * assistant could not decide reads the counter-evidence instead of the
     * recommendation, which is the right way round.
     */
    OPEN;

    /**
     * The band a numeric confidence falls in.
     *
     * <p>Only reached by the earlier prompt versions, which are kept registered so
     * that switching back stays a configuration change. The thresholds are chosen
     * to match what v2's wording asked for — above 0.8 settled, 0.5 or below
     * open — so an old brief lands where its own instructions said it should.
     */
    public static EvidenceStrength ofNumber(double confidence) {
        if (confidence > 0.8) {
            return SETTLED;
        }
        return confidence > 0.5 ? LEANING : OPEN;
    }
}
