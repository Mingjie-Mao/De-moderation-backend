package com.campusguard.moderation.investigation;

import com.campusguard.moderation.ContentLocator;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
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
 * What has already been decided about the person who wrote this.
 *
 * <p>The one thing a reviewer most wants and the schema could not give them.
 * Whether a comment deserves a hide or a ban usually turns on whether it is the
 * first or the fourth, and until this existed that question had no answer short
 * of reading the database by hand.
 *
 * <p>Takes no arguments. The author is resolved from the case under
 * investigation, so there is no way to point this at anybody else — an
 * assistant that could walk from any case to any account's history is a much
 * larger thing than the one this is.
 */
@Component
public class AuthorHistoryTool implements InvestigationTool {

    /**
     * Long enough to catch a pattern, short enough that a reformed account is not
     * judged forever on a first year it has since left behind.
     */
    private static final int WINDOW_DAYS = 90;

    private final ModerationCaseRepository cases;
    private final ContentLocator contentLocator;
    private final UserRepository users;

    public AuthorHistoryTool(
            ModerationCaseRepository cases, ContentLocator contentLocator, UserRepository users) {
        this.cases = cases;
        this.contentLocator = contentLocator;
        this.users = users;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "authorHistory",
                "Moderation decisions already made about the author of the content in this case, "
                        + "over the last " + WINDOW_DAYS + " days, across all of their posts and comments. "
                        + "Call this when whether an outcome should be escalated depends on whether this "
                        + "is a first offence.",
                ToolSpec.noArguments());
    }

    @Override
    public Output run(UUID caseId, JsonNode arguments) {
        ModerationCase subject = cases.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case %s no longer exists.".formatted(caseId)));

        UUID authorId = contentLocator
                .authorOf(subject.getTargetType(), subject.getTargetId())
                .orElse(null);

        if (authorId == null) {
            // The content is gone entirely, rather than merely hidden. Said
            // plainly, because a model handed an empty history would otherwise
            // read it as a clean record.
            return Output.withoutCases(History.unavailable(
                    "The content in this case no longer exists, so its author cannot be identified. "
                            + "This is not evidence of a clean record."));
        }

        List<UUID> targetIds = contentLocator.targetsOf(authorId).stream()
                .map(ContentLocator.TargetRef::id)
                .toList();

        // An author with nothing left on the site reaches the query with an empty
        // list, and Hibernate renders that as `in ()`, which PostgreSQL rejects.
        List<ModerationCase> resolved = targetIds.isEmpty()
                ? List.of()
                : cases.findResolvedForTargets(targetIds, Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS));

        User author = users.findById(authorId).orElse(null);

        Set<UUID> disclosed = new LinkedHashSet<>();
        List<Decision> decisions = new ArrayList<>();

        for (ModerationCase other : resolved) {
            // The case being investigated is not its own precedent. It is also not
            // resolved yet, so this only ever bites on a re-investigation.
            if (other.getId().equals(caseId)) {
                continue;
            }

            // A loop rather than a stream with peek: what a tool disclosed governs
            // what the brief may cite, and a side effect hidden in the middle of a
            // pipeline is one `limit` away from silently not running.
            disclosed.add(other.getId());
            decisions.add(new Decision(
                    other.getId().toString(),
                    other.getTargetType().name(),
                    other.getRuleCodes(),
                    other.getFinalAction() == null ? null : other.getFinalAction().name(),
                    other.getDecidedAt()));
        }

        History history = new History(
                author == null ? null : author.getUsername(),
                author == null ? null : author.getStatus().name(),
                author == null ? null : author.getCreatedAt(),
                WINDOW_DAYS,
                decisions.size(),
                decisions,
                null);

        return Output.of(history, disclosed);
    }

    /**
     * @param note only set when the history could not be assembled. Present in the
     *     shape either way so the model does not have to infer meaning from a
     *     missing field.
     */
    record History(
            String username,
            String accountStatus,
            Instant accountCreatedAt,
            int windowDays,
            int resolvedCaseCount,
            List<Decision> decisions,
            String note) {

        static History unavailable(String note) {
            return new History(null, null, null, WINDOW_DAYS, 0, List.of(), note);
        }
    }

    record Decision(
            String caseId, String targetType, List<String> ruleCodes, String finalAction, Instant decidedAt) {
    }
}
