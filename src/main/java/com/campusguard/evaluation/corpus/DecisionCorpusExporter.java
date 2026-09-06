package com.campusguard.evaluation.corpus;

import com.campusguard.appeal.Appeal;
import com.campusguard.appeal.AppealRepository;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.audit.AuditLogger;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.ContentLocator;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns decisions already made into a corpus.
 *
 * <p>This project measures its engines against 192 samples that were written for
 * the purpose. That set is what made the v1-to-v2 prompt rewrite checkable, and
 * it has a ceiling: it says how a model does on content somebody imagined, not
 * on this forum's content judged by this forum's moderators.
 *
 * <p>The second kind of data has been accumulating in the database since the
 * first case was decided, unread. Every resolved case pairs content with an
 * engine's opinion and a person's action, and the appeal and revision trails say
 * which of those a person later thought was wrong.
 *
 * <p>Exported rather than queried in place, because the uses are elsewhere: a
 * retrieval corpus of decided cases, an evaluation set drawn from real content,
 * and eventually a training set. None of them is worth building yet. The export
 * is worth having now, because a corpus that nobody started keeping cannot be
 * recovered later.
 */
@Service
public class DecisionCorpusExporter {

    /**
     * Longer than this and the case is treated as one somebody had to think about.
     *
     * <p>A blunt proxy, and labelled as one. Its purpose is to surface candidates
     * for an ESCALATE label — the class the corpus structurally cannot derive —
     * not to assert that a slow decision was a hard one. A reviewer who went to
     * lunch looks the same from here.
     */
    private static final Duration DELIBERATED = Duration.ofHours(2);

    private final ModerationCaseRepository cases;
    private final ContentLocator contentLocator;
    private final AuditEntryRepository auditEntries;
    private final AppealRepository appeals;

    public DecisionCorpusExporter(
            ModerationCaseRepository cases,
            ContentLocator contentLocator,
            AuditEntryRepository auditEntries,
            AppealRepository appeals) {
        this.cases = cases;
        this.contentLocator = contentLocator;
        this.auditEntries = auditEntries;
        this.appeals = appeals;
    }

    /**
     * @param limit how many resolved cases to take, oldest first. Paged rather
     *     than streamed because this runs as a command against a database that is
     *     not serving traffic, and a bounded read is easier to reason about than
     *     a cursor that has to survive one.
     */
    @Transactional(readOnly = true)
    public List<DecisionSample> export(int limit) {
        List<ModerationCase> resolved = cases.findByStatus(CaseStatus.RESOLVED, PageRequest.of(0, limit));

        // One query for every appeal in the batch rather than one per case. The
        // corpus is read whole, and an N+1 over a few thousand rows is the kind of
        // slowness that gets a useful tool quietly abandoned.
        Map<UUID, Appeal> appealsByCase = appeals.findAll().stream()
                .filter(appeal -> appeal.getModerationCase() != null)
                .collect(Collectors.toMap(
                        appeal -> appeal.getModerationCase().getId(),
                        Function.identity(),
                        (first, second) -> second));

        List<DecisionSample> samples = new ArrayList<>();
        for (ModerationCase moderationCase : resolved) {
            toSample(moderationCase, appealsByCase.get(moderationCase.getId())).ifPresent(samples::add);
        }

        return List.copyOf(samples);
    }

    private Optional<DecisionSample> toSample(ModerationCase moderationCase, Appeal appeal) {
        // Content the author hard-deleted is gone for good, and a row with no text
        // teaches nothing. Skipped rather than exported empty, so a count of rows
        // is a count of usable ones.
        Optional<ContentLocator.ModeratedContent> content = contentLocator.findIncludingRemoved(
                moderationCase.getTargetType(), moderationCase.getTargetId());
        if (content.isEmpty()) {
            return Optional.empty();
        }

        ContentLocator.ModeratedContent moderated = content.get();
        FinalAction action = moderationCase.getFinalAction();
        ModerationDecision label = DecisionSample.labelFor(action);

        boolean revised = auditEntries
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                        moderationCase.getTargetType(), moderationCase.getTargetId())
                .stream()
                .anyMatch(entry -> AuditLogger.CASE_DECISION_REVISED.equals(entry.getAction()));

