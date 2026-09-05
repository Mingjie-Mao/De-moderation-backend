package com.campusguard.moderation.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

/**
 * One thing the investigator is allowed to look up.
 *
 * <p>Every implementation reads and nothing else. That is not a convention to be
 * remembered: the investigation runs inside a read-only transaction, so a tool
 * that tried to write would fail at the database rather than succeed quietly.
 *
 * <p>The case under investigation arrives as an argument to {@link #run} rather
 * than through the model's own arguments. A model that could name the case would
 * be a model that could name a different one, and an assistant able to walk from
 * any case to any author's history is a different and much larger thing than the
 * one being built here.
 */
public interface InvestigationTool {

    ToolSpec spec();

    default String name() {
        return spec().name();
    }

    /**
     * @param caseId the case being investigated, supplied by the loop
     * @param arguments whatever the model sent, already parsed as JSON but not
     *     otherwise checked. Implementations validate and throw {@link
     *     IllegalArgumentException} with a message written for the model.
     */
    Output run(UUID caseId, JsonNode arguments);

    /** @param payload serialised to JSON by the registry, so it may be any Jackson-friendly value */
    record Output(Object payload, Set<UUID> disclosedCaseIds) {

        public static Output of(Object payload, Set<UUID> disclosedCaseIds) {
            return new Output(
                    payload, java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(disclosedCaseIds)));
        }

        /** For a tool whose answer names no cases, such as the text of a rule. */
        public static Output withoutCases(Object payload) {
            return new Output(payload, Set.of());
        }
    }
}
