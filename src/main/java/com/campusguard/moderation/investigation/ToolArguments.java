package com.campusguard.moderation.investigation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reading the model's arguments without trusting them.
 *
 * <p>Every message here is addressed to the model, because that is where it goes:
 * a rejected call comes back as the tool's result, and the next thing the model
 * does is read it. "Field 'ruleCode' is required" gets a corrected call; a stack
 * trace gets a guess.
 */
final class ToolArguments {

    private ToolArguments() {
    }

    static String requiredString(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);

        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Field '%s' is required.".formatted(field));
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Field '%s' must be a string.".formatted(field));
        }

        String value = node.asText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Field '%s' must not be empty.".formatted(field));
        }

        return value;
    }
}
