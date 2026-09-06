package com.campusguard.evaluation.corpus;

/**
 * How much a harvested label is worth.
 *
 * <p>Every row in this corpus is a decision a person actually made, but they are
 * not equally informative. A reviewer who agreed with the engine and moved on has
 * told you very little; a reviewer who overturned an earlier decision has told
 * you that the earlier one was wrong, which is the rarer and more valuable fact.
 *
 * <p>Recorded per row rather than filtered on, so that whoever builds a training
 * set later can weight or exclude by it instead of re-deriving it.
 */
public enum LabelBasis {

    /**
     * A first and only decision, agreeing with the engine.
     *
     * <p>The cheapest kind. It confirms the engine on content the engine already
     * got right, which is where a model needs the least help.
     */
    ROUTINE,

    /**
     * A first and only decision, disagreeing with the engine.
     *
     * <p>The ordinary workhorse of a training set: a person looked at what the
     * model said and did something else.
     */
    CORRECTION,

    /**
     * The decision was revised afterwards.
     *
     * <p>Somebody decided, then a person — often a second one — decided the first
     * call was wrong. The final state carries two people's attention.
     */
    REVISED,

    /**
     * An appeal was upheld against the author, leaving the decision standing.
     *
     * <p>The author argued and a reviewer looked again and kept the outcome. Weaker
     * than an overturn, because inertia and a real second look are hard to tell
     * apart from the outside.
     */
    APPEAL_UPHELD,

    /**
     * An appeal was overturned.
     *
     * <p>The strongest signal available. The author said the decision was wrong, a
     * second reviewer agreed with them, and the outcome changed. These are the rows
     * a model most needs and the ones this system produces fewest of.
     */
    APPEAL_OVERTURNED
}
