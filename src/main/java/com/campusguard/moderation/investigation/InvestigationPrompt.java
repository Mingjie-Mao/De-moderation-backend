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
}
