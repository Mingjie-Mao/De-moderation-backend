package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ai.EngineInvocationStats;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/** Renders a run as the markdown that goes in {@code docs/evaluation.md}. */
@Component
public class EvaluationReportWriter {

    private static final int MAX_ROWS_PER_SECTION = 25;

    public String render(BenchmarkReport report) {
        StringBuilder out = new StringBuilder();

        out.append("# Moderation engine benchmark\n\n");
        out.append("_Generated ")
                .append(DateTimeFormatter.ISO_LOCAL_DATE.format(report.generatedAt().atZone(ZoneOffset.UTC)))
                .append(" by `EvaluationCommand`. Do not edit by hand._\n\n");

        if (report.starterDataset()) {
            out.append("> **These numbers measure nothing.**\n")
                    .append("> The run used the bundled starter set, which exists to prove the harness works.\n")
                    .append("> Its samples were written to exercise the code, not sampled from real posts, so\n")
                    .append("> scores on it say only whether an engine matches the terms it was configured with.\n")
                    .append("> A real dataset has to be sampled from genuine forum content and labelled by hand.\n\n");
        }

        out.append("Dataset `").append(report.datasetName()).append("`, ")
                .append(report.sampleCount()).append(" samples.\n\n");

        renderComparison(out, report);
        renderDisagreements(out, report);
        report.results().forEach(result -> renderEngine(out, result));
        renderProduction(out, report);
        renderReproduction(out);

        return out.toString();
    }

    private void renderComparison(StringBuilder out, BenchmarkReport report) {
        out.append("## Side by side\n\n");
        out.append("| engine | status | accuracy | macro-F1 | macro-P | macro-R | errors | mean | p50 | p95 | tokens | est. cost |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (EvaluationResult result : report.results()) {
            if (result.status() == EngineRunStatus.UNAVAILABLE) {
                out.append("| `").append(result.engineName()).append("` | **UNAVAILABLE** | — | — | — | — | ")
                        .append(result.errorCount()).append(" | — | — | — | — | — |\n");
                continue;
            }

            out.append("| `").append(result.engineName()).append("` | ").append(result.status())
                    .append(" | ").append(decimal(result.matrix().accuracy()))
                    .append(" | ").append(decimal(result.matrix().macroF1()))
                    .append(" | ").append(decimal(result.matrix().macroPrecision()))
                    .append(" | ").append(decimal(result.matrix().macroRecall()))
                    .append(" | ").append(result.errorCount())
                    .append(" | ").append(millis(result.meanMillis()))
                    .append(" ms | ").append(millis(result.percentileMillis(50)))
                    .append(" ms | ").append(millis(result.percentileMillis(95)))
                    .append(" ms | ").append(tokens(result))
                    .append(" | ").append(cost(result.estimatedCost()))
                    .append(" |\n");
        }
        out.append('\n');

        report.results().stream()
                .filter(result -> result.status() == EngineRunStatus.UNAVAILABLE)
                .forEach(result -> out.append("`").append(result.engineName())
                        .append("` answered nothing: ").append(result.unavailableReason()).append("\n\n"));

        out.append("An engine that answered nothing scores zero on every column, which on a table\n")
                .append("is indistinguishable from an engine that answered everything wrongly. The\n")
                .append("status column is there so those two are never confused.\n\n");
    }

    private void renderDisagreements(StringBuilder out, BenchmarkReport report) {
        if (report.comparisons().isEmpty()) {
            return;
        }

        out.append("## Where they disagree\n\n");
        out.append("A higher average is not by itself a reason to switch. What matters is whether\n")
                .append("the candidate fixed the baseline's mistakes or traded them for different\n")
                .append("ones, and only the per-sample comparison answers that.\n\n");

        for (EngineComparator.Comparison comparison : report.comparisons()) {
            out.append("### `").append(comparison.candidateEngine())
                    .append("` against `").append(comparison.baselineEngine()).append("`\n\n");

            out.append("| | count |\n|---|---|\n");
            out.append("| both right | ").append(comparison.bothRight()).append(" |\n");
            out.append("| candidate fixed | ").append(comparison.candidateFixed().size()).append(" |\n");
            out.append("| candidate broke | ").append(comparison.candidateBroke().size()).append(" |\n");
            out.append("| both wrong | ").append(comparison.bothWrong().size()).append(" |\n");
            out.append("| **net** | **").append(comparison.netGain() >= 0 ? "+" : "")
                    .append(comparison.netGain()).append("** |\n\n");

            renderDeltas(out, "Fixed by the candidate", comparison.candidateFixed());
            renderDeltas(out, "Broken by the candidate", comparison.candidateBroke());
            renderDeltas(out, "Wrong in both", comparison.bothWrong());
        }
    }

