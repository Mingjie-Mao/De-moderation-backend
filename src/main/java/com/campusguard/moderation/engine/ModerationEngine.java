package com.campusguard.moderation.engine;

/**
 * Something that can judge a piece of content.
 *
 * <p>Named for what it does rather than for how it does it. Calling this a
 * "language model client" would have been accurate for one implementation and
 * wrong for the keyword engine that has to keep working when the model is
 * unreachable, and a fallback that cannot satisfy the same interface as the
 * thing it falls back from is not a fallback.
 *
 * <p>Two consequences follow from putting the seam here: swapping providers is a
 * new implementation rather than a rewrite, and the evaluation harness measures
 * whatever is behind this interface, so a rule engine and a model are scored by
 * the same code on the same data.
 */
public interface ModerationEngine {

    /** Recorded on every case and every evaluation run, so results stay attributable. */
    String name();

    ModerationVerdict evaluate(ModerationRequest request);

    /**
     * Whether judging one sample spends a call on somebody else's metered service.
     *
     * <p>Only the benchmark asks. It throttles itself to stay under a provider's
     * per-minute ceiling, and an engine with no provider should not be made to
     * wait for a limit that does not apply to it — two hundred samples at five
     * seconds each is sixteen minutes added to a run for an engine that answers
     * in under a millisecond.
     *
     * <p>Defaults to true so that an engine added later is throttled until someone
     * says otherwise. The cost of being wrong in that direction is a slow
     * benchmark; the cost of being wrong in the other is a burnt quota.
     */
    default boolean callsAnExternalService() {
        return true;
    }

    /** Capability metadata for clients; false unless an engine explicitly says otherwise. */
    default boolean isLanguageModel() {
        return false;
    }
}
