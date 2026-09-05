package com.campusguard.moderation.investigation;

import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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

    private final ModerationCaseRepository cases;

    public SimilarResolvedCasesTool(ModerationCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "similarResolvedCases",
                "Up to " + LIMIT + " past cases closed under a given rule with an outcome, most recent "
                        + "first, showing what reviewers actually did. Reports that were dismissed are not "
                        + "included. Call this when you want to know how this kind of case is normally handled.",
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

        return Output.of(new Precedents(ruleCode, precedents.size(), precedents), disclosed);
    }

    record Precedents(String ruleCode, int count, List<Precedent> cases) {
    }

    record Precedent(
            String caseId, List<String> ruleCodes, String finalAction, String rationale, Instant decidedAt) {
    }
}
