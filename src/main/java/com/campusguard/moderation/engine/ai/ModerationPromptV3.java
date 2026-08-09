package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link ModerationPromptV2}, with the rationale written in the language of the
 * content.
 *
 * <p>The rationale is not internal telemetry. It is shown to whoever reviews the
 * case — in this deployment, inside the De Android app, which is bilingual — and
 * a reviewer reading a Chinese thread was being handed an English explanation of
 * it. Everything the app itself renders follows the user's language; this was
 * the one string on the screen that could not, because it is written by the
 * model rather than by the app, and no amount of client-side work can translate
 * a sentence that was generated in the wrong language.
 *
 * <p><b>The judgement wording is copied from v2 without a single edit.</b> Only
 * two things are added, both confined to how the answer is written down: a line
 * in the output contract fixing the rationale's language, and nothing else.
 * That is deliberate. v2's numbers on the evaluation set — macro-F1 0.924,
 * ESCALATE recall 0.778 — describe that exact wording, and a version that also
 * reworded the instructions would leave any difference in score unattributable.
 * With the classification text held identical, a gap between v2 and v3 is
 * evidence about the language instruction and nothing else.
 *
 * <p>What this version has not yet been shown to do: the evaluation set is
 * English, so running it here measures whether asking for a language-matched
 * rationale costs anything on English content — the honest question for a change
 * that is meant to be free. It cannot say whether the Chinese rationales are any
 * good, because there is no labelled Chinese set to say it with. Both engines
 * are registered, so {@code EvaluationRunner} scores {@code <model>/v2} and
 * {@code <model>/v3} side by side on the same samples; treat a difference within
 * noise on 192 samples as what it is.
 *
 * <p>v2 remains the configured engine. Switching live traffic to this one is a
 * {@code MODERATION_ENGINE} change, and should follow the run rather than
 * precede it.
 */
@Component
public class ModerationPromptV3 implements ModerationPrompt {

    public static final String VERSION = "v3";

    private final RuleProvider ruleProvider;

    public ModerationPromptV3(RuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String system() {
        return """
                You are the first pass over reports on a university student forum. \
                You never remove anything. Your answer decides what a queue of \
                human moderators sees, so choose it by what should happen next, \
                not by how bad the text is.

                  ALLOW    - this can be closed with no action and nobody needs to \
                read it.
                  REMOVE   - a rule is broken. A moderator will confirm and act.
                  ESCALATE - a person has to read this before anything is decided.

                ALLOW is a decision that nobody looks at this again. Only choose it \
                when you would be comfortable with that.

                Answer ESCALATE when the text is clear but the judgement is not \
                yours to make. That includes:

                  - Someone asking what the rules are, whether something is \
                allowed, or how to report something. Asking is not breaking a rule, \
                and the answer still has to come from a person.
                  - Someone reporting or quoting what another person said to them. \
                The quoted words may be severe. The person repeating them is not the \
                one who said them, and the original is what needs attention.
                  - Any mention of self-harm, dying or not wanting to go on, \
                including as exaggeration or a joke about coursework. You are not \
                the one who gets to decide it was only an idiom.
                  - Complaints about a named or identifiable person, or about staff \
                conduct, where whether it crossed a line depends on what actually \
                happened.
                  - Anything where the right answer turns on context you cannot \
                see: who these people are, what was said earlier, whether consent \
                exists.

                Do not use ESCALATE to avoid committing. Content that plainly \
                breaks a rule is REMOVE even when it is mild, and ordinary campus \
                content - study questions, selling a ticket, complaining about a \
                building, notes, events, lost property - is ALLOW. If you find \
                yourself escalating most of what you see, you are using it wrongly.

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

                decision is one of those three words and nothing else - never a \
                rule code. ruleCodes is a separate field listing only codes from \
                the rules above, and is empty for ALLOW. confidence is how sure you \
                are of the decision, not how severe the content is.

                Write rationale in the same language as the content you were \
                given: Chinese content gets a Chinese rationale, English content \
                gets an English one. A moderator reads this field, and reading a \
                thread in one language and its explanation in another is what this \
                instruction exists to prevent. Mixed-language content takes the \
                language most of it is in. This applies to rationale only - \
                decision and ruleCodes stay exactly as specified above, in English, \
                because they are values rather than prose.
                """
                .formatted(renderRules(ruleProvider.activeRules()));
    }

    @Override
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
