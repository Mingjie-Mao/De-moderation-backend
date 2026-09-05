package com.campusguard.moderation.admin;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.audit.AuditLogger;
import com.campusguard.common.ConflictException;
import com.campusguard.common.NotFoundException;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.ContentLocator;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.report.Report;
import com.campusguard.report.ReportRepository;
import com.campusguard.notification.NotificationService;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminModerationService {

    private final ModerationCaseRepository caseRepository;
    private final AuditEntryRepository auditEntryRepository;
    private final ContentLocator contentLocator;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final AuditLogger auditLogger;
    private final NotificationService notifications;

    public AdminModerationService(
            ModerationCaseRepository caseRepository,
            AuditEntryRepository auditEntryRepository,
            ContentLocator contentLocator,
            UserRepository userRepository,
            ReportRepository reportRepository,
            AuditLogger auditLogger,
            NotificationService notifications) {
        this.caseRepository = caseRepository;
        this.auditEntryRepository = auditEntryRepository;
        this.contentLocator = contentLocator;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.auditLogger = auditLogger;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<ModerationCaseView> list(CaseStatus status, Pageable pageable) {
        return caseRepository.findByStatus(status, pageable).stream()
                .map(ModerationCaseView::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ModerationCaseDetail get(UUID caseId) {
        ModerationCase moderationCase = requireCase(caseId);

        // Removed content included on purpose: this view exists so a person can
        // read what a decision was about, and a hidden item is exactly the case
        // where they most need to.
        ReportedContentView content = contentLocator
                .findIncludingRemoved(moderationCase.getTargetType(), moderationCase.getTargetId())
                .map(ReportedContentView::of)
                .orElse(null);

        List<CaseAuditEntryView> trail = auditEntryRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                        moderationCase.getTargetType(), moderationCase.getTargetId())
                .stream()
                .map(CaseAuditEntryView::of)
                .toList();

        return new ModerationCaseDetail(ModerationCaseView.of(moderationCase), content, trail);
    }

    /**
     * Close a case with an outcome.
     *
     * <p>This is the only place content is removed or an account banned as a
     * result of moderation. An engine's verdict never reaches here on its own,
     * which is what makes the recommendation reviewable rather than merely
     * reported after the fact.
     */
    @Transactional
    public ModerationCaseDetail decide(UUID adminId, UUID caseId, CaseDecisionRequest request) {
        User admin = userRepository
                .findById(adminId)
                .orElseThrow(() -> new NotFoundException("No user with id " + adminId));

        ModerationCase moderationCase = requireCaseForUpdate(caseId);

        // A decided case is corrected rather than refused. A reviewer who hid the
        // wrong thing needs a way back, and refusing here would leave the only
        // remedy outside the product, where nothing is audited.
        if (moderationCase.getStatus() == CaseStatus.RESOLVED) {
            return revise(admin, moderationCase, request);
        }

        if (moderationCase.getStatus() != CaseStatus.AWAITING_REVIEW) {
            throw new ConflictException(
                    "This case cannot be resolved while it is "
                            + moderationCase.getStatus()
                            + ". Wait for automated analysis to finish.");
        }

        if (moderationCase.getAssignedTo() != null
                && !moderationCase.getAssignedTo().getId().equals(adminId)) {
            throw new ConflictException("This case is assigned to another reviewer.");
        }
        if (moderationCase.getAssignedTo() == null) {
            moderationCase.assign(admin);
        }

        applyAction(admin, moderationCase, request.action());

        moderationCase.resolve(admin, request.action());

        // The reports that caused this case reach their own terminal state here.
        // Leaving them aggregated forever would mean a reporter's submission was
        // never actually finished with, only quietly forgotten.
        reportRepository.findByCaseId(caseId).forEach(Report::markResolved);

        Map<String, Object> payload = new HashMap<>();
        payload.put("caseId", caseId.toString());
        payload.put("action", request.action().name());
        payload.put("recommendation", String.valueOf(moderationCase.getDecision()));
        if (request.note() != null && !request.note().isBlank()) {
            payload.put("note", request.note());
        }
        // Recorded whenever the human disagreed with the engine. This is the
        // number that tells you whether the engine is worth keeping, and it is
        // invisible unless it is written down at the moment of disagreement.
        payload.put("overrodeRecommendation", disagrees(moderationCase, request.action()));

        auditLogger.record(
                AuditActorType.ADMIN,
                adminId,
                AuditLogger.CASE_RESOLVED,
                moderationCase.getTargetType(),
                moderationCase.getTargetId(),
                payload);

        notifyDecision(moderationCase, request.action());

        return get(caseId);
    }

    /**
     * Change the outcome of a case that was already decided.
     *
     * <p>The effects of the old decision are undone before the new one is
     * applied, so the account and the content end up in the state the new action
     * describes rather than in the union of both. Everything is appended to the
     * audit trail: the correction never erases the decision it replaces.
     */
    private ModerationCaseDetail revise(
            User admin, ModerationCase moderationCase, CaseDecisionRequest request) {

        FinalAction previous = moderationCase.getFinalAction();
        FinalAction next = request.action();

        if (previous == next) {
            throw new ConflictException(
                    "This case is already resolved as " + previous + ".");
        }

        undoAction(admin, moderationCase, previous, next);
        applyAction(admin, moderationCase, next);

        moderationCase.revise(admin, next);

        Map<String, Object> payload = new HashMap<>();
        payload.put("caseId", moderationCase.getId().toString());
        payload.put("previousAction", String.valueOf(previous));
        payload.put("action", next.name());
        payload.put("recommendation", String.valueOf(moderationCase.getDecision()));
        if (request.note() != null && !request.note().isBlank()) {
            payload.put("note", request.note());
        }
        payload.put("overrodeRecommendation", disagrees(moderationCase, next));

        auditLogger.record(
                AuditActorType.ADMIN,
                admin.getId(),
                AuditLogger.CASE_DECISION_REVISED,
                moderationCase.getTargetType(),
                moderationCase.getTargetId(),
                payload);

        notifyDecision(moderationCase, next);

        return get(moderationCase.getId());
    }

    /**
     * Reverse whatever the previous decision did that the new one does not ask
     * for. Nothing here runs when both decisions remove the content, so a HIDE
     * corrected to a BAN does not briefly put the post back.
     */
    private void undoAction(
            User admin, ModerationCase moderationCase, FinalAction previous, FinalAction next) {

        if (removesContent(previous) && !removesContent(next)) {
            boolean restored =
                    contentLocator.restore(moderationCase.getTargetType(), moderationCase.getTargetId());
            if (restored) {
                auditLogger.record(
                        AuditActorType.ADMIN,
                        admin.getId(),
                        AuditLogger.CONTENT_RESTORED,
                        moderationCase.getTargetType(),
                        moderationCase.getTargetId(),
                        Map.of("caseId", moderationCase.getId().toString(), "from", previous.name()));
            }
        }

        if (previous == FinalAction.BAN && next != FinalAction.BAN) {
            contentLocator
                    .authorOf(moderationCase.getTargetType(), moderationCase.getTargetId())
                    .flatMap(userRepository::findById)
                    .ifPresent(author -> reinstateUnlessBannedElsewhere(admin, moderationCase, author));
        }
    }

    /**
     * Lift the ban only if this case was the last thing holding it up.
     *
     * <p>Two cases can each end in a ban on the same account. Undoing one of
     * them says that decision was wrong, not that the other one was, so the
     * account stays suspended and the audit trail records why nothing changed.
     */
    private void reinstateUnlessBannedElsewhere(
            User admin, ModerationCase moderationCase, User author) {

        List<ModerationCase> otherBans =
                caseRepository.findOtherStandingBans(moderationCase.getId());

        boolean bannedByAnotherCase = otherBans.stream().anyMatch(other -> contentLocator
                .authorOf(other.getTargetType(), other.getTargetId())
                .map(otherAuthorId -> otherAuthorId.equals(author.getId()))
                .orElse(false));

        if (bannedByAnotherCase) {
            auditLogger.record(
                    AuditActorType.ADMIN,
                    admin.getId(),
                    AuditLogger.BAN_UPHELD_ELSEWHERE,
                    moderationCase.getTargetType(),
                    moderationCase.getTargetId(),
                    Map.of(
                            "caseId", moderationCase.getId().toString(),
                            "userId", author.getId().toString()));
            return;
        }

        author.reinstate();

        auditLogger.record(
                AuditActorType.ADMIN,
                admin.getId(),
                AuditLogger.AUTHOR_REINSTATED,
                moderationCase.getTargetType(),
                moderationCase.getTargetId(),
                Map.of(
                        "caseId", moderationCase.getId().toString(),
                        "reinstatedUserId", author.getId().toString()));
    }

    /** Whether an outcome takes the content down. {@code NONE} is the only one that does not. */
    private static boolean removesContent(FinalAction action) {
        return action == FinalAction.HIDE || action == FinalAction.DELETE || action == FinalAction.BAN;
    }

    private void applyAction(User admin, ModerationCase moderationCase, FinalAction action) {
        if (action == FinalAction.NONE) {
            return;
        }

        Optional<UUID> authorId =
                contentLocator.authorOf(moderationCase.getTargetType(), moderationCase.getTargetId());

        boolean hidden = contentLocator.hide(moderationCase.getTargetType(), moderationCase.getTargetId());
        if (hidden) {
            auditLogger.record(
                    AuditActorType.ADMIN,
                    admin.getId(),
                    AuditLogger.CONTENT_HIDDEN,
                    moderationCase.getTargetType(),
                    moderationCase.getTargetId(),
                    Map.of("caseId", moderationCase.getId().toString(), "action", action.name()));
        }

        if (action == FinalAction.BAN) {
            UUID id = authorId.orElseThrow(
                    () -> new NotFoundException("The reported content has no author to ban."));

            User author = userRepository
                    .findById(id)
                    .orElseThrow(() -> new NotFoundException("No user with id " + id));

            author.ban();

            auditLogger.record(
                    AuditActorType.ADMIN,
                    admin.getId(),
                    AuditLogger.AUTHOR_BANNED,
                    moderationCase.getTargetType(),
                    moderationCase.getTargetId(),
                    Map.of("caseId", moderationCase.getId().toString(), "bannedUserId", id.toString()));
        }
    }

    /**
     * Whether the outcome contradicts what the engine advised. ALLOW paired with
     * anything that removes content, or a removal recommendation waved through.
     */
    private boolean disagrees(ModerationCase moderationCase, FinalAction action) {
        return switch (moderationCase.getDecision()) {
            case null -> false;
            case ALLOW -> action != FinalAction.NONE;
            case REMOVE -> action == FinalAction.NONE;
            // Escalation is a request for judgement, so no outcome contradicts it.
            case ESCALATE -> false;
        };
    }

    private ModerationCase requireCase(UUID caseId) {
        return caseRepository
                .findById(caseId)
                .orElseThrow(() -> new NotFoundException("No moderation case with id " + caseId));
    }

    private ModerationCase requireCaseForUpdate(UUID caseId) {
        return caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new NotFoundException("No moderation case with id " + caseId));
    }

    @Transactional
    public ModerationCaseDetail assign(UUID adminId, UUID caseId) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("No user with id " + adminId));
        ModerationCase moderationCase = requireCaseForUpdate(caseId);
        if (moderationCase.getStatus() != CaseStatus.AWAITING_REVIEW) {
            throw new ConflictException("Only cases awaiting review can be assigned.");
        }
        if (moderationCase.getAssignedTo() != null) {
            if (moderationCase.getAssignedTo().getId().equals(adminId)) return get(caseId);
            throw new ConflictException("This case is already assigned to another reviewer.");
        }
        moderationCase.assign(admin);
        auditLogger.record(AuditActorType.ADMIN, adminId, AuditLogger.CASE_ASSIGNED,
                moderationCase.getTargetType(), moderationCase.getTargetId(),
                Map.of("caseId", caseId.toString(), "reviewerId", adminId.toString()));
        return get(caseId);
    }

    @Transactional
    public ModerationCaseDetail release(UUID adminId, UUID caseId) {
        ModerationCase moderationCase = requireCaseForUpdate(caseId);
        if (moderationCase.getStatus() != CaseStatus.AWAITING_REVIEW) {
            throw new ConflictException("Only cases awaiting review have an active assignment.");
        }
        if (moderationCase.getAssignedTo() == null) return get(caseId);
        if (!moderationCase.getAssignedTo().getId().equals(adminId)) {
            throw new ConflictException("Only the assigned reviewer can release this case.");
        }
        moderationCase.releaseAssignment();
        auditLogger.record(AuditActorType.ADMIN, adminId, AuditLogger.CASE_ASSIGNMENT_RELEASED,
                moderationCase.getTargetType(), moderationCase.getTargetId(),
                Map.of("caseId", caseId.toString()));
        return get(caseId);
    }

    private void notifyDecision(ModerationCase moderationCase, FinalAction action) {
        Map<UUID, User> recipients = new java.util.LinkedHashMap<>();
        reportRepository.findByCaseId(moderationCase.getId()).forEach(report ->
                recipients.put(report.getReporter().getId(), report.getReporter()));
        Optional<UUID> affectedAuthorId = contentLocator.authorOf(
                moderationCase.getTargetType(), moderationCase.getTargetId());
        affectedAuthorId.flatMap(userRepository::findById)
                .ifPresent(user -> recipients.put(user.getId(), user));

        recipients.values().forEach(user -> notifications.create(
                user,
                affectedAuthorId.filter(user.getId()::equals).isPresent() && action != FinalAction.NONE
                        ? "MODERATION_DECISION_APPEALABLE"
                        : "MODERATION_DECISION",
                "A moderation case was decided",
                "The current action is " + action + ". Open the case for details.",
                "MODERATION_CASE",
                moderationCase.getId()));
    }
}
