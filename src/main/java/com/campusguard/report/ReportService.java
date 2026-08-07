package com.campusguard.report;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditLogger;
import com.campusguard.comment.CommentRepository;
import com.campusguard.common.ConflictException;
import com.campusguard.common.NotFoundException;
import com.campusguard.common.TargetType;
import com.campusguard.common.TooManyRequestsException;
import com.campusguard.moderation.ModerationCaseService;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final ModerationCaseService moderationCaseService;
    private final AuditLogger auditLogger;
    private final ReportProperties reportProperties;

    public ReportService(
            ReportRepository reportRepository,
            PostRepository postRepository,
            CommentRepository commentRepository,
            UserRepository userRepository,
            ModerationCaseService moderationCaseService,
            AuditLogger auditLogger,
            ReportProperties reportProperties) {
        this.reportRepository = reportRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.moderationCaseService = moderationCaseService;
        this.auditLogger = auditLogger;
        this.reportProperties = reportProperties;
    }

    @Transactional
    public ReportResponse create(UUID reporterId, CreateReportRequest request) {
        User reporter = userRepository
                .findById(reporterId)
                .orElseThrow(() -> new NotFoundException("No user with id " + reporterId));

        requireLiveTarget(request.targetType(), request.targetId());
        requireWithinRateLimit(reporterId);

        // A friendly 409 for the ordinary case. The unique index behind it is
        // what makes this correct under concurrency; this check only spares the
        // caller a database error message.
        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetId(
                reporterId, request.targetType(), request.targetId())) {
            throw new ConflictException("You have already reported this content.");
        }

        // The case is opened before the report is written so that the report can
        // be stored already pointing at it, rather than saved and then updated.
        UUID caseId = moderationCaseService.openOrJoinCase(request.targetType(), request.targetId());

        Report report =
                new Report(request.targetType(), request.targetId(), reporter, request.reason(), caseId);

        // Flushed so that createdAt is populated for the response, and so that a
        // race lost to the unique index fails here rather than at commit.
        reportRepository.saveAndFlush(report);

        auditLogger.record(
                AuditActorType.USER,
                reporterId,
                AuditLogger.REPORT_FILED,
                request.targetType(),
                request.targetId(),
                Map.of("reportId", report.getId().toString(), "caseId", caseId.toString(), "reason", request.reason().name()));

        return ReportResponse.of(report);
    }

    /**
     * Readable by the account that filed it and by administrators, nobody else.
     *
     * <p>The response names the reporter, and in content moderation a reporter's
     * anonymity towards the person they reported is the whole reason people
     * report at all. Without this check, anyone holding a report id could learn
     * who turned them in.
     */
    @Transactional(readOnly = true)
    public ReportResponse get(UUID actorId, UUID id) {
        Report report = reportRepository
                .findByIdWithReporter(id)
                .orElseThrow(() -> new NotFoundException("No report with id " + id));

        User actor = userRepository
                .findById(actorId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorId));

        boolean isReporter = report.getReporter().getId().equals(actorId);
        boolean isAdmin = actor.getRole() == UserRole.ADMIN;

        if (!isReporter && !isAdmin) {
            throw new AccessDeniedException("Only the reporter or an administrator can read this report.");
        }

        return ReportResponse.of(report);
    }

    /**
     * Caps how fast one account can open moderation cases.
     *
     * <p>Every distinct target reported is a case, and every case is an engine
     * call. One account working through a hundred posts is a hundred billable
     * calls and a hundred items in a human queue, all of them individually
     * legitimate, which is why nothing else in the workflow stops it: the
     * per-target uniqueness only prevents reporting the same thing twice.
     */
    private void requireWithinRateLimit(UUID reporterId) {
        Instant since = Instant.now().minus(reportProperties.perUserWindow());
        long recent = reportRepository.countByReporterIdAndCreatedAtAfter(reporterId, since);

        if (recent >= reportProperties.perUserLimit()) {
            throw new TooManyRequestsException(
                    "You have filed %d reports in the last %s. Try again later."
                            .formatted(recent, reportProperties.perUserWindow()));
        }
    }

    /**
     * Reports carry no foreign key to their target, so the reference has to be
     * checked here or the table would happily accept reports against content
     * that never existed.
     */
    private void requireLiveTarget(TargetType targetType, UUID targetId) {
        boolean exists = switch (targetType) {
            case POST -> postRepository.existsByIdAndDeletedAtIsNull(targetId);
            case COMMENT -> commentRepository.existsByIdAndDeletedAtIsNull(targetId);
        };

        if (!exists) {
            throw new NotFoundException(
                    "No " + targetType.name().toLowerCase() + " with id " + targetId);
        }
    }
}
