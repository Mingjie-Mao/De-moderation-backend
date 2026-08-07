package com.campusguard.evaluation;

import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ai.AiInvocationRepository;
import com.campusguard.moderation.engine.ai.EngineInvocationStats;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the whole benchmark: every engine over one dataset, then everything
 * derived from the results.
 *
 * <p>Ordering matters. The first engine is the baseline every other is compared
 * against, so the rule engine is forced there and not left to sort where it may:
 * the question a model has to answer is not "is it good" but "is it better than
 * what was already there, and by enough to justify the latency and the bill".
 * The rest follow by name, which is enough to keep successive versions of one
 * prompt adjacent.
 */
@Service
public class BenchmarkService {

    private final EvaluationDataset datasets;
    private final EvaluationRunner runner;
    private final EngineComparator comparator;
    private final EngineRegistry engines;
    private final AiInvocationRepository invocations;

    public BenchmarkService(
            EvaluationDataset datasets,
            EvaluationRunner runner,
            EngineComparator comparator,
            EngineRegistry engines,
            AiInvocationRepository invocations) {
        this.datasets = datasets;
        this.runner = runner;
        this.comparator = comparator;
        this.engines = engines;
        this.invocations = invocations;
    }

    @Transactional(readOnly = true)
    public BenchmarkReport run(String datasetPath, List<String> requestedEngines) throws Exception {
        EvaluationDataset.Loaded dataset = datasets.load(datasetPath);
        List<String> engineNames = resolveEngines(requestedEngines);

        List<EvaluationResult> results = new ArrayList<>();
        for (String engineName : engineNames) {
            // Each engine is independent. One that cannot run is recorded as such
            // and the rest of the benchmark continues, because a missing API key
            // should not cost the baseline numbers too.
            results.add(runner.run(engineName, dataset));
        }

        List<EngineComparator.Comparison> comparisons = comparePairs(results);

        List<EngineInvocationStats> production = invocations.statsByEngine();

        return new BenchmarkReport(
                Instant.now(),
                dataset.name(),
                dataset.samples().size(),
                dataset.starter(),
                results,
                comparisons,
                production);
    }

    /**
     * Two families of pair, not all of them.
     *
     * <p>Every engine against the baseline answers "is this worth using at all",
     * which is the question that decides adoption. Every engine against the one
     * before it answers "did this change help", which is the question that decides
     * the next one — and for two versions of a prompt it is the only pair that
     * isolates the wording, since everything else about them is identical.
     *
     * <p>That second family leans on engines being ordered by name: {@code .../v1}
     * sorts before {@code .../v2}, so successive versions of one prompt land next
     * to each other. Across different models the neighbour is merely whatever
     * sorted there, and the baseline pair is the one to read instead. Worth
     * knowing before drawing a conclusion from a row.
     *
     * <p>All pairs would be the union plus a lot of comparisons nobody asked for,
     * and a report grows unreadable faster than it grows informative.
     */
    List<EngineComparator.Comparison> comparePairs(List<EvaluationResult> results) {
        List<EngineComparator.Comparison> comparisons = new ArrayList<>();

        for (int i = 1; i < results.size(); i++) {
            addComparison(comparisons, results.getFirst(), results.get(i));
        }
        // From the third onwards: the pair at i = 1 is already the baseline pair.
        for (int i = 2; i < results.size(); i++) {
            addComparison(comparisons, results.get(i - 1), results.get(i));
        }

        return comparisons;
    }

    /** An engine that never answered has nothing to compare, only a reason. */
    private void addComparison(
            List<EngineComparator.Comparison> into, EvaluationResult baseline, EvaluationResult candidate) {
        if (baseline.status() == EngineRunStatus.UNAVAILABLE
                || candidate.status() == EngineRunStatus.UNAVAILABLE) {
            return;
        }
        into.add(comparator.compare(baseline, candidate));
    }

    /**
     * Defaults to every registered engine with the fallback first, so the
     * comparison is always drawn against the floor rather than against whichever
     * engine happened to sort first.
     */
    private List<String> resolveEngines(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }

        List<String> ordered = new ArrayList<>();
        ordered.add(engines.fallback().name());
        engines.names().stream().filter(name -> !ordered.contains(name)).forEach(ordered::add);
        return ordered;
    }
}
