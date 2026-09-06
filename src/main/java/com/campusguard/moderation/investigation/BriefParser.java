package com.campusguard.moderation.investigation;

import com.campusguard.moderation.FinalAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns what the model wrote into a brief, or refuses.
 *
 * <p>The dangerous failure is not a mangled answer, which is obvious the moment
 * anyone looks at it. It is a well-formed, fluent, confident brief that says the
 * author has four prior offences when the history returned three — a sentence a
 * reviewer would act on, arriving in exactly the shape of a sentence that is
 * true. Every case the brief names is therefore checked against the set the
 * tools actually disclosed, and a brief naming anything else is rejected.
 *
 * <p>The check covers the prose as well as the citation list, because a
 * fabrication in the summary is read by the reviewer whether or not it was also
 * declared. Anything shaped like a case id anywhere in the text has to have been
 * seen.
 */
@Component
public class BriefParser {

    private static final Pattern UUID_SHAPED = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private final ObjectMapper objectMapper;

    public BriefParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param disclosed every case id the tools returned during this investigation.
     *     The brief may name these and nothing else.
     */
    public InvestigationBrief.Complete parse(String raw, Set<UUID> disclosed) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFences(raw));
        } catch (Exception ex) {
            throw new InvalidBriefException("Your answer was not valid JSON.");
        }

        if (root == null || !root.isObject()) {
            throw new InvalidBriefException("Your answer was not a JSON object.");
        }

        String summary = readText(root, "summary");
        String counterEvidence = readText(root, "counterEvidence");
        FinalAction recommendation = readRecommendation(root);
        EvidenceStrength strength = readEvidenceStrength(root);
        List<UUID> cited = readCitations(root);

        // The prose is checked too. A reviewer reads the summary; whether an id in
        // it was also declared below is not something they should have to notice.
        Set<UUID> named = new LinkedHashSet<>(cited);
        named.addAll(idsMentionedIn(summary));
        named.addAll(idsMentionedIn(counterEvidence));

        for (UUID id : named) {
            if (!disclosed.contains(id)) {
                throw new InvalidBriefException(
                        ("You referred to case %s, which none of your tool calls returned. Use only cases "
                                + "the tools showed you, and if the evidence does not support a point, drop "
                                + "the point rather than the citation.")
                                .formatted(id));
            }
        }

        return new InvestigationBrief.Complete(
                summary, recommendation, strength, counterEvidence, List.copyOf(named));
    }

    private String readText(JsonNode root, String field) {
        JsonNode node = root.get(field);

        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            // counterEvidence is required, not merely expected. A model allowed to
            // omit it omits it exactly when it is most confident, which is when a
            // reviewer most needs to see what argues the other way.
            throw new InvalidBriefException(
                    "Field '%s' is missing or empty. It is required.".formatted(field));
        }

        return node.asText().trim();
    }

    private FinalAction readRecommendation(JsonNode root) {
        JsonNode node = root.get("recommendation");

        if (node == null || !node.isTextual()) {
            throw new InvalidBriefException("Field 'recommendation' is missing or not a string.");
        }
        try {
            return FinalAction.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidBriefException(
                    "Field 'recommendation' was '%s'. It must be exactly one of NONE, HIDE, DELETE or BAN."
                            .formatted(node.asText()));
        }
    }

    /**
     * Accepts a band or, from the earlier prompt versions, a number.
     *
     * <p>Both shapes rather than one, because the versions that ask for a number
     * stay registered so that going back to them is a configuration change. A
     * parser that only understood the current contract would make that switch a
     * lie: the old wording would run and every brief would be rejected.
     */
    private EvidenceStrength readEvidenceStrength(JsonNode root) {
        JsonNode node = root.get("confidence");

        if (node == null || node.isNull()) {
            throw new InvalidBriefException("Field 'confidence' is missing.");
        }

        if (node.isNumber()) {
            double value = node.asDouble();
            if (value < 0 || value > 1) {
                throw new InvalidBriefException(
                        "Field 'confidence' was %s. It must be between 0 and 1.".formatted(node.asText()));
            }
            return EvidenceStrength.ofNumber(value);
        }

        if (!node.isTextual()) {
            throw new InvalidBriefException("Field 'confidence' must be one of SETTLED, LEANING or OPEN.");
        }

        try {
            return EvidenceStrength.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidBriefException(
                    "Field 'confidence' was '%s'. It must be exactly SETTLED, LEANING or OPEN."
                            .formatted(node.asText()));
        }
    }

    private List<UUID> readCitations(JsonNode root) {
        JsonNode node = root.get("citedCaseIds");

        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidBriefException("Field 'citedCaseIds' must be an array of case ids.");
        }

        List<UUID> ids = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw new InvalidBriefException("Every entry in 'citedCaseIds' must be a string.");
            }
            try {
                ids.add(UUID.fromString(element.asText().trim()));
            } catch (IllegalArgumentException ex) {
                throw new InvalidBriefException(
                        "'%s' in 'citedCaseIds' is not a case id.".formatted(element.asText()));
            }
        }

        return ids;
    }

    private Set<UUID> idsMentionedIn(String text) {
        Set<UUID> found = new LinkedHashSet<>();
        Matcher matcher = UUID_SHAPED.matcher(text);

        while (matcher.find()) {
            found.add(UUID.fromString(matcher.group()));
        }

        return found;
    }

    /** Models fence JSON in markdown when they are being helpful. Tolerated rather than retried over. */
    private String stripFences(String raw) {
        String trimmed = raw == null ? "" : raw.trim();

        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                return trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }

        return trimmed;
    }
}
