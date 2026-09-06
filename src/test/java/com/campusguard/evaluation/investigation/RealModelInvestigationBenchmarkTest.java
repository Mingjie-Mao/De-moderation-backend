package com.campusguard.evaluation.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.moderation.investigation.CaseInvestigator;
import com.campusguard.moderation.investigation.InvestigatorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * The investigation set against a real model.
 *
 * <p>Skipped unless {@code GEMINI_API_KEY} is in the environment. Run it
 * deliberately, and expect it to cost something — sixteen scenarios times three
 * runs is forty-eight investigations:
 *
 * <pre>
 * export $(grep -E '^(GEMINI_API_KEY|GEMINI_MODELS)=' .env | xargs)
 * mvn -Dtest=RealModelInvestigationBenchmarkTest -Dinvestigation.prompt=inv-v4 test
 * </pre>
 *
 * <p>A test rather than a command like {@code EvaluationCommand}, and the
 * difference is not stylistic. That command only reads, so it can be pointed at
 * any database. This one writes: it invents authors, posts and moderation
 * history to build each scenario. Against a real forum it would fabricate a
 * record for real people, so it is confined to a container that is thrown away.
 *
 * <p>Almost nothing is asserted. What an assistant recommends is not a fact about
 * this code, and a threshold here would break the build every time a prompt
 * changed for good reasons — the same argument {@code EvaluationCommand} makes.
 * The report is the output; the assertions only check that a run happened at
 * all.
 */
@TestPropertySource(
        properties = {
            "spring.ai.model.chat=google-genai",
            "campusguard.moderation.investigator.enabled=true",
            "campusguard.moderation.engine=keyword-v1",
            "campusguard.moderation.investigator.prompt-version=${investigation.prompt:inv-v4}"
        })
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class RealModelInvestigationBenchmarkTest extends AbstractIntegrationTest {

    /**
     * Three runs per scenario.
     *
     * <p>Enough to tell "always the same" from "not always the same", which is the
     * question. Distinguishing two thirds from three quarters would take far more
     * runs than this set is worth at its current size.
     */
    private static final int RUNS_EACH = 3;

    @Autowired
    private InvestigationBenchmark benchmark;

    @Autowired
    private InvestigationScenarios scenarios;

    @Autowired
    private CaseInvestigator investigator;

    @Autowired
    private InvestigatorProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${campusguard.moderation.ai.models:unknown}")
    private String models;

    @Test
    void scoresTheInvestigationSet() throws Exception {
        List<InvestigationScenario> set = scenarios.load();

        InvestigationBenchmarkReport report = benchmark.run(
                investigator, set, RUNS_EACH, properties.promptVersion(), models);

        System.out.println("\n" + report.summary());
        report.outcomes().forEach(outcome -> System.out.printf(
                "  %-8s %-22s expected %-6s got %-24s stability %.2f %s%n",
                outcome.id(),
                outcome.shape(),
                outcome.expected(),
                outcome.recommendations(),
                outcome.stability(),
                outcome.grounded() ? "grounded" : "UNGROUNDED"));

        write(report);

        assertThat(report.scenarios()).isEqualTo(set.size());
        assertThat(report.outcomes()).allSatisfy(outcome ->
                assertThat(outcome.recommendations()).hasSize(RUNS_EACH));
    }

    /**
     * Written to a file as well as printed, so two prompt versions can be
     * compared without anybody having to keep terminal output.
     */
    private void write(InvestigationBenchmarkReport report) throws Exception {
        Path file = Path.of("docs", "investigation-benchmark-%s.json".formatted(report.promptVersion()));
        Files.createDirectories(file.getParent());

        objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), report);

        System.out.println("wrote " + file.toAbsolutePath());
    }
}
