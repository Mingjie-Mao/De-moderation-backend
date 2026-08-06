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

    public AdminModerationService(
            ModerationCaseRepository caseRepository,
            AuditEntryRepository auditEntryRepository,
            ContentLocator contentLocator,
            UserRepository userRepository,
            ReportRepository reportRepository,
            AuditLogger auditLogger) {
        this.caseRepository = caseRepository;
        this.auditEntryRepository = auditEntryRepository;
        this.contentLocator = contentLocator;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.auditLogger = auditLogger;
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

        ReportedContentView content = contentLocator
                .find(moderationCase.getTargetType(), moderationCase.getTargetId())
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

        ModerationCase moderationCase = requireCase(caseId);

        if (moderationCase.getStatus() == CaseStatus.RESOLVED) {
            throw new ConflictException("This case has already been resolved.");
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

        return get(caseId);
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
}