        boolean engineAgreed = moderationCase.getDecision() != null && moderationCase.getDecision() == label;
        long minutes = moderationCase.getDecidedAt() == null
                ? 0
                : Duration.between(moderationCase.getCreatedAt(), moderationCase.getDecidedAt()).toMinutes();

        return Optional.of(new DecisionSample(
                moderationCase.getId().toString(),
                moderationCase.getTargetType().name(),
                sha256(text(moderated)),
                pseudonym(moderated.authorId()),
                moderated.title(),
                moderated.body(),
                moderationCase.getEngine(),
                moderationCase.getDecision(),
                moderationCase.getConfidence(),
                moderationCase.getRationale(),
                moderationCase.getRuleCodes(),
                action,
                moderationCase.getDecidedAt(),
                minutes,
                label,
                basis(appeal, revised, engineAgreed),
                engineAgreed,
                revised || appeal != null || minutes > DELIBERATED.toMinutes(),
                priorResolvedCases(moderated.authorId(), moderationCase)));
    }

    /**
     * Ordered by how much a person reconsidered, not by what happened first.
     *
     * <p>An appeal outranks a revision because it means the affected author
     * disagreed and a second reviewer weighed that; a revision outranks a first
     * decision for the same reason at lower strength. A pending appeal is not yet
     * a signal about anything and falls through.
     */
    private LabelBasis basis(Appeal appeal, boolean revised, boolean engineAgreed) {
        if (appeal != null) {
            switch (appeal.getStatus()) {
                case OVERTURNED -> {
                    return LabelBasis.APPEAL_OVERTURNED;
                }
                case UPHELD -> {
                    return LabelBasis.APPEAL_UPHELD;
                }
                default -> {
                    // Filed and undecided: it says the author objects, and nothing yet
                    // about whether they are right.
                }
            }
        }
        if (revised) {
            return LabelBasis.REVISED;
        }
        return engineAgreed ? LabelBasis.ROUTINE : LabelBasis.CORRECTION;
    }

    /**
     * How many resolved cases this author already had when this one was decided.
     *
     * <p>Counted as of the decision, not as of now, because a row that carried
     * "three priors" when it was labelled and "eleven" when it is read is a row
     * that describes two different situations. Uses the same path an
     * investigation does, which is also the only path from an author to their
     * history.
     */
    private int priorResolvedCases(UUID authorId, ModerationCase moderationCase) {
        if (authorId == null || moderationCase.getDecidedAt() == null) {
            return 0;
        }

        List<UUID> targetIds = contentLocator.targetsOf(authorId).stream()
                .map(ContentLocator.TargetRef::id)
                .toList();
        if (targetIds.isEmpty()) {
            return 0;
        }

        return (int) cases.findResolvedForTargets(targetIds, Instant.EPOCH).stream()
                .filter(other -> !other.getId().equals(moderationCase.getId()))
                .filter(other -> other.getDecidedAt() != null
                        && other.getDecidedAt().isBefore(moderationCase.getDecidedAt()))
                .count();
    }

    /**
     * A stable pseudonym rather than a user id or a name.
     *
     * <p>The corpus needs to know that two rows are the same person — that is most
     * of what makes it more interesting than a pile of individual posts — and
     * needs nothing else about them. Hashing keeps the first and drops the rest,
     * so the file can be handed to whoever is building a model without handing
     * over the user table with it.
     */
    private String pseudonym(UUID authorId) {
        return authorId == null ? null : sha256("author:" + authorId).substring(0, 16);
    }

    private String text(ContentLocator.ModeratedContent content) {
        return (content.title() == null ? "" : content.title() + "\n") + content.body();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JVM.", ex);
        }
    }
}
