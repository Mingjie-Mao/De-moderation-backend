package com.campusguard.report;

import com.campusguard.common.TargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateReportRequest(
        @NotNull TargetType targetType,
        @NotNull UUID targetId,
        @NotNull ReportReason reason,
        @Size(max = 1000) String details) {

    public CreateReportRequest(TargetType targetType, UUID targetId, ReportReason reason) {
        this(targetType, targetId, reason, null);
    }
}
