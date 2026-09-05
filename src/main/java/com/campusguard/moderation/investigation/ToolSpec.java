package com.campusguard.moderation.investigation;

import java.util.List;
import java.util.Map;

/**
 * A tool as the model is told about it.
 *
 * <p>The schema is written by hand rather than derived from a Java type. What
 * the model needs is a description of what a parameter means, and a generated
 * schema carries only its shape — which is the half a model rarely gets wrong.
 *
 * @param name what the model puts in a call. Also the registry's key, so it is
 *     the one string that must not drift.
 * @param description read by the model when it decides whether to call this at
 *     all. Says when the tool is useful, not merely what it returns.
 * @param parameters JSON Schema for the arguments object.
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    /** The schema for a tool that takes nothing. */
    public static Map<String, Object> noArguments() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    /** The schema for the common case: one required string. */
    public static Map<String, Object> oneRequiredString(String field, String description) {
        return Map.of(
                "type", "object",
                "properties", Map.of(field, Map.of("type", "string", "description", description)),
                "required", List.of(field));
    }
}
