package com.campusguard.evaluation;

import com.campusguard.moderation.engine.EngineRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Runs the evaluation and writes the report, then stops the application.
 *
 * <p>A command rather than a test. A test that measured an engine would either
 * assert nothing, and so never fail, or assert a threshold, and so break the
 * build every time a prompt changed for good reasons. Evaluation answers "how
 * good is it", which is a question with a number for an answer, not a pass or a
 * fail.
 */
@Component
@ConditionalOnProperty(name = "campusguard.evaluation.run", havingValue = "true")
public class EvaluationCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCommand.class);

    private final EvaluationDataset datasets;
    private final EvaluationRunner runner;
    private final EvaluationReportWriter reportWriter;
    private final EngineRegistry engines;
    private final ApplicationContext context;

    @Value("${campusguard.evaluation.dataset:}")
    private String datasetPath;

    @Value("${campusguard.evaluation.output:docs/evaluation.md}")
    private String outputPath;

    @Value("${campusguard.evaluation.engines:}")
    private String engineNames;

    public EvaluationCommand(
            EvaluationDataset datasets,
            EvaluationRunner runner,
            EvaluationReportWriter reportWriter,
            EngineRegistry engines,
            ApplicationContext context) {
        this.datasets = datasets;
        this.runner = runner;
        this.reportWriter = reportWriter;
        this.engines = engines;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        EvaluationDataset.Loaded dataset = datasets.load(datasetPath);

        List<String> toRun = engineNames == null || engineNames.isBlank()
                ? engines.names()
                : List.of(engineNames.split(","));

        List<EvaluationResult> results = new ArrayList<>();
        for (String name : toRun) {
            EvaluationResult result = runner.run(name.trim(), dataset);
            results.add(result);
            log.info(
                    "{}: macro-F1 {} over {} samples, p95 {} ms",
                    result.engineName(),
                    String.format("%.3f", result.matrix().macroF1()),
                    result.sampleCount(),
                    String.format("%.3f", result.percentileMillis(95)));
        }

        Path output = Path.of(outputPath);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, reportWriter.render(results, dataset.starter()));

        log.info("Wrote {}", output.toAbsolutePath());
        if (dataset.starter()) {
            log.warn(
                    "This run used the bundled starter set. Its scores are a smoke test of the harness, not a measurement.");
        }

        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
