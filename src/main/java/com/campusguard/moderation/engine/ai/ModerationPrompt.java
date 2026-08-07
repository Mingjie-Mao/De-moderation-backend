package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.engine.ModerationRequest;

/**
 * What is actually sent to the model, under a version that is recorded on every
 * call.
 *
 * <p>An interface rather than one class because a prompt revision is a change to
 * the system's behaviour on the same scale as changing model: it moves the
 * numbers, and there is no way to tell by inspection which way. Making versions
 * coexist is what lets the harness score them against each other on one dataset,
 * so "the new wording is better" is a measurement rather than an impression.
 *
 * <p>The alternative — editing the prompt in place and rerunning — cannot answer
 * the question, because the old numbers were produced by code that no longer
 * exists.
 */
public interface ModerationPrompt {

    /**
     * Short, stable, and part of the engine's public name. It is written to every
     * row in {@code ai_invocations}, which is what makes a stored result
     * attributable to the exact wording that produced it.
     */
    String version();

    /** The standing instructions: the task, the rules, and the answer shape. */
    String system();

    /** The content under review. */
    String user(ModerationRequest request);
}
