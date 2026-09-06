package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.ConflictException;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.admin.ModerationCaseDetail;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.engine.ai.AiInvocationRepository;
import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.moderation.engine.ai.ModelCallException;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The loop, exercised against a model that does exactly what each test needs.
 *
 * <p>Nothing here touches a network or needs a credential. The behaviour worth
 * pinning is not what a model says — that is the prompt's business, and it
 * changes — but what happens around whatever it says: that the budget is a
 * budget, that a fabricated citation is caught, that a provider going quiet
 * produces a sentence rather than a stack trace.
 *
 * <p>These are the failures that would otherwise be found in production, because
 * each of them needs a model behaving badly in a specific way and no amount of
 * running the real thing reliably produces that on demand.
 */
class CaseInvestigatorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ToolRegistry tools;

    @Autowired
    private BriefParser parser;

    @Autowired
    private AiInvocationRecorder recorder;

    @Autowired
    private AiInvocationRepository invocations;

    @Autowired
    private AdminModerationService adminCases;

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private PostRepository posts;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void looksThingsUpAndThenWritesABrief() {
        User author = newUser();
        UUID priorCase = resolvedCase(author, "ABUSE");
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        ScriptedModel model = new ScriptedModel()
                .thenCalls("authorHistory")
                .thenCalls("similarResolvedCases", "{\"ruleCode\":\"ABUSE\"}")
                .thenAnswers(brief("BAN", 0.7, List.of(priorCase)));  // numeric, as v1 and v2 emit

        InvestigationBrief result = investigator(model).investigate(caseId);

        assertThat(result).isInstanceOf(InvestigationBrief.Complete.class);
        InvestigationBrief.Complete complete = (InvestigationBrief.Complete) result;
        assertThat(complete.recommendation()).isEqualTo(FinalAction.BAN);
        assertThat(complete.evidenceStrength()).isEqualTo(EvidenceStrength.LEANING);
        assertThat(complete.counterEvidence()).isNotBlank();
        assertThat(complete.citedCaseIds()).containsExactly(priorCase);

        // Three turns, three rows, all successful.
        assertThat(invocations.findAll().stream()
                        .filter(row -> row.getCaseId().equals(caseId))
                        .toList())
                .hasSize(3)
                .allSatisfy(row -> {
                    assertThat(row.getEngine()).isEqualTo("investigator/test-v1");
                    assertThat(row.getStatus()).isEqualTo(InvocationStatus.SUCCESS);
                });
        assertThat(model.calls()).isEqualTo(3);
    }

    /**
     * The budget is the only thing standing between a model that will not
     * converge and an unbounded bill. Asserted as an exact count, because "about
     * five" is not a budget.
     */
    @Test
    void stopsAtTheStepLimitWithoutThrowing() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");

        ScriptedModel neverStops = new ScriptedModel().alwaysCalls("caseDetail");

        InvestigationBrief result = investigator(neverStops).investigate(caseId);

        assertThat(result).isInstanceOf(InvestigationBrief.Inconclusive.class);
        assertThat(result.summary()).contains("did not reach a conclusion");
        assertThat(neverStops.calls()).isEqualTo(MAX_STEPS);
    }

    /**
     * The failure this whole design exists to catch. A brief that reads perfectly
     * and cites a case nobody looked up is the one a reviewer would act on.
     */
    @Test
    void refusesABriefThatCitesACaseItNeverRead() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");
        UUID neverRead = UUID.randomUUID();

        ScriptedModel fabricates = new ScriptedModel()
                .thenAnswers(brief("BAN", "SETTLED", List.of(neverRead)))
                .thenAnswers(brief("BAN", "SETTLED", List.of(neverRead)));

        InvestigationBrief result = investigator(fabricates).investigate(caseId);

        assertThat(result).isInstanceOf(InvestigationBrief.Inconclusive.class);
        assertThat(result.summary()).contains("could not produce a brief that held up");
        // Asked once, corrected once, then given up on.
        assertThat(fabricates.calls()).isEqualTo(2);
    }

    /**
     * The same check, but against a case id buried in the prose rather than
     * declared. A reviewer reads the summary; whether the id was also listed
     * below is not something they should have to verify.
     */
    @Test
    void catchesAFabricatedCaseIdInTheProse() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");
        UUID neverRead = UUID.randomUUID();

        String smuggled = json(
                "The author was already banned in case " + neverRead + ".", "BAN", "SETTLED", "None.", List.of());

        ScriptedModel model = new ScriptedModel().thenAnswers(smuggled).thenAnswers(smuggled);

        assertThat(investigator(model).investigate(caseId))
                .isInstanceOf(InvestigationBrief.Inconclusive.class);
    }

    /** Corrected once, and the second answer stands. */
    @Test
    void acceptsABriefThatWasFixedOnTheSecondAsking() {
        User author = newUser();
        UUID priorCase = resolvedCase(author, "ABUSE");
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        ScriptedModel model = new ScriptedModel()
                .thenCalls("authorHistory")
                .thenAnswers(brief("BAN", "SETTLED", List.of(UUID.randomUUID())))
                .thenAnswers(brief("HIDE", "LEANING", List.of(priorCase)));

        InvestigationBrief result = investigator(model).investigate(caseId);

        assertThat(result).isInstanceOf(InvestigationBrief.Complete.class);
        assertThat(((InvestigationBrief.Complete) result).recommendation()).isEqualTo(FinalAction.HIDE);
    }

    /**
     * A correction is not a lookup, and must not be charged to the lookup budget.
     * If it were, a model that wrote a bad brief on its last step would have the
     * budget silently taken from the evidence gathering of the next investigation
     * to reason about.
     */
    @Test
    void doesNotSpendTheStepBudgetOnCorrections() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");

        ScriptedModel model = new ScriptedModel()
                .thenAnswers("not json at all")
                .alwaysCalls("caseDetail");

        InvestigationBrief result = investigator(model).investigate(caseId);

        assertThat(result).isInstanceOf(InvestigationBrief.Inconclusive.class);
        // One rejected brief plus a full budget of lookups, not four lookups.
        assertThat(model.calls()).isEqualTo(MAX_STEPS + 1);
    }

    /**
     * A provider that stops answering is the reviewer's problem only insofar as
     * they get no help; it must not become an error on their screen. What was
     * read before it went quiet is still returned, labelled as incomplete.
     */
    @Test
    void returnsWhatItHadWhenTheProviderStopsAnswering() {
        User author = newUser();
        UUID priorCase = resolvedCase(author, "ABUSE");
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        ScriptedModel model = new ScriptedModel()
                .thenCalls("authorHistory")
                .thenThrows(new ModelCallException(
                        InvocationStatus.TIMEOUT, "The model did not answer within the budget."));

        InvestigationBrief result = investigator(model).investigate(caseId);

        assertThat(result).isInstanceOf(InvestigationBrief.Partial.class);
        assertThat(result.summary()).contains("did not answer in time");
        assertThat(result.citedCaseIds()).containsExactly(priorCase);

        // The failed turn is recorded as carefully as the successful one.
        assertThat(invocations.findAll().stream()
                        .filter(row -> row.getCaseId().equals(caseId))
                        .map(row -> row.getStatus())
                        .toList())
                .containsExactly(InvocationStatus.SUCCESS, InvocationStatus.TIMEOUT);
    }

    /**
     * A model naming a tool that does not exist gets told so and carries on. The
     * alternative — ending the investigation — turns the most ordinary thing a
     * model does wrong into a failure the reviewer sees.
     */
    @Test
    void carriesOnAfterAskingForAToolThatDoesNotExist() {
        User author = newUser();
        UUID priorCase = resolvedCase(author, "ABUSE");
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        ScriptedModel model = new ScriptedModel()
                .thenCalls("banUser", "{\"userId\":\"anyone\"}")
                .thenCalls("authorHistory")
                .thenAnswers(brief("HIDE", "OPEN", List.of(priorCase)));

        assertThat(investigator(model).investigate(caseId)).isInstanceOf(InvestigationBrief.Complete.class);
        assertThat(model.calls()).isEqualTo(3);
    }

    @Test
    void refusesToInvestigateACaseNobodyIsAboutToDecide() {
        UUID resolved = resolvedCase(newUser(), "ABUSE");

        assertThatThrownBy(() -> investigator(new ScriptedModel()).investigate(resolved))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("only be investigated while it is awaiting review");
    }

    /**
     * A reviewer may click Investigate twice — after new reports arrive, or a
     * colleague's note, or because the first brief was inconclusive.
     *
     * <p>This failed when written. The opening message of a second run is
     * byte-for-byte the opening of the first, so its recorded call collided with
     * the invocation table's uniqueness constraint and the reviewer got a 500.
     * The constraint is right for an engine, where the same question twice about
     * one case is a double charge; it is wrong for an investigation, and the run
     * now carries an identity of its own.
     */
    @Test
    void canBeAskedTwiceAboutTheSameCase() {
        User author = newUser();
        UUID priorCase = resolvedCase(author, "ABUSE");
        UUID caseId = awaitingReviewCase(author, "ABUSE");

        investigator(new ScriptedModel().thenAnswers(brief("HIDE", "OPEN", List.of()))).investigate(caseId);

        InvestigationBrief second = investigator(new ScriptedModel()
                        .thenCalls("authorHistory")
                        .thenAnswers(brief("BAN", "SETTLED", List.of(priorCase))))
                .investigate(caseId);

        assertThat(second).isInstanceOf(InvestigationBrief.Complete.class);
    }

    /**
     * Both shapes of the confidence field, because both are in use.
     *
     * <p>inv-v3 asks for a band; inv-v1 and inv-v2 ask for a number and stay
     * registered so that going back to them is a configuration change. A parser
     * that understood only the current contract would make that switch a lie —
     * the old wording would run and every brief it produced would be rejected.
     */
    @Test
    void readsBothTheBandAndTheNumberTheOlderWordingsProduce() {
        UUID settled = awaitingReviewCase(newUser(), "ABUSE");
        UUID leaning = awaitingReviewCase(newUser(), "ABUSE");
        UUID open = awaitingReviewCase(newUser(), "ABUSE");

        assertThat(complete(settled, brief("HIDE", "SETTLED", List.of())).evidenceStrength())
                .isEqualTo(EvidenceStrength.SETTLED);

        // 0.85, which is what v1 and v2 produced seven times out of nine.
        assertThat(complete(leaning, brief("HIDE", 0.85, List.of())).evidenceStrength())
                .isEqualTo(EvidenceStrength.SETTLED);

        assertThat(complete(open, brief("HIDE", 0.4, List.of())).evidenceStrength())
                .isEqualTo(EvidenceStrength.OPEN);
    }

    @Test
    void refusesAConfidenceThatIsNeitherABandNorANumberInRange() {
        UUID caseId = awaitingReviewCase(newUser(), "ABUSE");

        ScriptedModel model = new ScriptedModel()
                .thenAnswers(brief("HIDE", "VERY_SURE", List.of()))
                .thenAnswers(brief("HIDE", 1.4, List.of()));

        assertThat(investigator(model).investigate(caseId))
                .isInstanceOf(InvestigationBrief.Inconclusive.class);
    }

    private InvestigationBrief.Complete complete(UUID caseId, String answer) {
        InvestigationBrief brief = investigator(new ScriptedModel().thenAnswers(answer)).investigate(caseId);
        assertThat(brief).isInstanceOf(InvestigationBrief.Complete.class);
        return (InvestigationBrief.Complete) brief;
    }

    // --- harness -------------------------------------------------------------

    private static final int MAX_STEPS = 5;

    private CaseInvestigator investigator(ToolCallingPort port) {
        return new CaseInvestigator(
                port,
                tools,
                new TestPrompt(),
                parser,
                recorder,
                adminCases,
                cases,
                new InvestigatorProperties(true, MAX_STEPS, 1, "test-v1"));
    }

    private String brief(String recommendation, Object confidence, List<UUID> cited) {
        return json(
                "A summary a reviewer could read.",
                recommendation,
                confidence,
                "The content is milder than the prior cases.",
                cited);
    }

    /** @param confidence a band, or a number, since the parser still accepts both from the older wordings */
    private String json(
            String summary, String recommendation, Object confidence, String counter, List<UUID> cited) {
        try {
            return mapper.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.of(
                    "summary", summary,
                    "recommendation", recommendation,
                    "confidence", confidence,
                    "counterEvidence", counter,
                    "citedCaseIds", cited.stream().map(UUID::toString).toList())));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A model that does what the test told it to, in order, and counts how often it was asked. */
    private final class ScriptedModel implements ToolCallingPort {

        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private Object repeatForever;

        ScriptedModel thenCalls(String tool) {
            return thenCalls(tool, null);
        }

        ScriptedModel thenCalls(String tool, String argumentsJson) {
            script.add(toolCall(tool, argumentsJson));
            return this;
        }

        ScriptedModel thenAnswers(String text) {
            script.add(new Turn.Finished(text));
            return this;
        }

        ScriptedModel thenThrows(RuntimeException failure) {
            script.add(failure);
            return this;
        }

        /** For the cases about budgets, where the model's behaviour is that it never stops. */
        ScriptedModel alwaysCalls(String tool) {
            this.repeatForever = toolCall(tool, null);
            return this;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String modelName() {
            return "scripted-model";
        }

        @Override
        public Response next(String system, List<Message> history, List<ToolSpec> specs) {
            calls.incrementAndGet();

            Object next = script.isEmpty() ? repeatForever : script.poll();
            if (next == null) {
                throw new IllegalStateException("The script ran out and no repeating turn was set.");
            }
            if (next instanceof RuntimeException failure) {
                throw failure;
            }

            return new Response((Turn) next, 100, 20);
        }

        private Turn toolCall(String tool, String argumentsJson) {
            try {
                return new Turn.CallTools(List.of(new ToolCall(
                        "call_" + UUID.randomUUID().toString().substring(0, 8),
                        tool,
                        argumentsJson == null ? null : mapper.readTree(argumentsJson))));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /** Wording is the prompt's business and is settled elsewhere; the loop only needs a version and some text. */
    private static final class TestPrompt implements InvestigationPrompt {

        @Override
        public String version() {
            return "test-v1";
        }

        @Override
        public String system() {
            return "You are a test.";
        }

        @Override
        public String opening(ModerationCaseDetail detail) {
            return "Case " + detail.moderationCase().id() + ".";
        }
    }

    private UUID awaitingReviewCase(User author, String ruleCode) {
        return caseFor(author, ruleCode, null);
    }

    private UUID resolvedCase(User author, String ruleCode) {
        return caseFor(author, ruleCode, FinalAction.HIDE);
    }

    private UUID caseFor(User author, String ruleCode, FinalAction action) {
        User admin = newAdmin();

        return new TransactionTemplate(transactionManager).execute(status -> {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));

            cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
            UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "test-engine",
                    new ModerationVerdict(
                            ModerationDecision.REMOVE, 0.9, "Recorded by a test.", List.of(ruleCode)));

            if (action != null) {
                moderationCase.resolve(admin, action);
            }

            return cases.saveAndFlush(moderationCase).getId();
        });
    }

}
