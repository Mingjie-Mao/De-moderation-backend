package com.campusguard.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs the benchmark and writes the three reports, then stops the application.
 *
 * <p>A command rather than a test. A test that measured an engine would either
 * assert nothing, and so never fail, or assert a threshold, and so break the
 * build every time a prompt changed for good reasons. Evaluation answers "how
 * good is it", which has a number for an answer, not a pass or a fail.
 */
@Component
@ConditionalOnProperty(name = "campusguard.evaluation.run", havingValue = "true")
public class EvaluationCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCommand.class);

    private final BenchmarkService benchmark;
    private final EvaluationReportWriter markdownWriter;
    private final EvaluationJsonWriter jsonWriter;
    private final EvaluationCsvWriter csvWriter;
    private final ApplicationContext context;

    @Value("${campusguard.evaluation.dataset:}")
    private String datasetPath;

    @Value("${campusguard.evaluation.output-dir:docs}")
    private String outputDir;

    @Value("${campusguard.evaluation.engines:}")
    private String engineNames;

    /**
     * The stem of the three files written. Varies because there is more than one
     * dataset now: a held-out run must not overwrite the report of the set the
     * prompts were tuned on, or the comparison between them is gone.
     */
    @Value("${campusguard.evaluation.output-prefix:evaluation}")
    private String outputPrefix;

    public EvaluationCommand(
            BenchmarkService benchmark,
            EvaluationReportWriter markdownWriter,
            EvaluationJsonWriter jsonWriter,
            EvaluationCsvWriter csvWriter,
            ApplicationContext context) {
        this.benchmark = benchmark;
        this.markdownWriter = markdownWriter;
        this.jsonWriter = jsonWriter;
        this.csvWriter = csvWriter;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> requested = engineNames == null || engineNames.isBlank()
                ? List.of()
                : Arrays.stream(engineNames.split(",")).map(String::trim).filter(name -> !name.isBlank()).toList();

        BenchmarkReport report = benchmark.run(datasetPath, requested);

        for (EngineRuns runs : report.repeats()) {
            EvaluationResult result = runs.representative();
            if (result.status() == EngineRunStatus.UNAVAILABLE) {
                log.warn("{}: unavailable ({})", result.engineName(), result.unavailableReason());
                continue;
            }
            log.info(
                    "{}: macro-F1 {} (mean of {} run(s), range {}) over {} judged samples ({} errors), p95 {} ms",
                    result.engineName(),
                    String.format("%.3f", runs.macroF1().mean()),
                    runs.measured().size(),
                    String.format("%.3f", runs.macroF1().range()),
                    result.judgedCount(),
                    result.errorCount(),
                    String.format("%.3f", result.percentileMillis(95)));
        }

        Path directory = Path.of(outputDir);
        Files.createDirectories(directory);

        write(directory.resolve(outputPrefix + ".md"), markdownWriter.render(report));
        write(directory.resolve(outputPrefix + ".json"), jsonWriter.render(report));
        write(directory.resolve(outputPrefix + "-samples.csv"), csvWriter.render(report));

        if (report.starterDataset()) {
            log.warn(
                    "This run used the bundled starter set. Its scores are a smoke test of the harness, not a measurement.");
        }

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void write(Path path, String content) throws Exception {
        Files.writeString(path, content);
        log.info("Wrote {}", path.toAbsolutePath());
    }
}
