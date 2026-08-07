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
 * <p>Ordering matters and is not alphabetical. The first engine is the baseline
 * every other is compared against, and the rule engine is the honest floor: the
 * question a model has to answer is not "is it good" but "is it better than what
 * was already there, and by enough to justify the latency and the bill".
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

        List<EngineComparator.Comparison> comparisons = new ArrayList<>();
        if (results.size() > 1) {
            EvaluationResult baseline = results.getFirst();
            for (EvaluationResult candidate : results.subList(1, results.size())) {
                if (baseline.status() == EngineRunStatus.UNAVAILABLE
                        || candidate.status() == EngineRunStatus.UNAVAILABLE) {
                    continue;
                }
                comparisons.add(comparator.compare(baseline, candidate));
            }
        }

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
