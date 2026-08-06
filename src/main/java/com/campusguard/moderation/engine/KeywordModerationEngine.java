package com.campusguard.moderation.engine;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import com.campusguard.moderation.rule.RuleSeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Term matching against the configured rules.
 *
 * <p>This exists for two reasons beyond being the first thing that worked. It is
 * the fallback for when a model-backed engine is unreachable, so the queue keeps
 * moving when an external dependency does not. And it is the baseline the
 * evaluation measures against, which is what turns "we added a language model"
 * into a number: without a floor to compare to, a model's score says nothing
 * about whether the model was needed.
 *
 * <p>Its weaknesses are the point. It cannot see anything outside its term list,
 * it has no notion of context, and it will flag a quotation of an insult as
 * readily as the insult. Those are exactly the failure modes the evaluation
 * should surface.
 */
@Component
public class KeywordModerationEngine implements ModerationEngine {

    public static final String NAME = "keyword-v1";

    private final RuleProvider ruleProvider;

    public KeywordModerationEngine(RuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModerationVerdict evaluate(ModerationRequest request) {
        String haystack = request.fullText().toLowerCase(Locale.ROOT);

        List<String> matchedCodes = new ArrayList<>();
        RuleSeverity worst = null;

        for (ModerationRule rule : ruleProvider.activeRules()) {
            if (matches(haystack, rule)) {
                matchedCodes.add(rule.getCode());
                if (worst == null || rule.getSeverity().compareTo(worst) > 0) {
                    worst = rule.getSeverity();
                }
            }
        }

        if (worst == null) {
            // Not "this is fine" but "nothing in my term list appeared", which is
            // a much weaker claim. The confidence says so rather than pretending.
            return ModerationVerdict.allow(
                    0.5, "No configured term matched. This engine cannot detect wording it has not been given.");
        }

        return switch (worst) {
            case HIGH -> new ModerationVerdict(
                    ModerationDecision.REMOVE, 0.9, describe(matchedCodes, "a high-severity term"), matchedCodes);
            case MEDIUM -> new ModerationVerdict(
                    ModerationDecision.REMOVE, 0.7, describe(matchedCodes, "a medium-severity term"), matchedCodes);
            // Advertising wording appears in legitimate posts often enough that
            // removing on a term alone would be worse than asking a person.
            case LOW -> new ModerationVerdict(
                    ModerationDecision.ESCALATE, 0.5, describe(matchedCodes, "a low-severity term"), matchedCodes);
        };
    }

    private boolean matches(String haystack, ModerationRule rule) {
        return rule.getTerms().stream()
                .map(term -> term.toLowerCase(Locale.ROOT))
                .anyMatch(term -> containsTerm(haystack, term));
    }

    /**
     * Word boundaries are applied to terms that are entirely ASCII and plain
     * substring matching to everything else.
     *
     * <p>Chinese is written without spaces, so a boundary anchor would never fire
     * on a Chinese term. English without one matches inside longer words, which
     * is how a rule aimed at an insult ends up flagging "classic". Neither rule
     * works for both scripts, so which one applies follows the term.
     */
    private boolean containsTerm(String haystack, String term) {
        if (term.isBlank()) {
            return false;
        }
        if (!isAscii(term)) {
            return haystack.contains(term);
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(term) + "\\b");
        Matcher matcher = pattern.matcher(haystack);
        return matcher.find();
    }

    private boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < 128);
    }

    private String describe(List<String> codes, String what) {
        return "Matched " + what + " from " + String.join(", ", codes) + ".";
    }
}
