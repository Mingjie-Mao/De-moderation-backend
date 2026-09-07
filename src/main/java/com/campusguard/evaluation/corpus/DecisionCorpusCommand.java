package com.campusguard.evaluation.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * Writes the decision corpus and stops the application.
 *
 * <p>A command rather than an endpoint. This reads every piece of content the
 * forum has moderated, including content that was removed, and writes it to a
 * file; that is a thing an operator should do deliberately on a box they are
 * sitting at, not something reachable over HTTP because somebody guessed a URL.
 *
 * <pre>
 * java -jar app.jar --campusguard.corpus.export=true --campusguard.corpus.output-dir=/tmp
 * </pre>
 *
 * <p>Off unless asked for, like the evaluation command it is modelled on.
 */
@Component
@ConditionalOnProperty(name = "campusguard.corpus.export", havingValue = "true")
public class DecisionCorpusCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DecisionCorpusCommand.class);

    private final DecisionCorpusExporter exporter;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    @Value("${campusguard.corpus.output-dir:docs}")
    private String outputDir;

    @Value("${campusguard.corpus.limit:5000}")
    private int limit;

    public DecisionCorpusCommand(
            DecisionCorpusExporter exporter, ObjectMapper objectMapper, ApplicationContext context) {
        this.exporter = exporter;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<DecisionSample> samples = exporter.export(limit);

        Path directory = Path.of(outputDir);
        Files.createDirectories(directory);
        Path file = directory.resolve("decision-corpus.json");

        objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), samples);

        report(samples, file);

        // Ends the process, not just the context. Closing the context alone lets
        // startup carry on into whatever runs next, which then meets a closed
        // EntityManagerFactory and reports a failure after the file was written
        // perfectly well — an operator would read that as the export having
        // failed. EvaluationCommand ends the same way for the same reason.
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /**
     * Says what was harvested, in the terms that decide what it is good for.
     *
     * <p>A count alone would hide the two numbers that matter: how many rows carry
     * a label somebody reconsidered, and how many carry no ESCALATE label because
     * none can be derived. Both are small at first, and both are the reason to
     * start collecting early rather than when a model is wanted.
     */
    private void report(List<DecisionSample> samples, Path file) {
        Map<LabelBasis, Long> byBasis = samples.stream()
                .collect(Collectors.groupingBy(DecisionSample::basis, Collectors.counting()));

        long disagreed = samples.stream().filter(sample -> !sample.engineAgreed()).count();
        long hard = samples.stream().filter(DecisionSample::hard).count();
        long repeat = samples.stream().filter(sample -> sample.priorResolvedCases() > 0).count();

        log.info("Wrote {} decisions to {}", samples.size(), file.toAbsolutePath());
        log.info("  by basis: {}", byBasis);
        log.info("  the engine was overruled on {} of them", disagreed);
        log.info("  {} took a second look and are candidates for an ESCALATE label a person would have to add",
                hard);
        log.info("  {} were decided against an author who already had a resolved case", repeat);
    }
}
