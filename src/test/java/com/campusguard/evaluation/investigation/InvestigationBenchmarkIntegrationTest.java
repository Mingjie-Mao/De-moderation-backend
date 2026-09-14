package com.campusguard.evaluation.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.investigation.BriefParser;
import com.campusguard.moderation.investigation.CaseInvestigator;
import com.campusguard.moderation.investigation.EvidenceStrength;
import com.campusguard.moderation.investigation.InvestigationPromptV1;
import com.campusguard.moderation.investigation.InvestigationPromptV4;
import com.campusguard.moderation.investigation.InvestigatorProperties;
import com.campusguard.moderation.investigation.ToolCall;
import com.campusguard.moderation.investigation.ToolCallingPort;
import com.campusguard.moderation.investigation.ToolRegistry;
import com.campusguard.moderation.investigation.ToolSpec;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The benchmark measuring a model it was told the answers for.
 *
 * <p>Nothing here needs a credential. What is being checked is the scoring — that
 * an assistant which always says the same thing scores as stable, that one which
 * alternates does not, that a right answer with no citation is not counted as
 * grounded. Those are the numbers that will later be used to argue a prompt is
 * better, so they are worth more scepticism than the prompt is.
 *
 * <p>The fixtures are real: scenarios are materialised into PostgreSQL and the
 * tools read them back. Only the model is scripted.
 */
class InvestigationBenchmarkIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InvestigationBenchmark benchmark;

    @Autowired
    private InvestigationScenarios scenarios;

    @Autowired
    private ScenarioFixture fixture;

    @Autowired
    private ToolRegistry tools;

    @Autowired
    private BriefParser parser;

    @Autowired
    private AiInvocationRecorder recorder;

    @Autowired
    private AdminModerationService adminCases;

    @Autowired
    private ModerationCaseRepository cases;

    @Test
    void theBundledSetLoadsAndCoversTheShapesItClaimsTo() throws Exception {
        List<InvestigationScenario> loaded = scenarios.load();

        assertThat(loaded).hasSize(32);
        assertThat(loaded).extracting(InvestigationScenario::id).doesNotHaveDuplicates();

        // Every expected outcome appears. A set that never expects NONE would
        // measure only how well an assistant agrees that content is bad.
        assertThat(loaded).extracting(InvestigationScenario::expected)
                .contains(FinalAction.NONE, FinalAction.HIDE, FinalAction.DELETE, FinalAction.BAN);

        // And at least one case the engine let through. Without it the set can
        // only ask whether the assistant agrees with a takedown, never whether it
        // would act on something the engine left up.
        assertThat(loaded).extracting(InvestigationScenario::engineDecision).contains(ModerationDecision.ALLOW);

        // The expected answer must itself be defensible, or the set contradicts
        // its own scoring.
        assertThat(loaded).allSatisfy(scenario ->
                assertThat(scenario.acceptable()).contains(scenario.expected()));
    }

    @Test
    void materialisesTheHistoryAndThePrecedentAScenarioDescribes() throws Exception {
        InvestigationScenario scenario = scenarioNamed("inv-001");
        int dismissals = (int) scenario.precedentActions().stream()
                .filter(action -> action == FinalAction.NONE)
                .count();
        assertThat(dismissals).as("inv-001 is used because its precedent carries a dismissal").isPositive();

        ScenarioFixture.Built built = fixture.build(scenario);

        assertThat(built.priorCaseIds()).hasSize(scenario.priorActions().size());
        assertThat(built.precedentCaseIds()).hasSize(scenario.precedentActions().size() - dismissals);
        assertThat(built.dismissedCaseIds()).hasSize(dismissals);
        assertThat(cases.findById(built.caseId())).isPresent();
        // A dismissal is never something a brief must cite: the precedent lookup
        // does not list it, so no brief could.
        assertThat(built.required(InvestigationScenario.MustCite.BOTH))
                .hasSize(built.priorCaseIds().size() + built.precedentCaseIds().size())
                .doesNotContainAnyElementsOf(built.dismissedCaseIds());
    }

    /**
     * Grounding can only be earned by citing what a tool returned, so every case a
     * scenario requires has to be one the tools actually disclose.
     *
     * <p>The check that was missing while precedent counted dismissed reports: five
     * scenarios required a citation the precedent lookup leaves out by design,
     * their grounding could never be anything but zero, and that zero read as a
     * failure of the assistant.
     */
    @Test
    void everyCaseAScenarioRequiresCitingIsOneTheToolsDisclose() throws Exception {
        for (InvestigationScenario scenario : scenarios.load()) {
            ScenarioFixture.Built built = fixture.build(scenario);

            Set<UUID> disclosed = new LinkedHashSet<>(
                    tools.execute(built.caseId(), new ToolCall("history", "authorHistory", null)).disclosedCaseIds());
            if (scenario.ruleCode() != null) {
                disclosed.addAll(tools.execute(built.caseId(), new ToolCall(
                                "precedent",
                                "similarResolvedCases",
                                objectMapper.createObjectNode().put("ruleCode", scenario.ruleCode())))
                        .disclosedCaseIds());
            }

            assertThat(disclosed)
                    .as("%s requires citing a case no tool returns", scenario.id())
                    .containsAll(built.required(scenario.mustCite()));

            // Thirty-two cases left awaiting review would fill the admin list's
            // first page, which is paged at fifty and shared with every other
            // test here.
            fixture.retire(built);
        }
    }

    /** An assistant that answers the same way every time should score 1.0, and nothing else should. */
    @Test
    void scoresARepeatableAssistantAsStable() throws Exception {
        InvestigationBenchmarkReport report = benchmark.run(
                investigator(always("BAN")), List.of(scenarioNamed("inv-001")), 3, "stub", "stub-model");

        assertThat(report.stability()).isEqualTo(1.0);
        assertThat(report.unanimousScenarios()).isEqualTo(1);
        assertThat(report.agreement()).isEqualTo(1.0);
    }

    /**
     * The number this whole benchmark exists for.
     *
     * <p>An assistant that alternates between two answers is the failure that hid
     * for five runs because step counts looked identical. Two of three runs
     * agreeing is a stability of two thirds, and it must not round up to fine.
     */
    @Test
    void scoresAnAssistantThatChangesItsMindAsUnstable() throws Exception {
        InvestigationBenchmarkReport report = benchmark.run(
                investigator(cycling("BAN", "HIDE", "BAN")),
                List.of(scenarioNamed("inv-001")),
                3,
                "stub",
                "stub-model");

        assertThat(report.stability()).isEqualTo(2.0 / 3);
        assertThat(report.unanimousScenarios()).isZero();
        // The modal answer is still the acceptable one, so agreement stays high.
        // Reporting both is the point: one number cannot say this.
        assertThat(report.agreement()).isEqualTo(1.0);
    }

    /**
     * A right answer that cites nothing is a guess that landed. Over a set this
     * size the two are indistinguishable without measuring citation separately.
     */
    @Test
    void doesNotCreditAnUngroundedBrief() throws Exception {
        InvestigationScenario mustCitePriors = scenarioNamed("inv-010");

        InvestigationBenchmarkReport report = benchmark.run(
                investigator(always("HIDE")), List.of(mustCitePriors), 1, "stub", "stub-model");

        assertThat(report.agreement()).isEqualTo(1.0);
        assertThat(report.grounding()).isZero();
        assertThat(report.outcomes().getFirst().groundedRuns()).isZero();
    }

    /** An investigation that never concluded is neither right nor wrong, and must not be averaged away. */
    @Test
    void countsAnInconclusiveRunAgainstTheScore() throws Exception {
        InvestigationBenchmarkReport report = benchmark.run(
                investigator(always("not json at all")),
                List.of(scenarioNamed("inv-003")),
                2,
                "stub",
                "stub-model");

        assertThat(report.outcomes().getFirst().modal()).isNull();
        assertThat(report.agreement()).isZero();
        assertThat(report.evidence().values()).allSatisfy(tally -> assertThat(tally.runs()).isZero());
    }

    /**
     * The band a brief carried, counted against whether what it recommended was
     * defensible. With runs above one that count is what consensus mode exists to
     * produce, so the counting is pinned here rather than trusted.
     */
    @Test
    void countsDefensibleRunsByTheBandTheirBriefCarried() throws Exception {
        // inv-001 accepts only BAN and inv-002 does not accept it at all. The stub
        // always answers BAN, graded LEANING.
        InvestigationBenchmarkReport report = benchmark.run(
                investigator(always("BAN")),
                List.of(scenarioNamed("inv-001"), scenarioNamed("inv-002")),
                2,
                "stub",
                "stub-model");

        assertThat(report.evidence().get(EvidenceStrength.LEANING))
                .isEqualTo(new InvestigationBenchmarkReport.BandTally(4, 2));
        assertThat(report.evidence().get(EvidenceStrength.SETTLED).runs()).isZero();
        assertThat(report.outcomes()).allSatisfy(outcome ->
                assertThat(outcome.evidence()).containsOnly(EvidenceStrength.LEANING));
    }

    @Test
    void reportsTallies() throws Exception {
        InvestigationBenchmarkReport report = benchmark.run(
                investigator(always("HIDE")),
                List.of(scenarioNamed("inv-001"), scenarioNamed("inv-010")),
                1,
                "stub",
                "stub-model");

        assertThat(report.recommended()).containsEntry(FinalAction.HIDE, 2);
        assertThat(report.expected())
                .containsEntry(FinalAction.BAN, 1)
                .containsEntry(FinalAction.HIDE, 1);
        assertThat(report.summary()).contains("agreement").contains("stability").contains("evidence");
    }

    // --- harness -------------------------------------------------------------

    private InvestigationScenario scenarioNamed(String id) throws Exception {
        return scenarios.load().stream()
                .filter(scenario -> scenario.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private com.campusguard.moderation.investigation.Investigator investigator(ToolCallingPort port) {
        InvestigationPromptV1 v1 = new InvestigationPromptV1();
        return new CaseInvestigator(
                port, tools, new InvestigationPromptV4(v1), parser, recorder, adminCases, cases,
                new InvestigatorProperties(true, 5, 1, "bench-stub", 1, 60));
    }

    /** Answers with the same recommendation every time, citing nothing. */
    private ToolCallingPort always(String recommendation) {
        return scripted(new ArrayDeque<>(List.of(recommendation)), true);
    }

    /** Works through the list and then stops, so a run count beyond it is a test error rather than a silent repeat. */
    private ToolCallingPort cycling(String... recommendations) {
        return scripted(new ArrayDeque<>(List.of(recommendations)), false);
    }

    private ToolCallingPort scripted(Deque<String> answers, boolean repeat) {
        return new ToolCallingPort() {
            @Override
            public String modelName() {
                return "stub-model";
            }

            @Override
            public Response next(String system, List<Message> history, List<ToolSpec> specs) {
                String recommendation = repeat ? answers.peek() : answers.poll();
                if (recommendation == null) {
                    throw new IllegalStateException("The benchmark asked more times than the script allows.");
                }
                return new Response(new Turn.Finished(brief(recommendation)), 100, 20);
            }
        };
    }

    private String brief(String recommendation) {
        if (!List.of("NONE", "HIDE", "DELETE", "BAN").contains(recommendation)) {
            return recommendation;
        }
        return """
                {"summary":"A summary.","recommendation":"%s","confidence":"LEANING",
                 "counterEvidence":"Something that argues the other way.","citedCaseIds":[]}
                """
                .formatted(recommendation);
    }

}
