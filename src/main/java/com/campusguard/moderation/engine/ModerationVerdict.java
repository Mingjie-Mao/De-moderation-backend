package com.campusguard.moderation.engine;

import com.campusguard.moderation.ModerationDecision;
import java.util.List;

/**
 * An engine's answer about one piece of content.
 *
 * @param decision what the engine recommends
 * @param confidence how sure it is, in [0, 1]
 * @param rationale a sentence an administrator can read; the point of the
 *     workflow is that a person decides, and a person cannot weigh a bare label
 * @param ruleCodes which rules the engine believes were broken
 */
public record ModerationVerdict(
        ModerationDecision decision, double confidence, String rationale, List<String> ruleCodes) {

    public ModerationVerdict {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        // Validated in the constructor rather than trusted, because the next
        // implementation of this interface produces its fields from a language
        // model and a confidence of 1.7 must not reach the database.
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be within [0, 1], got " + confidence);
        }
        ruleCodes = ruleCodes == null ? List.of() : List.copyOf(ruleCodes);
        rationale = rationale == null ? "" : rationale;
    }

    public static ModerationVerdict allow(double confidence, String rationale) {
        return new ModerationVerdict(ModerationDecision.ALLOW, confidence, rationale, List.of());
    }

    public static ModerationVerdict escalate(String rationale) {
        return new ModerationVerdict(ModerationDecision.ESCALATE, 0.0, rationale, List.of());
    }
}