    private void renderDeltas(StringBuilder out, String heading, List<EngineComparator.SampleDelta> deltas) {
        out.append("**").append(heading).append("** (").append(deltas.size()).append(")\n\n");

        if (deltas.isEmpty()) {
            out.append("None.\n\n");
            return;
        }

        out.append("| sample | category | expected | baseline | candidate | content |\n|---|---|---|---|---|---|\n");
        deltas.stream().limit(MAX_ROWS_PER_SECTION).forEach(delta -> out
                .append("| ").append(delta.sampleId())
                .append(" | ").append(delta.category())
                .append(" | ").append(delta.expected())
                .append(" | ").append(delta.baselineSaid())
                .append(" | ").append(delta.candidateSaid())
                .append(" | ").append(escape(delta.excerpt()))
                .append(" |\n"));

        if (deltas.size() > MAX_ROWS_PER_SECTION) {
            out.append("\n_").append(deltas.size() - MAX_ROWS_PER_SECTION)
                    .append(" more in `evaluation-samples.csv`._\n");
        }
        out.append('\n');
    }

    private void renderEngine(StringBuilder out, EvaluationResult result) {
        out.append("## `").append(result.engineName()).append("` in detail\n\n");

        if (result.status() == EngineRunStatus.UNAVAILABLE) {
            out.append("Answered nothing. ").append(result.unavailableReason()).append("\n\n");
            return;
        }

        if (result.status() == EngineRunStatus.DEGRADED) {
            out.append("> ").append(result.errorCount()).append(" of ").append(result.sampleCount())
                    .append(" samples failed outright. Everything below covers only the ")
                    .append(result.judgedCount()).append(" it answered.\n\n");
        }

        out.append("| decision | support | precision | recall | F1 |\n|---|---|---|---|---|\n");
        for (ConfusionMatrix.ClassMetrics metrics : result.matrix().allMetrics()) {
            out.append("| ").append(metrics.label())
                    .append(" | ").append(metrics.support())
                    .append(" | ").append(decimal(metrics.precision()))
                    .append(" | ").append(decimal(metrics.recall()))
                    .append(" | ").append(decimal(metrics.f1()))
                    .append(" |\n");
        }
        out.append('\n');

        renderMatrix(out, result);
        renderMisses(out, result);
    }

    private void renderMatrix(StringBuilder out, EvaluationResult result) {
        List<ModerationDecision> labels = result.matrix().labels();

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
        List<SampleOutcome> misses = result.misses();

        if (misses.isEmpty()) {
            out.append("No mistakes on this dataset.\n\n");
            return;
        }

        out.append(misses.size()).append(" of ").append(result.judgedCount())
                .append(" answered samples were judged wrongly.\n\n");
        out.append("| sample | category | expected | got | content | engine said |\n|---|---|---|---|---|---|\n");

        misses.stream().limit(MAX_ROWS_PER_SECTION).forEach(miss -> out
                .append("| ").append(miss.sampleId())
                .append(" | ").append(miss.category())
                .append(" | ").append(miss.expected())
                .append(" | ").append(miss.actual())
                .append(" | ").append(escape(miss.excerpt()))
                .append(" | ").append(escape(miss.rationale()))
                .append(" |\n"));

        if (misses.size() > MAX_ROWS_PER_SECTION) {
            out.append("\n_").append(misses.size() - MAX_ROWS_PER_SECTION)
                    .append(" more in `evaluation-samples.csv`._\n");
        }
        out.append('\n');
    }

    private void renderProduction(StringBuilder out, BenchmarkReport report) {
        out.append("## On real traffic\n\n");

        if (report.productionStats().isEmpty()) {
            out.append("No model calls have been recorded yet.\n\n");
            return;
        }

        out.append("From `ai_invocations`: what these engines have actually done, as opposed to how\n")
                .append("they score on a set someone chose. An engine can look good here and be\n")
                .append("unusable there.\n\n");
        out.append("| engine | calls | succeeded | failed | mean | p95 | prompt tokens | completion tokens |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");

        for (EngineInvocationStats stats : report.productionStats()) {
            out.append("| `").append(stats.getEngine()).append("` | ").append(stats.getCalls())
                    .append(" | ").append(stats.getSuccesses())
                    .append(" | ").append(stats.getFailures())
                    .append(" | ").append(millis(stats.getAvgLatencyMs()))
                    .append(" ms | ").append(millis(stats.getP95LatencyMs()))
                    .append(" ms | ").append(stats.getPromptTokens())
                    .append(" | ").append(stats.getCompletionTokens())
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
        out.append("Omitting `--campusguard.evaluation.dataset` uses the bundled starter set. Every\n")
                .append("registered engine runs; one that is unavailable is reported and skipped\n")
                .append("rather than ending the run. Machine-readable output lands beside this file as\n")
                .append("`evaluation.json` and `evaluation-samples.csv`.\n\n");
        out.append("Token prices are not built in, because a plausible default would put a number\n")
                .append("in this report that nobody checked. Configure them per engine under\n")
                .append("`campusguard.evaluation.pricing` to fill in the cost column.\n");
    }

    private String tokens(EvaluationResult result) {
        OptionalInt prompt = result.totalPromptTokens();
        OptionalInt completion = result.totalCompletionTokens();
        if (prompt.isEmpty() && completion.isEmpty()) {
            return "0";
        }
        return prompt.orElse(0) + " + " + completion.orElse(0);
    }

    private String cost(BigDecimal estimate) {
        if (estimate == null) {
            return "not priced";
        }
        if (estimate.signum() == 0) {
            return "$0";
        }
        return "$" + estimate.toPlainString();
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String millis(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}
