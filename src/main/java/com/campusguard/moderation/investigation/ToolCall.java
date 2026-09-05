package com.campusguard.moderation.investigation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One request from the model to run a tool.
 *
 * <p>Untrusted in both fields. The name may not exist and the arguments may be
 * any JSON at all, including the wrong types or nothing; both are the model's
 * output, not a caller's input. Nothing here is validated on construction,
 * because the registry is where a bad call has to become an answer the model can
 * read rather than an exception that ends the investigation.
 *
 * @param id the provider's handle for this call, quoted back when the result is
 *     returned so a model that asked for several at once can tell them apart
 */
public record ToolCall(String id, String name, JsonNode arguments) {
}
