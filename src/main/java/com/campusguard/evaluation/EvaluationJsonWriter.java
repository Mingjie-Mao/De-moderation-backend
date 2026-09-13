package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ai.EngineInvocationStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The machine-readable form of a run.
 *
 * <p>Built as an explicit shape rather than by serialising the domain objects.
 * Reflecting over them would make the file's format a side effect of internal
 * class structure, so a refactor would silently break anything reading it —
 * including a comparison against an earlier run, which is the main reason this
 * file exists.
 */
@Component
public class EvaluationJsonWriter {

    private final ObjectMapper objectMapper;

    public EvaluationJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(BenchmarkReport report) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("generatedAt", report.generatedAt().toString());
        root.put("dataset", report.datasetName());
        root.put("sampleCount", report.sampleCount());
        root.put("starterDataset", report.starterDataset());
        root.put("runs", report.runCount());
        root.put("engines", report.results().stream().map(this::engine).toList());
        root.put("stability", report.repeats().stream().map(this::stability).toList());
        root.put("comparisons", report.comparisons().stream().map(this::comparison).toList());
        root.put("production", report.productionStats().stream().map(this::production).toList());

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    private Map<String, Object> engine(EvaluationResult result) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("engine", result.engineName());
        node.put("status", result.status().name());
        node.put("unavailableReason", result.unavailableReason());
        node.put("sampleCount", result.sampleCount());
        node.put("judgedCount", result.judgedCount());
        node.put("errorCount", result.errorCount());

        if (result.status() != EngineRunStatus.UNAVAILABLE) {
            node.put("accuracy", result.matrix().accuracy());
            node.put("macroF1", result.matrix().macroF1());
            node.put("macroPrecision", result.matrix().macroPrecision());
            node.put("macroRecall", result.matrix().macroRecall());
            node.put("meanLatencyMs", result.meanMillis());
            node.put("p50LatencyMs", result.percentileMillis(50));
            node.put("p95LatencyMs", result.percentileMillis(95));
            node.put("promptTokens", result.totalPromptTokens().isPresent() ? result.totalPromptTokens().getAsInt() : null);
            node.put(
                    "completionTokens",
                    result.totalCompletionTokens().isPresent() ? result.totalCompletionTokens().getAsInt() : null);
            node.put("estimatedCost", result.estimatedCost() == null ? null : result.estimatedCost().toPlainString());
            node.put("perDecision", perDecision(result));
            node.put("pairs", pairs(result));
            node.put("confusion", confusion(result));
        }

        return node;
    }

    private Map<String, Object> pairs(EvaluationResult result) {
        EvaluationResult.PairScore score = result.pairScore();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("scored", score.scored());
        node.put("bothRight", score.bothRight());
        node.put("oneRight", score.oneRight());
        node.put("bothWrong", score.bothWrong());
        node.put("incomplete", score.incomplete());
        node.put("accuracy", score.accuracy());
        return node;
    }

    /**
     * The spread across runs, kept in its own block rather than merged into the
     * engine node. The engine node describes one run and has to stay traceable to
     * the CSV; this describes the set of runs, and mixing the two would make it
     * impossible to tell which of the numbers came from the representative run.
     */
    private Map<String, Object> stability(EngineRuns runs) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("engine", runs.engineName());
        node.put("runs", runs.measured().size());
        node.put("macroF1", spread(runs.macroF1()));
        node.put("accuracy", spread(runs.accuracy()));
        node.put("pairAccuracy", spread(runs.pairAccuracy()));
        node.put("meanLatencyMs", spread(runs.meanLatencyMillis()));
        node.put("promptTokensPerSample", spread(runs.promptTokensPerSample()));

        Map<String, Object> recall = new LinkedHashMap<>();
        for (ModerationDecision decision : ModerationDecision.values()) {
            recall.put(decision.name(), spread(runs.recall(decision)));
        }
        node.put("recall", recall);

        EngineRuns.Instability instability = runs.instability();
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("samples", instability.samples());
        changed.put("unstable", instability.unstable());
        changed.put("rate", instability.rate());
        changed.put("sampleIds", instability.unstableSampleIds());
        node.put("changedAnswer", changed);

        return node;
    }

    private Map<String, Object> spread(EngineRuns.Spread value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("mean", value.mean());
        node.put("min", value.min());
        node.put("max", value.max());
        node.put("observations", value.observations());
        return node;
    }

    private List<Map<String, Object>> perDecision(EvaluationResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfusionMatrix.ClassMetrics metrics : result.matrix().allMetrics()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("decision", metrics.label().name());
            row.put("support", metrics.support());
            row.put("truePositives", metrics.truePositives());
            row.put("falsePositives", metrics.falsePositives());
            row.put("falseNegatives", metrics.falseNegatives());
            row.put("precision", metrics.precision());
            row.put("recall", metrics.recall());
            row.put("f1", metrics.f1());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> confusion(EvaluationResult result) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (ModerationDecision actual : result.matrix().labels()) {
            for (ModerationDecision predicted : result.matrix().labels()) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("actual", actual.name());
                cell.put("predicted", predicted.name());
                cell.put("count", result.matrix().count(actual, predicted));
                cells.add(cell);
            }
        }
        return cells;
    }

    private Map<String, Object> comparison(EngineComparator.Comparison comparison) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("baseline", comparison.baselineEngine());
        node.put("candidate", comparison.candidateEngine());
        node.put("bothRight", comparison.bothRight());
        node.put("candidateFixed", comparison.candidateFixed().stream().map(this::delta).toList());
        node.put("candidateBroke", comparison.candidateBroke().stream().map(this::delta).toList());
        node.put("bothWrong", comparison.bothWrong().stream().map(this::delta).toList());
        node.put("netGain", comparison.netGain());
        return node;
    }

    private Map<String, Object> delta(EngineComparator.SampleDelta delta) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("sampleId", delta.sampleId());
        node.put("category", delta.category());
        node.put("expected", delta.expected().name());
        node.put("baselineSaid", delta.baselineSaid().name());
        node.put("candidateSaid", delta.candidateSaid().name());
        node.put("excerpt", delta.excerpt());
        return node;
    }

    private Map<String, Object> production(EngineInvocationStats stats) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("engine", stats.getEngine());
        node.put("calls", stats.getCalls());
        node.put("successes", stats.getSuccesses());
        node.put("failures", stats.getFailures());
        node.put("avgLatencyMs", stats.getAvgLatencyMs());
        node.put("p95LatencyMs", stats.getP95LatencyMs());
        node.put("promptTokens", stats.getPromptTokens());
        node.put("completionTokens", stats.getCompletionTokens());
        return node;
    }
}
