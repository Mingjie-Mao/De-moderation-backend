package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.moderation.engine.ai.AiInvocation;
import com.campusguard.moderation.engine.ai.AiInvocationRepository;
import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one test that spends money.
 *
 * <p>Skipped unless {@code GEMINI_API_KEY} is in the environment, so it is inert
 * in CI and on a machine without credentials. Run it deliberately:
 *
 * <pre>
 * export $(grep -E '^(GEMINI_API_KEY|GEMINI_MODELS)=' .env | xargs)
 * mvn -Dtest=RealModelInvestigationSmokeTest test
 * </pre>
 *
 * <p>Everything else about the loop is pinned against a scripted model, because
 * a real one cannot be made to time out or fabricate a citation on demand. What
 * only a real one can answer is whether this provider and this model do tool
 * calling at all, whether the prompt gets a model to stop looking things up
 * before its budget runs out, and what an investigation actually costs. Those
 * are the three questions here.
 *
 * <p>The assertions are deliberately loose. A model's exact recommendation is not
 * a fact about this code and pinning it would produce a test that fails when the
 * provider updates a checkpoint. What is asserted is that the mechanism works:
 * tools were called, the answer held up to checking, and the budget was enough.
 */
@TestPropertySource(
        properties = {
            "spring.ai.model.chat=google-genai",
            "campusguard.moderation.investigator.enabled=true",
            "campusguard.moderation.engine=keyword-v1"
        })
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class RealModelInvestigationSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private CaseInvestigator investigator;

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private PostRepository posts;

    @Autowired
    private AiInvocationRepository invocations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * A repeat offender, a first offence, and a case with precedent but no
     * history. Three shapes rather than three samples of one, because the useful
     * question is whether the assistant reaches different conclusions when the
     * evidence differs — a brief that recommends the same thing regardless would
     * pass a one-case test and be worthless.
     */
    @Test
    void investigatesThreeRealCasesAndReportsWhatItCost() {
        List<Outcome> outcomes = new ArrayList<>();

        outcomes.add(run("repeat offender", repeatOffender()));
        outcomes.add(run("first offence", firstOffence()));
        outcomes.add(run("precedent, no history", precedentOnly()));

        report(outcomes);

        // The mechanism, not the judgement. If tool calling did not work at all,
        // every case would come back in one step with an ungrounded opinion.
        assertThat(outcomes).allSatisfy(outcome ->
                assertThat(outcome.brief()).isNotInstanceOf(InvestigationBrief.Partial.class));

        assertThat(outcomes.stream().filter(Outcome::converged).count())
                .as("at least two of three should reach a usable brief")
                .isGreaterThanOrEqualTo(2);

        double averageSteps = outcomes.stream().mapToInt(Outcome::steps).average().orElse(0);
        assertThat(averageSteps)
                .as("a model that always hits the budget is wandering, not converging")
                .isLessThanOrEqualTo(4.0);
    }

    private Outcome run(String label, UUID caseId) {
        InvestigationBrief brief = investigator.investigate(caseId);

        List<AiInvocation> rows = invocations.findAll().stream()
                .filter(row -> row.getCaseId().equals(caseId))
                .toList();

        int promptTokens = rows.stream()
                .map(AiInvocation::getPromptTokens)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        return new Outcome(label, brief, rows.size(), promptTokens, rows);
    }

    /**
     * Printed rather than asserted. These are the numbers the decision to keep or
     * drop this feature rests on, and they are measurements, not requirements —
     * writing them as assertions would turn "it got slightly more expensive" into
     * a broken build.
     */
    private void report(List<Outcome> outcomes) {
        StringBuilder out = new StringBuilder("\n=== Real-model investigation smoke run ===\n");

        for (Outcome outcome : outcomes) {
            out.append("%-24s %-14s steps=%d promptTokens=%d%n".formatted(
                    outcome.label(),
                    outcome.brief().getClass().getSimpleName(),
                    outcome.steps(),
                    outcome.promptTokens()));

            outcome.rows().forEach(row -> out.append("      call %d  %-16s %s%n".formatted(
                    row.getAttempt(),
                    row.getStatus(),
                    row.getStatus() == InvocationStatus.SUCCESS ? "" : String.valueOf(row.getError()))));

            out.append("      summary: ").append(outcome.brief().summary()).append('\n');

            if (outcome.brief() instanceof InvestigationBrief.Complete complete) {
                out.append("      recommends %s at %.2f, citing %d case(s)%n".formatted(
                        complete.recommendation(), complete.confidence(), complete.citedCaseIds().size()));
                out.append("      against:  ").append(complete.counterEvidence()).append('\n');
            }
        }

        out.append("average steps: %.1f, average prompt tokens: %.0f%n".formatted(
                outcomes.stream().mapToInt(Outcome::steps).average().orElse(0),
                outcomes.stream().mapToInt(Outcome::promptTokens).average().orElse(0)));

        // Printed, not asserted. Three cases chosen to have different evidence
        // should not all get the same answer: an assistant that recommends the
        // same thing for a fourth offence as for a first has gathered evidence
        // without using it, which is a prompt failure invisible in any single
        // case. Pinning it would mean asserting a model's judgement, which is
        // not a fact about this code; reporting it puts the number in front of
        // whoever next edits the wording.
        List<String> recommendations = outcomes.stream()
                .map(outcome -> outcome.brief() instanceof InvestigationBrief.Complete complete
                        ? complete.recommendation().name()
                        : "-")
                .toList();

        out.append("recommendations: %s (%d distinct)%n".formatted(
                String.join(", ", recommendations), Set.copyOf(recommendations).size()));

        System.out.println(out);
    }

    private record Outcome(
            String label, InvestigationBrief brief, int steps, int promptTokens, List<AiInvocation> rows) {

        boolean converged() {
            return brief instanceof InvestigationBrief.Complete;
        }
    }

    // --- the three cases -----------------------------------------------------

    /** Two prior hides under the same rule, then a third, milder comment. */
    private UUID repeatOffender() {
        User author = newUser();
        resolvedCase(author, "You are an idiot and your code is garbage.", "ABUSE", FinalAction.HIDE);
        resolvedCase(author, "Get out of this forum, you are worthless here.", "ABUSE", FinalAction.HIDE);

        return awaitingReviewCase(
                author,
                "You contribute nothing to this group and everyone in the tutorial knows it.",
                "ABUSE");
    }

    private UUID firstOffence() {
        return awaitingReviewCase(
                newUser(), "Keep crying about the marks, it is the only thing you are good at.", "ABUSE");
    }

    /** Nothing on this author, but other reviewers have handled this rule before. */
    private UUID precedentOnly() {
        resolvedCase(newUser(), "加微信 代刷 兼职日结，有意私聊。", "SPAM", FinalAction.HIDE);
        resolvedCase(newUser(), "Free money, click here, limited offer.", "SPAM", FinalAction.HIDE);

        return awaitingReviewCase(newUser(), "Selling last year's lecture notes, DM me, cheap.", "SPAM");
    }

    private UUID awaitingReviewCase(User author, String body, String ruleCode) {
        return caseFor(author, body, ruleCode, null);
    }

    private UUID resolvedCase(User author, String body, String ruleCode, FinalAction action) {
        return caseFor(author, body, ruleCode, action);
    }

    private UUID caseFor(User author, String body, String ruleCode, FinalAction action) {
        User admin = newAdmin();

        return new TransactionTemplate(transactionManager).execute(status -> {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "Tutorial group", body));

            cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
            UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "keyword-v1",
                    new ModerationVerdict(
                            ModerationDecision.REMOVE,
                            0.8,
                            "Matched a configured term for " + ruleCode + ".",
                            List.of(ruleCode)));

            if (action != null) {
                moderationCase.resolve(admin, action);
            }

            return cases.saveAndFlush(moderationCase).getId();
        });
    }
}
