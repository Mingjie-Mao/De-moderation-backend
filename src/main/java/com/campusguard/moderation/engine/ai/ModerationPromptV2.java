package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.rule.ModerationRule;
import com.campusguard.moderation.rule.RuleProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The same task, asked as a different question.
 *
 * <p>{@link ModerationPromptV1} scored macro-F1 0.636 on the 192-sample set, and
 * its error was almost entirely one behaviour: REMOVE recall 0.970 against
 * ESCALATE recall 0.056. Thirty-three of the thirty-six samples that should reach
 * a person were answered ALLOW.
 *
 * <p>The rationales it wrote are consistent about the cause, and it is not that
 * the model misread anything. Asked whether a post breaks a rule, it answered
 * correctly: a student asking who to report harassment to is not harassing
 * anyone, and someone writing that a lecture made them want to die is using an
 * idiom. Both are right, and both are the wrong question. What the queue needs to
 * know is whether the matter can be closed without a person looking, and those
 * two questions come apart precisely on the cases a moderation queue exists for.
 *
 * <p>So this version defines the three answers by what happens next rather than
 * by what the text is, and names the situations where reading the text correctly
 * still is not enough. It keeps an explicit floor under REMOVE, because the
 * cheapest way to raise ESCALATE recall is to escalate everything, and a queue
 * nobody can keep up with protects nobody.
 *
 * <p><b>This wording was written after reading v1's mistakes on this dataset,
 * so its score on that same dataset is optimistic by an unknown amount.</b> The
 * comparison is still worth running — it says whether the diagnosis was right —
 * but it is a diagnosis confirmed on the data that produced it, not a held-out
 * result, and nothing here should be read as an estimate of live traffic. The
 * categories below are written as categories for that reason: fitting the
 * sentences in the file would measure nothing at all.
 *
 * <p>What the run said: macro-F1 0.924 against v1's 0.636, ESCALATE recall
 * 0.056 to 0.778, and REMOVE and ALLOW recall unchanged at 0.970 and 0.989. The
 * floor under REMOVE held, which was the thing most at risk. It cost three
 * samples in the other direction — one benign idiom and two clear insults about
 * an identifiable person, all sent for review instead of being decided — and
 * roughly twice the prompt tokens per sample, 655 against 336, which is what a
 * prompt this long costs on every call forever.
 *
 * <p>Two things the run found that this version does not fix, left for the next
 * one rather than patched here, because editing this text would leave the
 * numbers above describing code that no longer exists:
 *
 * <ul>
 *   <li>Eleven of 192 answers came back with a rule code of {@code "ABUSE
 *       (HIGH)"} — the severity copied out of how the rules are rendered below.
 *       The validator rejected each one and the corrective retry fixed it, so
 *       the run shows no errors, but it is eleven extra calls and eleven extra
 *       seconds of latency bought by a formatting ambiguity. v1 rendered rules
 *       identically and did this once; asking harder for codes "from the rules
 *       above" appears to have made the model read the line more literally.
 *   <li>The instruction to escalate any mention of dying fires on "my soul
 *       leaves my body". Narrowing it is the obvious next change and the obvious
 *       next risk.
 * </ul>
 *
 * <p>{@link ModerationPromptV3} is the configured engine as of 13 September 2026.
 * It copies the wording above without an edit and changes only the language the
 * rationale is written in, so both entries in that list are still open and this
 * version's numbers still describe the text in this file. Nothing here is stale;
 * it is simply no longer the one judging live traffic.
 */
@Component
public class ModerationPromptV2 implements ModerationPrompt {

    public static final String VERSION = "v2";

    private final RuleProvider ruleProvider;

    public ModerationPromptV2(RuleProvider ruleProvider) {
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

                Attachment: %s
                """
                .formatted(title, request.body(), request.media().isEmpty()
                        ? "none" : "one image is attached; inspect its visible content too");
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
