package com.campusguard.report;

import com.campusguard.comment.CommentRepository;
import com.campusguard.common.ConflictException;
import com.campusguard.common.NotFoundException;
import com.campusguard.common.TargetType;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;

    public ReportService(
            ReportRepository reportRepository,
            PostRepository postRepository,
            CommentRepository commentRepository,
            UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ReportResponse create(UUID reporterId, CreateReportRequest request) {
        User reporter = userRepository
                .findById(reporterId)
                .orElseThrow(() -> new NotFoundException("No user with id " + reporterId));

        requireLiveTarget(request.targetType(), request.targetId());

        // A friendly 409 for the ordinary case. The unique index behind it is
        // what makes this correct under concurrency; this check only spares the
        // caller a database error message.
        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetId(
                reporterId, request.targetType(), request.targetId())) {
            throw new ConflictException("You have already reported this content.");
        }

        // Flushed so that createdAt is populated for the response, and so that a
        // race lost to the unique index fails here rather than at commit.
        Report report = reportRepository.saveAndFlush(
                new Report(request.targetType(), request.targetId(), reporter, request.reason()));

        return ReportResponse.of(report);
    }

    @Transactional(readOnly = true)
    public ReportResponse get(UUID id) {
        return reportRepository
                .findByIdWithReporter(id)
                .map(ReportResponse::of)
                .orElseThrow(() -> new NotFoundException("No report with id " + id));
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
