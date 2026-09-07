package com.campusguard.moderation.investigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the same question more than once and reports what the answers agreed on.
 *
 * <p>The point is not the majority vote, which changes little: the investigation
 * set measures stability at 0.938, so most cases would have come back the same
 * anyway. The point is what the disagreement is worth once it is counted.
 *
 * <p>{@link EvidenceStrength} has been a number the model reported about itself
 * ever since it existed. First it was a confidence, and it was 0.85 in seven
 * briefs out of nine; then it was a band, which stopped it being a habit but
 * left it a claim. Three runs turn it into a measurement: three answers that
 * agree are settled, two out of three lean, and a three-way split is a case the
 * assistant genuinely could not decide — which is the most useful thing it can
 * say and the one thing it could never say honestly while grading itself.
 *
 * <p>Costs what it says on the tin. Three investigations per click at roughly two
 * thousand prompt tokens each, run in parallel so a reviewer waits no longer
 * than for one.
 */
public class ConsensusInvestigator implements Investigator {

    private static final Logger log = LoggerFactory.getLogger(ConsensusInvestigator.class);

    private final Investigator delegate;
    private final int runs;
    private final ExecutorService executor;

    public ConsensusInvestigator(Investigator delegate, int runs) {
        this.delegate = delegate;
        this.runs = runs;

        // Virtual threads: these are almost entirely waiting on a model, which is
        // the case they exist for. The circuit breaker underneath is shared, so
        // three concurrent runs against a failing provider still fail fast rather
        // than three times slowly.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public InvestigationBrief investigate(UUID caseId) {
        List<InvestigationBrief> briefs = runAll(caseId);

        List<InvestigationBrief.Complete> completed = briefs.stream()
                .filter(InvestigationBrief.Complete.class::isInstance)
                .map(InvestigationBrief.Complete.class::cast)
                .toList();

        if (completed.isEmpty()) {
            // Nothing concluded. The first answer is as good as any and says what
            // went wrong, which is what a reviewer needs — not a summary of three
            // identical failures.
            return briefs.getFirst();
        }

        InvestigationBrief.Complete winner = winnerOf(completed);
        EvidenceStrength measured = strengthOf(completed, winner);

        if (measured != winner.evidenceStrength()) {
            log.debug("Case {}: the model called it {} and {} runs made it {}.",
                    caseId, winner.evidenceStrength(), completed.size(), measured);
        }

        // The winning brief's own prose and citations, with only the band
        // replaced. Splicing a summary from one run onto a recommendation from
        // another would produce reasoning that never argued for its conclusion.
        return new InvestigationBrief.Complete(
                winner.summary(),
                winner.recommendation(),
                measured,
                winner.counterEvidence(),
                winner.citedCaseIds());
    }

    private List<InvestigationBrief> runAll(UUID caseId) {
        List<Future<InvestigationBrief>> futures = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            futures.add(executor.submit(() -> delegate.investigate(caseId)));
        }

        List<InvestigationBrief> briefs = new ArrayList<>();
        for (Future<InvestigationBrief> future : futures) {
            try {
                briefs.add(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while investigating case " + caseId, ex);
            } catch (ExecutionException ex) {
                // One run failing is not the investigation failing. Recorded as a
                // brief so it counts against the vote rather than vanishing.
                log.warn("A run of case {} failed: {}", caseId, String.valueOf(ex.getCause()));
                briefs.add(new InvestigationBrief.Inconclusive(
                        "One attempt did not complete: " + describe(ex.getCause())));
            }
        }

        return briefs;
    }

    /**
     * The brief carrying the answer given most often.
     *
     * <p>Ties go to the more lenient outcome, on the same principle as everything
     * else here: an assistant that cannot decide should not be the thing pushing
     * a case towards action. {@code FinalAction} is declared from least to most
     * severe, so the earlier ordinal wins.
     */
    private InvestigationBrief.Complete winnerOf(List<InvestigationBrief.Complete> completed) {
        Map<com.campusguard.moderation.FinalAction, Integer> votes = new HashMap<>();
        completed.forEach(brief -> votes.merge(brief.recommendation(), 1, Integer::sum));

        com.campusguard.moderation.FinalAction winner = votes.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<com.campusguard.moderation.FinalAction, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().ordinal(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();

        return completed.stream()
                .filter(brief -> brief.recommendation() == winner)
                .findFirst()
                .orElseThrow();
    }

    /**
     * How far the runs agreed, which is what the reviewer is being told.
     *
     * <p>Measured over the runs that concluded. A run that failed has already
     * been counted against the vote by not being here; counting it a second time
     * as disagreement would report a slow provider as an ambiguous case.
     */
    private EvidenceStrength strengthOf(
            List<InvestigationBrief.Complete> completed, InvestigationBrief.Complete winner) {

        long agreeing = completed.stream()
                .filter(brief -> brief.recommendation() == winner.recommendation())
                .count();

        if (agreeing == completed.size() && completed.size() == runs) {
            return EvidenceStrength.SETTLED;
        }
        return agreeing * 2 > completed.size() ? EvidenceStrength.LEANING : EvidenceStrength.OPEN;
    }

    private String describe(Throwable cause) {
        return cause == null ? "unknown" : Objects.requireNonNullElse(cause.getMessage(), cause.toString());
    }
}
