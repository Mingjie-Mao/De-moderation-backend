package com.campusguard.moderation.investigation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What a tool call produced, on its way back into the conversation.
 *
 * <p>An error is a result rather than an exception. A model that asked for a
 * tool that does not exist, or passed a string where a UUID belongs, should be
 * told so and allowed to try something else; ending the investigation instead
 * would turn the model's most ordinary mistake into a failure the reviewer sees.
 *
 * @param disclosedCaseIds every case this result actually revealed. The union
 *     over an investigation is the set the brief is allowed to cite, which is
 *     what makes a fabricated citation detectable rather than merely unlikely.
 */
public record ToolResult(String callId, String name, String content, boolean error, Set<UUID> disclosedCaseIds) {

    public static ToolResult of(ToolCall call, String content, Set<UUID> disclosedCaseIds) {
        // Insertion order preserved rather than Set.copyOf, which does not. The
        // order these were found in is the order the brief ends up citing them,
        // and a reviewer reading "most recent first" should get that.
        return new ToolResult(
                call.id(),
                call.name(),
                content,
                false,
                Collections.unmodifiableSet(new LinkedHashSet<>(disclosedCaseIds)));
    }

    /**
     * Phrased for the model, which is the only reader. It is about to decide what
     * to do next, so the message says what was wrong and, where the registry
     * knows it, what would have been right.
     */
    public static ToolResult error(ToolCall call, String message) {
        return new ToolResult(call.id(), call.name(), message, true, Set.of());
    }
}
