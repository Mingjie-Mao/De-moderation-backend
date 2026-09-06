package com.campusguard.moderation.investigation;

import com.campusguard.moderation.admin.ModerationCaseDetail;

/**
 * The wording the investigator is given, versioned the way the moderation
 * prompts are.
 *
 * <p>Separate from the loop for the same reason {@code ModerationPrompt} is
 * separate from the engine: the loop's behaviour under a step limit or a
 * fabricated citation has to be testable without settling on any particular
 * wording, and the wording has to be replaceable without touching the loop.
 */
public interface InvestigationPrompt {

    /** Recorded against every call, so a brief can be attributed to the wording that produced it. */
    String version();

    String system();

    /** The opening message: what this case is, before the model has looked anything up. */
    String opening(ModerationCaseDetail detail);

    /**
     * Lookups run before the first turn, as if the model had asked for them.
     *
     * <p>On the prompt rather than on the loop because the two are one decision:
     * a wording that says "the author's record is below" is only correct when the
     * record actually is. Splitting them into separate switches would let a
     * version run against evidence its own instructions do not describe.
     *
     * <p>Empty by default, which is what the earlier versions want — they tell the
     * model to choose its own lookups, and pre-supplying results would leave them
     * describing a conversation that did not happen.
     */
    default java.util.List<ToolCall> prefetch(ModerationCaseDetail detail) {
        return java.util.List.of();
    }
}
