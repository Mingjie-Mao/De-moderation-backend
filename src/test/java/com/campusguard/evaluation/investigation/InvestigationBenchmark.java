package com.campusguard.evaluation.investigation;

import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.investigation.EvidenceStrength;
import com.campusguard.moderation.investigation.Investigator;
import com.campusguard.moderation.investigation.InvestigationBrief;
import com.campusguard.moderation.engine.ai.AiInvocation;
import com.campusguard.moderation.engine.ai.AiInvocationRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the investigation set and scores it.
 *
 * <p>Each scenario is asked more than once on purpose. The engine's benchmark
 * asks each sample once because a classification at temperature zero is close
 * enough to a function of its input; an investigation is not. Two runs over
 * identical cases have returned BAN and HIDE, and the only way to know how often
 * that happens is to ask repeatedly and count.
 *
 * <p>A fresh fixture per run rather than one fixture asked several times, so a
 * repeat is a genuine repeat: the assistant caches nothing here, but the case
 * ids would be the same and a coincidence in one run could not be told from a
 * pattern.
 */
@Service
public class InvestigationBenchmark {

    private static final Logger log = LoggerFactory.getLogger(InvestigationBenchmark.class);

    private final ScenarioFixture fixture;
    private final AiInvocationRepository invocations;

    public InvestigationBenchmark(ScenarioFixture fixture, AiInvocationRepository invocations) {
        this.fixture = fixture;
        this.invocations = invocations;
    }

    public InvestigationBenchmarkReport run(
            Investigator investigator,
            List<InvestigationScenario> scenarios,
            int runsEach,
            String promptVersion,
            String model) {
        return run(investigator, scenarios, runsEach, promptVersion, model, Duration.ZERO);
    }

    /**
     * @param minInterval the shortest time from the start of one investigation to
     *     the start of the next, for the reason {@code EvaluationRunner} paces its
     *     samples: a set asked back to back is a burst no reviewer produces, and a
     *     free-tier key answers it by spending retries on the per-minute ceiling.
     *     With runs above one every investigation is that many calls at once.
     */
    public InvestigationBenchmarkReport run(
            Investigator investigator,
            List<InvestigationScenario> scenarios,
            int runsEach,
            String promptVersion,
            String model,
            Duration minInterval) {

        Pacer pacer = new Pacer(minInterval);
        Map<EvidenceStrength, InvestigationBenchmarkReport.BandTally> bands = InvestigationBenchmarkReport.emptyBands();
        List<InvestigationBenchmarkReport.ScenarioOutcome> outcomes = new ArrayList<>();

        for (InvestigationScenario scenario : scenarios) {
            outcomes.add(score(investigator, scenario, runsEach, pacer, bands));
            log.info("scenario {} done ({})", scenario.id(), outcomes.getLast().modal());
        }

        return aggregate(outcomes, scenarios, runsEach, promptVersion, model, bands);
    }

    private InvestigationBenchmarkReport.ScenarioOutcome score(
            Investigator investigator,
            InvestigationScenario scenario,
            int runsEach,
            Pacer pacer,
            Map<EvidenceStrength, InvestigationBenchmarkReport.BandTally> bands) {

        List<FinalAction> recommendations = new ArrayList<>();
        List<EvidenceStrength> evidence = new ArrayList<>();
        List<String> briefs = new ArrayList<>();
        List<Integer> steps = new ArrayList<>();
        List<Integer> tokens = new ArrayList<>();
        int grounded = 0;

        for (int run = 0; run < runsEach; run++) {
            ScenarioFixture.Built built = fixture.build(scenario);

            pacer.await();
            InvestigationBrief brief = investigator.investigate(built.caseId());

            List<AiInvocation> rows = invocations.findAll().stream()
                    .filter(row -> row.getCaseId().equals(built.caseId()))
                    .toList();
            steps.add(rows.size());
            tokens.add(rows.stream()
                    .map(AiInvocation::getPromptTokens)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum());

            if (brief instanceof InvestigationBrief.Complete complete) {
                recommendations.add(complete.recommendation());
                evidence.add(complete.evidenceStrength());
                briefs.add(complete.summary());

                // Per run rather than per scenario: the question is whether a brief
                // that says SETTLED is right more often than one that says LEANING,
                // and a modal answer has no band of its own.
                boolean defensible = scenario.acceptable().contains(complete.recommendation());
                bands.computeIfPresent(complete.evidenceStrength(), (band, tally) -> tally.plus(defensible));

                Set<UUID> required = built.required(scenario.mustCite());
                if (complete.citedCaseIds().containsAll(required)) {
                    grounded++;
                }
            } else {
                // An investigation that did not conclude is not a wrong answer, but
                // it is not a right one either, and averaging it away would flatter
                // a run where the provider was struggling.
                recommendations.add(null);
                evidence.add(null);
                briefs.add(brief.summary());
            }
        }

        FinalAction modal = modeOf(recommendations);
        long matchingModal = recommendations.stream().filter(action -> action == modal).count();

        return new InvestigationBenchmarkReport.ScenarioOutcome(
                scenario.id(),
                scenario.shape(),
                scenario.expected(),
                recommendations,
                evidence,
                modal,
                modal != null && scenario.acceptable().contains(modal),
                grounded == runsEach,
                grounded,
                (double) matchingModal / runsEach,
                steps.stream().mapToInt(Integer::intValue).average().orElse(0),
                tokens.stream().mapToInt(Integer::intValue).average().orElse(0),
                List.copyOf(briefs));
    }

