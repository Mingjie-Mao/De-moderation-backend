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

        renderComposition(out, report);
        renderComparison(out, report);
        renderPrecision(out, report);
        renderPairs(out, report);
        renderDisagreements(out, report);
        report.results().forEach(result -> renderEngine(out, result));
        renderProduction(out, report);
        renderReproduction(out);

        return out.toString();
    }

    /**
     * States what the dataset is made of before any score is shown, because the
     * composition bounds what every number below it can mean.
     */
    private void renderComposition(StringBuilder out, BenchmarkReport report) {
        List<SampleOutcome> all = report.results().stream()
                .filter(result -> result.status() != EngineRunStatus.UNAVAILABLE)
                .findFirst()
                .map(EvaluationResult::outcomes)
                .orElse(List.of());

        if (all.isEmpty()) {
            return;
        }

        long seeded = all.stream().filter(outcome -> outcome.provenance() == SampleProvenance.SEEDED).count();
        long paired = all.stream().filter(SampleOutcome::paired).count();

        out.append("## What this dataset is\n\n");
        out.append("| expected action | seeded | written for this | total |\n|---|---|---|---|\n");

        for (ModerationDecision decision : ModerationDecision.values()) {
            long real = count(all, decision, SampleProvenance.SEEDED);
            long authored = count(all, decision, SampleProvenance.AUTHORED);
            if (real + authored == 0) {
                continue;
            }
            out.append("| ").append(decision).append(" | ").append(real)
                    .append(" | ").append(authored).append(" | ").append(real + authored).append(" |\n");
        }
        out.append('\n');

        // The two-source prose below describes the 192-sample set, where the split
        // is the most important thing about the data. A set with one source is a
        // different object and the paragraphs would be false on it, so they are
        // conditional rather than always printed.
        if (seeded == 0) {
            out.append("Every sample here was written for this evaluation. That removes the flaw the\n")
                    .append("192-sample set has and cannot lose — there, the source of a sample almost\n")
                    .append("perfectly predicts its label, so part of any good score is a reward for\n")
                    .append("noticing which half a sample came from — and it removes the per-source\n")
                    .append("comparison along with it. There is one source, so that breakdown says\n")
                    .append("nothing and is not drawn.\n\n");
            out.append("What replaces it is the pair structure. ").append(paired)
                    .append(" of these samples are one half of a\n")
                    .append("minimal pair: the same post, one deliberate difference, two different\n")
                    .append("correct answers. Within a pair the author, the topic, the language and the\n")
                    .append("register are held constant, so the only thing left to notice is the thing\n")
                    .append("being measured. Pairs are scored as units further down.\n\n");
            out.append("It is still not real traffic, and it never becomes real traffic by being\n")
                    .append("harder.\n\n");
            return;
        }

        out.append("Neither column is real traffic. Both halves were written by somebody, and\n")
                .append("what separates them is only whether the writer knew about this evaluation.\n\n");
        out.append("`seeded` is text taken verbatim from the De-discussion campus forum app,\n")
                .append("where it exists to make a demo look inhabited: the right register and the\n")
                .append("right two languages, written before this system existed and therefore not\n")
                .append("shaped to suit it. Nobody posted it to a forum and nobody reported it. It is\n")
                .append("also almost entirely benign, because nobody seeds a demo with abuse.\n\n");
        out.append("`written for this` is the rest, and it is the weakest part of the dataset. It\n")
                .append("measures an engine against one person's idea of what a violation looks like.\n")
                .append("The violating classes are made of it because there was no honest alternative.\n")
                .append("Most of it deliberately avoids the wording in the rule term lists, since an\n")
                .append("engine that only has to recognise the words it was configured with is being\n")
                .append("asked nothing.\n\n");
        out.append("The two are scored separately below. A wide gap means the half written for\n")
                .append("this evaluation is the easier one, and the headline number is flattering by\n")
                .append("that much.\n\n");
    }

    private long count(List<SampleOutcome> outcomes, ModerationDecision decision, SampleProvenance provenance) {
        return outcomes.stream()
                .filter(outcome -> outcome.expected() == decision && outcome.provenance() == provenance)
                .count();
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

    /**
     * The precision of every number above, stated as a range rather than left for
     * the reader to assume there is none.
     */
    private void renderPrecision(StringBuilder out, BenchmarkReport report) {
        if (!report.repeated()) {
            out.append("## How much of this is noise\n\n");
            out.append("Each engine ran once, so this report cannot say. A provider's model is not a\n")
                    .append("pure function: an earlier run of `gemini-3.5-flash-lite/v1` on the\n")
                    .append("192-sample set scored macro-F1 0.636 where the run in this file's history\n")
                    .append("scored 0.617, with the same code, the same samples and `temperature: 0.0`.\n")
                    .append("Two hundredths is therefore the least a single-run difference has to be\n")
                    .append("before it means anything, and no number here is measured to that\n")
                    .append("precision.\n\n");
            out.append("Set `--campusguard.evaluation.runs=3` to replace that guess with a\n")
                    .append("measurement. It costs three times the model calls.\n\n");
            return;
        }

        out.append("## How much of this is noise\n\n");
        out.append("Every engine measured ").append(report.runCount())
                .append(" times on the same samples. `mean` is across runs and\n")
                .append("`range` is the widest observed minus the narrowest, which is this harness's\n")
                .append("own precision: **a gap between two engines narrower than their ranges is not\n")
                .append("a finding.**\n\n");

        out.append("| engine | runs | macro-F1 mean | range | ALLOW R | REMOVE R | ESCALATE R | changed answer |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");

        for (EngineRuns runs : report.repeats()) {
            if (runs.measured().isEmpty()) {
                out.append("| `").append(runs.engineName()).append("` | 0 | — | — | — | — | — | — |\n");
                continue;
            }
            EngineRuns.Instability instability = runs.instability();
            out.append("| `").append(runs.engineName()).append("` | ").append(runs.measured().size())
                    .append(" | ").append(decimal(runs.macroF1().mean()))
                    .append(" | ").append(plusMinus(runs.macroF1()))
                    .append(" | ").append(spread(runs.recall(ModerationDecision.ALLOW)))
                    .append(" | ").append(spread(runs.recall(ModerationDecision.REMOVE)))
                    .append(" | ").append(spread(runs.recall(ModerationDecision.ESCALATE)))
                    .append(" | ").append(instability.unstable()).append(" / ").append(instability.samples())
                    .append(" |\n");
        }
        out.append('\n');

        out.append("`changed answer` counts samples the engine did not answer identically every\n")
                .append("time. It is the same instability the range describes, at the granularity\n")
                .append("where it can be acted on: a score that holds steady because the same\n")
                .append("handful of samples flip in opposite directions is not the same thing as an\n")
                .append("engine that is steady, and only this column separates them.\n\n");

        for (EngineRuns runs : report.repeats()) {
            EngineRuns.Instability instability = runs.instability();
            if (instability.unstable() == 0 || !runs.repeated()) {
                continue;
            }
            out.append("`").append(runs.engineName()).append("` was inconsistent on: ")
                    .append(String.join(", ", instability.unstableSampleIds().stream()
                            .limit(MAX_ROWS_PER_SECTION)
                            .map(id -> "`" + id + "`")
                            .toList()));
            if (instability.unstable() > MAX_ROWS_PER_SECTION) {
                out.append(" and ").append(instability.unstable() - MAX_ROWS_PER_SECTION).append(" more");
            }
            out.append("\n\n");
        }

        out.append("The representative run below is the median by macro-F1, not the best and not\n")
                .append("the first. Every per-sample table, confusion matrix and comparison comes\n")
                .append("from that one run, so they agree with each other; an average of three\n")
                .append("matrices would correspond to no run that happened and could not be traced\n")
                .append("to a row in the CSV.\n\n");
    }

    /**
     * Pairs scored as units. Only drawn for a dataset that has them, because on
     * one that does not the whole section would be a table of zeros.
     */
    private void renderPairs(StringBuilder out, BenchmarkReport report) {
        boolean anyPairs = report.results().stream()
                .anyMatch(result -> result.pairScore().scored() > 0);
        if (!anyPairs) {
            return;
        }

        out.append("## Can it tell two near-identical posts apart\n\n");
        out.append("A minimal pair is one post written twice with a single deliberate difference,\n")
                .append("where the policy gives the two halves different answers. A pair counts as\n")
                .append("right only if both halves are.\n\n");
        out.append("This is the question per-sample accuracy cannot ask. An engine that answers by\n")
                .append("topic — anything about exams is suspicious, anything about lost keys is fine\n")
                .append("— gets one half of every pair right for free. That reads as roughly 50%\n")
                .append("accuracy on the samples and 0% on the pairs, and the second number is the\n")
                .append("one that says whether it understood the rule.\n\n");

        out.append("| engine | pairs | both right | one right | both wrong | pair accuracy |\n");
        out.append("|---|---|---|---|---|---|\n");

        for (EvaluationResult result : report.results()) {
            if (result.status() == EngineRunStatus.UNAVAILABLE) {
                continue;
            }
            EvaluationResult.PairScore score = result.pairScore();
            out.append("| `").append(result.engineName()).append("` | ").append(score.scored())
                    .append(" | ").append(score.bothRight())
                    .append(" | ").append(score.oneRight())
                    .append(" | ").append(score.bothWrong())
                    .append(" | ").append(decimal(score.accuracy()))
                    .append(" |\n");
        }
        out.append('\n');

        out.append("`one right` is the diagnostic column: it is the engine giving both halves the\n")
                .append("same answer, which is what keying on topic looks like from outside.\n\n");
    }

    private String spread(EngineRuns.Spread value) {
        return value.single()
                ? decimal(value.mean())
                : decimal(value.mean()) + " " + plusMinus(value);
    }

    /** A range written as a half-width, which is how a precision is usually read. */
    private String plusMinus(EngineRuns.Spread value) {
        return value.single() ? "—" : "±" + decimal(value.range() / 2);
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
                    .append(" more in the per-sample CSV beside this file._\n");
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

        renderBySource(out, result);
        renderMatrix(out, result);
        renderMisses(out, result);
    }

    private void renderBySource(StringBuilder out, EvaluationResult result) {
        ConfusionMatrix real = result.matrixFor(SampleProvenance.SEEDED);
        ConfusionMatrix authored = result.matrixFor(SampleProvenance.AUTHORED);

        if (real.total() == 0 || authored.total() == 0) {
            return;
        }

        out.append("Scored separately by where the samples came from:\n\n");
        out.append("| samples | n | accuracy | macro-F1 |\n|---|---|---|---|\n");
        out.append("| seeded | ").append(real.total()).append(" | ").append(decimal(real.accuracy()))
                .append(" | ").append(decimal(real.macroF1())).append(" |\n");
        out.append("| written for this | ").append(authored.total()).append(" | ")
                .append(decimal(authored.accuracy())).append(" | ").append(decimal(authored.macroF1()))
                .append(" |\n\n");

        double gap = authored.accuracy() - real.accuracy();
        boolean confounded = dominatedBySingleLabel(result, SampleProvenance.SEEDED)
                || dominatedBySingleLabel(result, SampleProvenance.AUTHORED);

        if (confounded) {
            // Saying "the written samples are harder" would be reading a class
            // imbalance as a difficulty difference. The two halves do not contain
            // the same mix of answers, so this split cannot separate the two.
            out.append("These two rows are **not comparable**. Each source is dominated by a single\n")
                    .append("expected answer — the real content is almost all ALLOW, the written content\n")
                    .append("almost all violations — so the difference between them measures the class\n")
                    .append("mix rather than the difficulty. An engine that answered ALLOW to everything\n")
                    .append("would score near 1.000 on the real half and near 0.000 on the written half\n")
                    .append("without knowing anything.\n\n")
                    .append("Fixing this needs violating content drawn from real traffic, which a seeded\n")
                    .append("demo application does not contain. It is the honest limit of this dataset.\n\n");
            return;
        }

        if (Math.abs(gap) >= 0.10) {
            out.append("The written samples are ").append(decimal(Math.abs(gap)))
                    .append(gap > 0 ? " easier" : " harder")
                    .append(" than the real ones by accuracy. Read the headline number with that in mind.\n\n");
        }
    }

    /**
     * Whether one source is so dominated by a single expected answer that scoring
     * it separately says more about the label mix than about the engine.
     */
    private boolean dominatedBySingleLabel(EvaluationResult result, SampleProvenance provenance) {
        List<SampleOutcome> subset = result.outcomes().stream()
                .filter(outcome -> outcome.provenance() == provenance)
                .toList();

        if (subset.isEmpty()) {
            return false;
        }

        long largest = subset.stream()
                .collect(java.util.stream.Collectors.groupingBy(SampleOutcome::expected, java.util.stream.Collectors.counting()))
                .values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);

        return (double) largest / subset.size() >= 0.80;
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
                    .append(" more in the per-sample CSV beside this file._\n");
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
        out.append("Add `--campusguard.evaluation.runs=3` to measure each engine three times and\n")
                .append("get a range next to every mean. It costs three times the model calls, and it\n")
                .append("is the difference between a comparison and an anecdote.\n\n");
        out.append("Omitting `--campusguard.evaluation.dataset` uses the bundled starter set. Every\n")
                .append("registered engine runs; one that is unavailable is reported and skipped\n")
                .append("rather than ending the run. Machine-readable output lands beside this file as\n")
                .append("a machine-readable report and every per-sample answer, under the same stem\n")
                .append("(`--campusguard.evaluation.output-prefix`).\n\n");
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
