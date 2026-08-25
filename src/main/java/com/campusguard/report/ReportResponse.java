package com.campusguard.report;

import com.campusguard.common.AuthorView;
import com.campusguard.common.TargetType;
import java.time.Instant;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        TargetType targetType,
        UUID targetId,
        ReportReason reason,
        String details,
        ReportStatus status,
        AuthorView reporter,
        Instant createdAt) {

    static ReportResponse of(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDetails(),
                report.getStatus(),
                AuthorView.of(report.getReporter()),
                report.getCreatedAt());
    }
}
