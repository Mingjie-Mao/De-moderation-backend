package com.campusguard.moderation.investigation;

import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How reviewers have actually enforced a rule, as opposed to what it says.
 *
 * <p>The two diverge, and the gap is the useful part. A rule marked HIGH whose
 * cases are almost all closed with a hide is telling a reviewer something no
 * amount of prompt wording could.
 *
 * <p>The row limit is fixed here rather than exposed as a parameter. A model
 * given the choice will ask for more than it needs, and every extra precedent is
 * paid for twice — once to fetch it and again in the context of every subsequent
 * step.
 */
@Component
public class SimilarResolvedCasesTool implements InvestigationTool {

    private static final int LIMIT = 5;

    /** The same window the author's history uses, so two pieces of evidence in one brief cover one period. */
    private static final int WINDOW_DAYS = 90;

    private final ModerationCaseRepository cases;

    public SimilarResolvedCasesTool(ModerationCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "similarResolvedCases",
                "How this rule is normally handled: up to " + LIMIT + " past cases closed with an outcome, "
                        + "most recent first, together with how often reports under this rule were dismissed "
                        + "outright. The listed cases are only ones where something was done — the dismissal "
                        + "rate is the other half of the picture and is given as a number.",
                ToolSpec.oneRequiredString("ruleCode", "The rule code to look up precedent for, for example ABUSE."));
    }

    @Override
    public Output run(UUID caseId, JsonNode arguments) {
        String ruleCode = ToolArguments.requiredString(arguments, "ruleCode").toUpperCase(java.util.Locale.ROOT);

        // One extra row, so that dropping the case under investigation cannot
        // silently return four precedents where five were asked for.
        List<ModerationCase> found = cases.findResolvedByRuleCode(ruleCode, LIMIT + 1);

        Set<UUID> disclosed = new LinkedHashSet<>();
        List<Precedent> precedents = new ArrayList<>();

        for (ModerationCase other : found) {
            if (other.getId().equals(caseId) || precedents.size() == LIMIT) {
                continue;
            }

            // See AuthorHistoryTool: the disclosure set decides what the brief may
            // cite, so it is built where it can be read rather than inside a
            // pipeline that a later `limit` could short-circuit past.
            disclosed.add(other.getId());
            precedents.add(new Precedent(
                    other.getId().toString(),
                    other.getRuleCodes(),
                    other.getFinalAction() == null ? null : other.getFinalAction().name(),
                    other.getRationale(),
                    other.getDecidedAt()));
        }

        return Output.of(
                new Precedents(ruleCode, baseRate(ruleCode), precedents.size(), precedents), disclosed);
    }

    /**
     * How often this rule ends in nothing happening.
     *
     * <p>Without it the precedent list reads as unanimity — every row an action,
     * because rows that were not an action are excluded by design. A rule whose
     * reports are wrong half the time and one whose reports are always right
     * produced identical-looking evidence, and the benchmark showed what that
     * costs: ordinary student content, a clean author, and a takedown recommended
     * three times out of three.
     *
     * <p>A rate, not rows. A dismissal is evidence about a report rather than a
     * precedent for an outcome, and listing them beside the precedents would
     * blur a distinction the rest of this feature is careful about.
     */
    private DismissalRate baseRate(String ruleCode) {
        long dismissed = 0;
        long total = 0;

        for (Object[] row : cases.countOutcomesByRuleCode(
                ruleCode, Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS))) {

            long count = ((Number) row[1]).longValue();
            total += count;
            if (Boolean.TRUE.equals(row[0])) {
                dismissed = count;
            }
        }

        return new DismissalRate(dismissed, total, WINDOW_DAYS);
    }

    /**
     * @param dismissed reports under this rule that a reviewer closed with no
     *     action, meaning they judged the report itself mistaken
     * @param resolved every case under this rule that reached an outcome, the
     *     dismissed ones included
     */
    record DismissalRate(long dismissed, long resolved, int windowDays) {
    }

    record Precedents(String ruleCode, DismissalRate dismissalRate, int count, List<Precedent> cases) {
    }

    record Precedent(
            String caseId, List<String> ruleCodes, String finalAction, String rationale, Instant decidedAt) {
    }
}
