package com.campusguard.evaluation;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Scores an engine against labelled data.
 *
 * <p>Takes an engine by name rather than the configured one, so the same code
 * measures the keyword baseline and anything added later. That is the point of
 * running this before a model exists: without a floor to compare against, a
 * model's score answers "is it good?" when the question that decides whether to
 * ship it is "is it better than what we already had, and by how much, at what
 * cost?".
 */
@Service
public class EvaluationRunner {

    private final EngineRegistry engines;

    public EvaluationRunner(EngineRegistry engines) {
        this.engines = engines;
    }

    public EvaluationResult run(String engineName, EvaluationDataset.Loaded dataset) {
        ModerationEngine engine = engines.require(engineName);

        ConfusionMatrix matrix = new ConfusionMatrix();
        List<Long> latencies = new ArrayList<>();
        List<EvaluationResult.Miss> misses = new ArrayList<>();

        for (LabelledSample sample : dataset.samples()) {
            ModerationRequest request = ModerationRequest.of(
                    TargetType.POST, UUID.randomUUID(), sample.title(), sample.body(), UUID.randomUUID());

            long startedAt = System.nanoTime();
            ModerationVerdict verdict = engine.evaluate(request);
            latencies.add(Duration.ofNanos(System.nanoTime() - startedAt).toNanos() / 1_000);

            matrix.record(sample.expected(), verdict.decision());

            if (verdict.decision() != sample.expected()) {
                misses.add(new EvaluationResult.Miss(
                        sample.id(),
                        sample.category(),
                        sample.expected(),
                        verdict.decision(),
                        excerpt(sample),
                        verdict.rationale()));
            }
        }

        return new EvaluationResult(
                engine.name(), dataset.name(), dataset.samples().size(), matrix, latencies, misses);
    }

    private String excerpt(LabelledSample sample) {
        String text = sample.title() == null || sample.title().isBlank()
                ? sample.body()
                : sample.title() + " / " + sample.body();
        String collapsed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 90 ? collapsed : collapsed.substring(0, 87) + "...";
    }
}
