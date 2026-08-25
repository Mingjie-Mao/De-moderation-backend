package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the model said into a verdict, or refuses.
 *
 * <p>Every field is checked rather than trusted. A schema-constrained response
 * still arrives over a network from a system that was asked nicely, and the
 * failure that matters is not a mangled response, which is obvious, but a
 * well-formed one carrying a confidence of 1.7 or a rule code that does not
 * exist. Those pass a JSON parse and would land in the database as facts.
 *
 * <p>Rejection messages are written to be readable by the model, because they are
 * fed straight back as the correction on the retry.
 */
@Component
public class VerdictParser {

    private final ObjectMapper objectMapper;
    private final RuleProvider ruleProvider;

    public VerdictParser(ObjectMapper objectMapper, RuleProvider ruleProvider) {
        this.objectMapper = objectMapper;
        this.ruleProvider = ruleProvider;
    }

    public ModerationVerdict parse(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFences(raw));
        } catch (Exception ex) {
            throw invalid("The response was not valid JSON.");
        }

        if (root == null || !root.isObject()) {
            throw invalid("The response was not a JSON object.");
        }

        ModerationDecision decision = readDecision(root);
        double confidence = readConfidence(root);
        String rationale = readRationale(root);
        List<String> ruleCodes = readRuleCodes(root, decision);

        return new ModerationVerdict(decision, confidence, rationale, ruleCodes);
    }

    private ModerationDecision readDecision(JsonNode root) {
        JsonNode node = root.get("decision");
        if (node == null || !node.isTextual()) {
            throw invalid("Field 'decision' is missing or not a string.");
        }
        try {
            return ModerationDecision.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("Field 'decision' was '%s'. It must be exactly ALLOW, REMOVE or ESCALATE."
                    .formatted(node.asText()));
        }
    }

    private double readConfidence(JsonNode root) {
        JsonNode node = root.get("confidence");
        if (node == null || !node.isNumber()) {
            throw invalid("Field 'confidence' is missing or not a number.");
        }
        double value = node.asDouble();
        if (value < 0 || value > 1) {
            throw invalid("Field 'confidence' was %s. It must be between 0 and 1 inclusive.".formatted(value));
        }
        return value;
    }

    private String readRationale(JsonNode root) {
        JsonNode node = root.get("rationale");
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw invalid("Field 'rationale' is missing or empty. A moderator has to be able to read why.");
        }
        return node.asText().trim();
    }

    /**
     * Unknown codes are rejected outright rather than dropped. A model that
     * invents a rule has misunderstood the rule set, and silently discarding the
     * invention would hide that behind a verdict that still looks reasonable.
     */
    private List<String> readRuleCodes(JsonNode root, ModerationDecision decision) {
        JsonNode node = root.get("ruleCodes");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalid("Field 'ruleCodes' must be an array of rule codes.");
        }

        Set<String> known = ruleProvider.activeRules().stream()
                .map(ModerationRule::getCode)
                .collect(Collectors.toSet());

        List<String> codes = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw invalid("Field 'ruleCodes' must contain only strings.");
            }
            String code = element.asText().trim().toUpperCase(Locale.ROOT);
            if (!known.contains(code)) {
                throw invalid("Rule code '%s' does not exist. Valid codes are: %s."
                        .formatted(code, String.join(", ", known)));
            }
            codes.add(code);
        }

        if (decision == ModerationDecision.REMOVE && codes.isEmpty()) {
            throw invalid("A REMOVE decision must name at least one rule code that was broken.");
        }

        return codes;
    }

    /**
     * Models are prone to wrapping JSON in a markdown fence despite being asked
     * not to. Stripping it is not leniency about the schema, only about the
     * packaging, and every field inside is still checked.
     */
    private String stripFences(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private InvalidVerdictException invalid(String message) {
        return new InvalidVerdictException(message);
    }
}
