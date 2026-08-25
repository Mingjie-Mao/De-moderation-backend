package com.campusguard.moderation.admin;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A case as the moderation console shows it.
 *
 * <p>{@code decision}, {@code confidence} and {@code rationale} are presented as
 * a recommendation and never as an outcome: the reviewer's job is to agree or
 * not, and a field named for what "will happen" would quietly turn the review
 * into a rubber stamp.
 */
public record ModerationCaseView(
        UUID id,
        TargetType targetType,
        UUID targetId,
        CaseStatus status,
        int reportCount,
        String engine,
        ModerationDecision recommendedDecision,
        BigDecimal confidence,
        String rationale,
        List<String> ruleCodes,
        Instant analysedAt,
        UUID decidedBy,
        Instant decidedAt,
        FinalAction finalAction,
        UUID assignedTo,
        Instant assignedAt,
        Instant reviewDueAt,
        Instant createdAt) {

    public static ModerationCaseView of(ModerationCase source) {
        return new ModerationCaseView(
                source.getId(),
                source.getTargetType(),
                source.getTargetId(),
                source.getStatus(),
                source.getReportCount(),
                source.getEngine(),
                source.getDecision(),
                source.getConfidence(),
                source.getRationale(),
                source.getRuleCodes(),
                source.getAnalysedAt(),
                source.getDecidedBy() == null ? null : source.getDecidedBy().getId(),
                source.getDecidedAt(),
                source.getFinalAction(),
                source.getAssignedTo() == null ? null : source.getAssignedTo().getId(),
                source.getAssignedAt(),
                source.getReviewDueAt(),
                source.getCreatedAt());
    }
}
