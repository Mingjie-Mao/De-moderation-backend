package com.campusguard.evaluation.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.moderation.investigation.Investigator;
import com.campusguard.moderation.investigation.InvestigatorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.test.context.TestPropertySource;

/**
 * The investigation set against a real model.
 *
 * <p>Skipped unless {@code GEMINI_API_KEY} is in the environment. Run it
 * deliberately, and expect it to cost something — thirty-two scenarios times
 * three runs is ninety-six investigations, and with {@code -Dinvestigation.runs=3}
 * each of those is three calls:
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
            "campusguard.moderation.investigator.prompt-version=${investigation.prompt:inv-v5}",
            // -Dinvestigation.runs=3 measures the consensus rather than one opinion.
            "campusguard.moderation.investigator.runs=${investigation.runs:1}"
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

    /**
     * The interface, not the loop.
     *
     * <p>With {@code runs} above one the bean is a {@link com.campusguard.moderation.investigation.ConsensusInvestigator}
     * wrapping the loop, and asking for the concrete type would fail to start —
     * which is exactly what it did the first time this was run at three.
     */
    @Autowired
    private Investigator investigator;

    @Autowired
    private InvestigatorProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${campusguard.moderation.ai.models:unknown}")
    private String models;

    @Value("${campusguard.moderation.investigator.runs:1}")
    private int runs;

    /**
     * Start to start, between investigations. Five seconds a call is the pacing
     * {@code EVAL_CALL_INTERVAL} gives the engine benchmark, which keeps a
     * free-tier key under fifteen requests a minute; at runs=3 an investigation is
     * three calls at once, so the default grows with it. Pass
     * {@code -Dinvestigation.interval=0s} on a key without that ceiling.
     */
    @Value("${investigation.interval:}")
    private String interval;

    /** Where the report goes, so a run from a separate worktree can still write into the main checkout. */
    @Value("${investigation.output-dir:docs}")
    private String outputDir;

    @Test
    void scoresTheInvestigationSet() throws Exception {
        List<InvestigationScenario> set = scenarios.load();

        Duration pacing = interval == null || interval.isBlank()
                ? Duration.ofSeconds(5L * runs)
                : DurationStyle.detectAndParse(interval);

        InvestigationBenchmarkReport report = benchmark.run(
                investigator, set, RUNS_EACH, properties.promptVersion(), models, pacing);

        System.out.println("\n" + report.summary());
        report.outcomes().forEach(outcome -> System.out.printf(
                "  %-8s %-30s expected %-6s got %-24s bands %-28s stability %.2f grounded %d/%d%n",
                outcome.id(),
                outcome.shape(),
                outcome.expected(),
                outcome.recommendations(),
                outcome.evidence(),
                outcome.stability(),
                outcome.groundedRuns(),
                RUNS_EACH));

        write(report);

        assertThat(report.scenarios()).isEqualTo(set.size());
        assertThat(report.outcomes()).allSatisfy(outcome ->
                assertThat(outcome.recommendations()).hasSize(RUNS_EACH));
    }

    /**
     * Written to a file as well as printed, so two prompt versions can be
     * compared without anybody having to keep terminal output.
     *
     * <p>The scenario count is part of the name because the set grows, and a
     * report over sixteen scenarios must not be overwritten by one over
     * thirty-two: the figures quoted in the documentation came from the first.
     */
    private void write(InvestigationBenchmarkReport report) throws Exception {
        Path file = Path.of(outputDir, "investigation-benchmark-%s%s-%d-scenarios.json".formatted(
                report.promptVersion(), runs > 1 ? "-consensus" + runs : "", report.scenarios()));
        Files.createDirectories(file.getParent());

        objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), report);

        System.out.println("wrote " + file.toAbsolutePath());
    }
}
