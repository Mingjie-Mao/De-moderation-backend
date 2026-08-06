package com.campusguard.moderation;

/**
 * What an engine recommends. Never what happens: an engine's output is advice
 * that an administrator either takes or overrides.
 *
 * <p>Three values rather than a category per rule, because these are the three
 * things the system can actually do next, and evaluation metrics reported over
 * decisions are directly interpretable. Which rule fired is recorded separately.
 */
public enum ModerationDecision {

    /** Nothing about the content violates the rules. */
    ALLOW,

    /** A rule is clearly broken. */
    REMOVE,

    /** Genuinely uncertain, and a human should look rather than guess. */
    ESCALATE
}
