package com.campusguard.moderation.investigation;

import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a rule actually says, and how severe it is held to be.
 *
 * <p>The matching terms are deliberately not returned. They are the keyword
 * engine's business, and a model shown a list of banned words tends to start
 * judging by whether one appears — which is the failure the language model was
 * introduced to avoid.
 */
@Component
public class RuleTextTool implements InvestigationTool {

    private final RuleProvider rules;

    public RuleTextTool(RuleProvider rules) {
        this.rules = rules;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "ruleText",
                "The wording and severity of one moderation rule. Call this when the right outcome "
                        + "depends on what the rule actually says rather than on what happened.",
                ToolSpec.oneRequiredString("code", "The rule code, for example ABUSE."));
    }

    @Override
    public Output run(UUID caseId, JsonNode arguments) {
        String code = ToolArguments.requiredString(arguments, "code").toUpperCase(java.util.Locale.ROOT);

        List<ModerationRule> active = rules.activeRules();

        return active.stream()
                .filter(rule -> rule.getCode().equalsIgnoreCase(code))
                .findFirst()
                .map(rule -> Output.withoutCases(
                        new RuleView(rule.getCode(), rule.getTitle(), rule.getBody(), rule.getSeverity().name())))
                .orElseThrow(() -> new IllegalArgumentException(
                        "There is no active rule with code '%s'. The active codes are: %s."
                                .formatted(code, active.stream().map(ModerationRule::getCode).sorted().toList())));
    }

    record RuleView(String code, String title, String body, String severity) {
    }
}
