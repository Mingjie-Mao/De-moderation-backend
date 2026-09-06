package com.campusguard.moderation.investigation;

import com.campusguard.moderation.admin.ModerationCaseDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link InvestigationPromptV3}, with the two lookups that always matter handed
 * over rather than left to the model.
 *
 * <p>v3 was run twice over identical cases and disagreed with itself. A repeat
 * offender came back BAN one run and HIDE the next; a report that looked
 * mistaken came back NONE one run and HIDE the next. Both differences tracked
 * exactly one thing: whether the model had chosen to call
 * {@code similarResolvedCases} that turn. Temperature is zero, but a tool choice
 * is a fork in the conversation and everything after it diverges.
 *
 * <p>That variance was invisible for five runs because step counts looked the
 * same. It only showed up once the smoke report printed which tools were called.
 *
 * <p>An assistant that recommends a ban one minute and a takedown the next is
 * worse than none: two reviewers comparing notes cannot tell whether they
 * disagree about the case or were shown different evidence. So the author's
 * record and the precedent for the flagged rules are fetched before the first
 * turn. They are what decides most cases, they are worth having every time, and
 * a decision made the same way every time is one the model cannot fork on.
 *
 * <p>It also costs a turn less. The model opens with the evidence already in
 * hand, so writing the brief immediately becomes the ordinary path rather than
 * the second step.
 *
 * <p>What is given up is real and small: the model no longer decides whether
 * those two are worth fetching. On a case where they are not, the tokens are
 * spent anyway. That is the price of an assistant two people can compare notes
 * about.
 */
@Component
public class InvestigationPromptV4 implements InvestigationPrompt {

    public static final String VERSION = "inv-v4";

    /**
     * Enough precedent to see a pattern without burying the author's own record.
     *
     * <p>A case flagged under more codes than this is unusual, and the two most
     * relevant are the ones the engine listed first.
     */
    private static final int MAX_PREFETCHED_RULES = 2;

    private final InvestigationPromptV1 v1;

    public InvestigationPromptV4(InvestigationPromptV1 v1) {
        this.v1 = v1;
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * The author's record, and precedent for each rule this case was flagged
     * under.
     *
     * <p>Synthesised as calls rather than fetched directly so they go through the
     * registry like any other lookup — same whitelist, same read-only
     * transaction, same accounting of which cases were disclosed and may
     * therefore be cited. A second path into the tools would have none of that.
     *
     * <p>A case with no rule codes gets the history alone. That happens when the
     * engine escalated without naming a rule, which is exactly the case where the
     * author's record is the only evidence there is.
     */
    @Override
    public List<ToolCall> prefetch(ModerationCaseDetail detail) {
        List<ToolCall> calls = new ArrayList<>();
        calls.add(call("authorHistory", null));

        detail.moderationCase().ruleCodes().stream()
                .limit(MAX_PREFETCHED_RULES)
                .forEach(code -> calls.add(callWithRuleCode(code)));

        return List.copyOf(calls);
    }

    /** Built as a node rather than parsed from a string: there is no text here to be wrong about. */
    private ToolCall callWithRuleCode(String ruleCode) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("ruleCode", ruleCode);
        return call("similarResolvedCases", arguments);
    }

    private ToolCall call(String name, JsonNode arguments) {
        return new ToolCall("prefetch_" + UUID.randomUUID().toString().substring(0, 8), name, arguments);
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

                Two lookups have already been made for you, and their results are \
                below: this author's own record, and what moderators did in past \
                cases under the rules this one was flagged under. Those two decide \
                most cases, so you are not asked whether to fetch them. Read them.

                Two more are available if you need them:

                  ruleText              - the wording and severity of one rule
                  caseDetail            - this case's full audit trail

                You may also call similarResolvedCases again for a rule this case \
                was not flagged under, or authorHistory again, though you already \
                have both for the obvious arguments and asking twice returns the \
                same thing.

                Call a tool only when its answer would change what should happen. \
                Every call costs the moderator time, and in most cases what is \
                already below is enough to write from. Writing your brief \
                immediately is the normal outcome, not a shortcut. You have at \
                most five further lookups; needing all five means you are not \
                converging.

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
                  "confidence": "SETTLED" | "LEANING" | "OPEN",
                  "counterEvidence": "the case against your own recommendation",
                  "citedCaseIds": ["<case id>", ...]
                }

                summary is what the tools showed you, not a re-judgement of the \
                content. Lead with the fact that matters most.

                recommendation is a suggestion. The moderator decides.

                confidence is one of three words, and it describes the evidence \
                rather than your state of mind:

                  SETTLED - the record points one way and being overruled would \
                surprise you.
                  LEANING - the record favours one outcome, but a moderator could \
                reasonably choose another.
                  OPEN    - two or more outcomes are equally defensible on what \
                you found.

                OPEN is the most useful of the three when it is true, because it \
                tells the moderator to read your counter-evidence instead of your \
                recommendation. It is not an admission of failure. SETTLED is for \
                a record that actually settles something, not for a case you feel \
                strongly about.

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

    /** v1's, delegated. Four versions of one case description would be four chances to drift. */
    @Override
    public String opening(ModerationCaseDetail detail) {
        return v1.opening(detail);
    }
}