    /**
     * The answer given most often, and null when nothing was given at all.
     *
     * <p>Ties go to the more lenient outcome, on the same principle the loop uses
     * when it cannot decide: an assistant that is split should not be the thing
     * pushing a case towards action.
     */
    private FinalAction modeOf(List<FinalAction> recommendations) {
        Map<FinalAction, Integer> counts = new HashMap<>();
        recommendations.stream()
                .filter(Objects::nonNull)
                .forEach(action -> counts.merge(action, 1, Integer::sum));

        return counts.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<FinalAction, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().ordinal(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private InvestigationBenchmarkReport aggregate(
            List<InvestigationBenchmarkReport.ScenarioOutcome> outcomes,
            List<InvestigationScenario> scenarios,
            int runsEach,
            String promptVersion,
            String model,
            Map<EvidenceStrength, InvestigationBenchmarkReport.BandTally> bands) {

        Map<FinalAction, Integer> recommended = InvestigationBenchmarkReport.emptyTally();
        Map<FinalAction, Integer> expected = InvestigationBenchmarkReport.emptyTally();

        outcomes.stream()
                .map(InvestigationBenchmarkReport.ScenarioOutcome::modal)
                .filter(Objects::nonNull)
                .forEach(action -> recommended.merge(action, 1, Integer::sum));
        scenarios.stream()
                .map(InvestigationScenario::expected)
                .filter(Objects::nonNull)
                .forEach(action -> expected.merge(action, 1, Integer::sum));

        int total = outcomes.size();
        Set<String> unanimous = outcomes.stream()
                .filter(outcome -> outcome.stability() == 1.0 && outcome.modal() != null)
                .map(InvestigationBenchmarkReport.ScenarioOutcome::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        return new InvestigationBenchmarkReport(
                promptVersion,
                model,
                total,
                runsEach,
                fraction(outcomes.stream().filter(InvestigationBenchmarkReport.ScenarioOutcome::acceptable).count(), total),
                fraction(outcomes.stream().filter(outcome -> outcome.modal() == outcome.expected()).count(), total),
                outcomes.stream().mapToDouble(InvestigationBenchmarkReport.ScenarioOutcome::stability).average().orElse(0),
                unanimous.size(),
                fraction(outcomes.stream().filter(InvestigationBenchmarkReport.ScenarioOutcome::grounded).count(), total),
                outcomes.stream().mapToDouble(InvestigationBenchmarkReport.ScenarioOutcome::averageSteps).average().orElse(0),
                outcomes.stream().mapToDouble(InvestigationBenchmarkReport.ScenarioOutcome::averagePromptTokens).average().orElse(0),
                bands,
                recommended,
                expected,
                List.copyOf(outcomes));
    }

    private double fraction(long matched, int total) {
        return total == 0 ? 0 : (double) matched / total;
    }

    /** Holds investigations a minimum interval apart, measured start to start. */
    private static final class Pacer {

        private final long intervalNanos;
        private long lastStart;

        Pacer(Duration interval) {
            this.intervalNanos = interval.toNanos();
        }

        void await() {
            if (intervalNanos > 0 && lastStart != 0) {
                long wait = intervalNanos - (System.nanoTime() - lastStart);
                if (wait > 0) {
                    try {
                        Thread.sleep(Duration.ofNanos(wait));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            lastStart = System.nanoTime();
        }
    }
}
