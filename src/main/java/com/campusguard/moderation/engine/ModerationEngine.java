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
}
