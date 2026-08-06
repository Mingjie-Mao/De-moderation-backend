package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The prompt, with a version number that is recorded on every call.
 *
 * <p>Versioning is what makes prompt changes measurable instead of anecdotal.
 * An unversioned prompt that someone improved last week leaves no way to say
 * whether this week's numbers moved because of the change or because of the
 * traffic, and no way to compare the two on the same data.
 *
 * <p>This first version states the task and the rules plainly and stops there.
 * It deliberately does not yet carry guidance about quoted abuse, hyperbole or
 * self-directed insults, which is what the keyword baseline showed to be the
 * hard cases. Writing those instructions before measuring which of them the
 * model actually needs would be guessing, and the comparison against a later
 * version is only meaningful if the first one was an honest attempt rather than
 * a straw man.
 */
@Component
public class ModerationPromptV1 {

    public static final String VERSION = "v1";

    private final RuleProvider ruleProvider;

    public ModerationPromptV1(RuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    public String version() {
        return VERSION;
    }

    public String system() {
        return """
                You review posts and comments on a university student forum and \
                recommend what a human moderator should do. You do not take action \
                yourself; a person reads your answer and decides.

                Choose exactly one decision:
                  ALLOW    - nothing here breaks the rules.
                  REMOVE   - a rule is clearly broken.
                  ESCALATE - genuinely ambiguous, or it depends on context you \
                cannot see. Prefer this over guessing.

                The rules are:
                %s

                Answer with a single JSON object and nothing else. No prose, no \
                markdown fences.

                {
                  "decision": "ALLOW" | "REMOVE" | "ESCALATE",
                  "confidence": number between 0 and 1,
                  "rationale": "one or two sentences a moderator can read",
                  "ruleCodes": ["CODE", ...]
                }

                ruleCodes lists only codes from the rules above, and is empty for \
                ALLOW. confidence is how sure you are of the decision, not how \
                severe the content is.
                """
                .formatted(renderRules(ruleProvider.activeRules()));
    }

    public String user(ModerationRequest request) {
        String title = request.title() == null || request.title().isBlank() ? "(none)" : request.title();
        return """
                Title: %s

                Body:
                %s
                """
                .formatted(title, request.body());
    }

    private String renderRules(List<ModerationRule> rules) {
        if (rules.isEmpty()) {
            return "  (no rules are configured)";
        }
        return rules.stream()
                .map(rule -> "  %s (%s): %s".formatted(rule.getCode(), rule.getSeverity(), rule.getBody()))
                .collect(Collectors.joining("\n"));
    }
}
