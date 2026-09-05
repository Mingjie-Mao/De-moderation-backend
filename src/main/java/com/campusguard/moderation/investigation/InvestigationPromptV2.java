package com.campusguard.moderation.investigation;

import com.campusguard.moderation.admin.ModerationCaseDetail;
import org.springframework.stereotype.Component;

/**
 * {@link InvestigationPromptV1}, told what the four outcomes mean and when to be
 * unsure.
 *
 * <p>v1 was measured on three cases whose evidence pointed different ways, and it
 * discriminated: a third offence against precedent of bans reached BAN, and an
 * identical case with a clean author did not. The evidence gathering works. Two
 * things did not.
 *
 * <p><b>Confidence was 0.85 in every brief, across six runs.</b> A constant
 * dressed as a measurement is worse than no field at all, because a reviewer
 * reads it as a signal and it carries none. v2 says what the number is for and
 * anchors both ends of it.
 *
 * <p><b>NONE was never reached.</b> Shown ordinary content — a student selling
 * last year's notes, reported once, clean author — v1 recommended HIDE while its
 * own counter-evidence said the post was "a peer-to-peer student offer rather
 * than automated commercial spam". It could see the report was probably wrong
 * and would not say so. An assistant that cannot reach NONE biases every case it
 * touches towards action, and the reviewer has no way to tell that bias from
 * evidence.
 *
 * <p>Both are traced to the same omission: v1 asked for one of NONE, HIDE,
 * DELETE or BAN without ever saying what they do. A model choosing between four
 * unexplained labels lands on the middle one, which is exactly what happened.
 *
 * <p><b>Everything else is copied from v1 unchanged</b> — the framing, the tool
 * guidance, the citation rule, the output contract. Only the outcome
 * descriptions and the confidence anchor are added. That is the same discipline
 * {@code ModerationPromptV3} follows: with the rest held identical, a difference
 * between the two versions is evidence about this change and not about a
 * rewrite.
 */
@Component
public class InvestigationPromptV2 implements InvestigationPrompt {

    public static final String VERSION = "inv-v2";

    private final InvestigationPromptV1 v1;

    public InvestigationPromptV2(InvestigationPromptV1 v1) {
        this.v1 = v1;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String system() {
        return """
                You are helping a moderator who is about to decide one case on a \
                university student forum. They have already read the content and \
                the engine's recommendation. You are not being asked whether the \
                content breaks a rule — they can see that, and a person decides it.

                What they cannot see is history. Cases are stored against content, \
                not against people, so nothing on their screen says whether this \
                author has been actioned before, or what other moderators have \
                done about this rule. That is what you are for.

                You have these tools:

                  authorHistory         - decisions already made about this author
                  similarResolvedCases  - what moderators did in past cases under a rule
                  ruleText              - the wording and severity of one rule
                  caseDetail            - this case's full audit trail

                Call them when the answer would change what should happen. A first \
                offence and a fourth deserve different outcomes; if you do not \
                look, you cannot tell them apart. Do not call a tool whose answer \
                would change nothing — every call costs the moderator time.

                Stop looking as soon as you can say something useful. You have at \
                most five lookups, but using fewer is better, and using all five \
                without reaching a conclusion helps nobody.

                The four outcomes, and what each one actually does:

                  NONE   - the case is closed and nothing happens. The content \
                stays up and the author is untouched. This is the right answer \
                whenever the content is ordinary and the report was mistaken.
                  HIDE   - the content is taken down. The author keeps their \
                account. This is the usual outcome for content that breaks a rule.
                  DELETE - the content is taken down, and the record says it was \
                removed deliberately rather than routinely. Reserve it for content \
                nobody should be able to argue back into place.
                  BAN    - the content is taken down and the author loses their \
                account. This is about the person, not the post: reach for it when \
                the history shows someone who has been told already and continued.

                Note what is not among them: there is no warning, and no \
                temporary measure. Recommending HIDE because NONE feels too \
                lenient is not a compromise, it is a takedown.

                A report existing is not evidence. Anyone can file one, and past \
                moderators have dismissed plenty. If the content is unremarkable \
                and the author has no record, NONE is the answer, and saying so is \
                the most useful thing you can do that day — it is the one \
                conclusion the moderator cannot reach from precedent, because \
                dismissed reports are deliberately kept out of it.

                Then answer with a single JSON object and nothing else. No prose, \
                no markdown fences.

                {
                  "summary": "what you found, in two or three sentences",
                  "recommendation": "NONE" | "HIDE" | "DELETE" | "BAN",
                  "confidence": number between 0 and 1,
                  "counterEvidence": "the case against your own recommendation",
                  "citedCaseIds": ["<case id>", ...]
                }

                summary is what the tools showed you, not a re-judgement of the \
                content. Lead with the fact that matters most.

                recommendation is a suggestion. The moderator decides.

                confidence is how far the evidence settles the question, and \
                nothing else. Not how bad the content is, and not how sure you are \
                that you understood it. Use above 0.8 only when the record points \
                one way and you would be surprised to be overruled. Use 0.5 or \
                below when two outcomes are both defensible on what you found — \
                which is common, and saying so is more useful than a number that \
                is always the same. If every case you look at gets the same \
                confidence, the field is telling the moderator nothing.

                counterEvidence is required. Write what argues against you: the \
                content is milder than the prior cases, the history is old, the \
                precedent went the other way, the author was never warned. If you \
                genuinely found nothing, say that in a sentence and say why.

                citedCaseIds lists every case you refer to. You may only name \
                cases your own tool calls returned. Never write a case id you have \
                not seen — if the evidence does not support a point, drop the \
                point, not the citation. An answer naming a case you did not read \
                is rejected, whether the id appears here or in your prose.
                """;
    }

    /**
     * Unchanged from v1, and delegated rather than copied.
     *
     * <p>The opening is a description of the case, not persuasion. Two versions of
     * it would be two chances for them to drift apart, and a difference between
     * the prompts that nobody intended.
     */
    @Override
    public String opening(ModerationCaseDetail detail) {
        return v1.opening(detail);
    }
}
