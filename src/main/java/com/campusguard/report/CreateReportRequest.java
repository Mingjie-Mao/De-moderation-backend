package com.campusguard.report;

import com.campusguard.common.TargetType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateReportRequest(
        @NotNull TargetType targetType,
        @NotNull UUID targetId,
        @NotNull ReportReason reason) {
}
