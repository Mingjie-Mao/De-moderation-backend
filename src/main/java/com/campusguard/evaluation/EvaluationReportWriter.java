package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Renders results as the markdown that goes in {@code docs/evaluation.md}. */
@Component
public class EvaluationReportWriter {

    public String render(List<EvaluationResult> results, boolean starterDataset) {
        StringBuilder out = new StringBuilder();

        out.append("# Moderation evaluation\n\n");
        out.append("_Generated ")
                .append(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE))
                .append(" by `EvaluationCommand`. Do not edit by hand._\n\n");

        if (starterDataset) {
            out.append("> **These numbers measure nothing.**\n")
                    .append("> The run used the bundled starter set, which exists to prove the harness works.\n")
                    .append("> Its samples were written to exercise the code, not sampled from real posts, so\n")
                    .append("> scores on it say only whether the engine matches the terms it was configured with.\n")
                    .append("> A real dataset has to be sampled from genuine forum content and labelled by hand.\n\n");
        }

        for (EvaluationResult result : results) {
            renderOne(out, result);
        }

        renderReproduction(out);
        return out.toString();
    }

    private void renderOne(StringBuilder out, EvaluationResult result) {
        out.append("## Engine: `").append(result.engineName()).append("`\n\n");
        out.append("Dataset `").append(result.datasetName()).append("`, ")
                .append(result.sampleCount()).append(" samples.\n\n");

        out.append("| metric | value |\n|---|---|\n");
        out.append("| macro-F1 | ").append(format(result.matrix().macroF1())).append(" |\n");
        out.append("| accuracy | ").append(format(result.matrix().accuracy())).append(" |\n");
        out.append("| p50 latency | ").append(millis(result.percentileMillis(50))).append(" ms |\n");
        out.append("| p95 latency | ").append(millis(result.percentileMillis(95))).append(" ms |\n\n");

        out.append("### Per-decision\n\n");
        out.append("| decision | support | precision | recall | F1 |\n|---|---|---|---|---|\n");
        for (ConfusionMatrix.ClassMetrics metrics : result.matrix().allMetrics()) {
            out.append("| ").append(metrics.label())
                    .append(" | ").append(metrics.support())
                    .append(" | ").append(format(metrics.precision()))
                    .append(" | ").append(format(metrics.recall()))
                    .append(" | ").append(format(metrics.f1()))
                    .append(" |\n");
        }
        out.append('\n');

        renderMatrix(out, result);
        renderMisses(out, result);
    }

    private void renderMatrix(StringBuilder out, EvaluationResult result) {
        List<ModerationDecision> labels = result.matrix().labels();

        out.append("### Confusion matrix\n\n");
        out.append("Rows are the labelled answer, columns what the engine said.\n\n");
        out.append("| actual \\ predicted |");
        labels.forEach(label -> out.append(' ').append(label).append(" |"));
        out.append("\n|---|");
        labels.forEach(label -> out.append("---|"));
        out.append('\n');

        for (ModerationDecision actual : labels) {
            out.append("| **").append(actual).append("** |");
            for (ModerationDecision predicted : labels) {
                out.append(' ').append(result.matrix().count(actual, predicted)).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private void renderMisses(StringBuilder out, EvaluationResult result) {
        out.append("### Failure modes\n\n");

        if (result.misses().isEmpty()) {
            out.append("No mistakes on this dataset.\n\n");
            return;
        }

        out.append(result.misses().size())
                .append(" of ")
                .append(result.sampleCount())
                .append(" samples were judged wrongly.\n\n");
        out.append("| sample | category | expected | got | content | engine said |\n|---|---|---|---|---|---|\n");

        for (EvaluationResult.Miss miss : result.misses()) {
            out.append("| ").append(miss.sampleId())
                    .append(" | ").append(miss.category())
                    .append(" | ").append(miss.expected())
                    .append(" | ").append(miss.actual())
                    .append(" | ").append(escape(miss.excerpt()))
                    .append(" | ").append(escape(miss.rationale()))
                    .append(" |\n");
        }
        out.append('\n');
    }

    private void renderReproduction(StringBuilder out) {
        out.append("## Reproducing this\n\n");
        out.append("```bash\n");
        out.append("set -a && . ./.env && set +a && mvn spring-boot:run \\\n");
        out.append("  -Dspring-boot.run.arguments=\"--campusguard.evaluation.run=true \\\n");
        out.append("  --campusguard.evaluation.dataset=docs/evaluation-samples.json\"\n");
        out.append("```\n\n");
        out.append("Omitting `--campusguard.evaluation.dataset` uses the bundled starter set.\n");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String millis(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}
