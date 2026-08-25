package com.campusguard.appeal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateAppealRequest(@NotNull UUID caseId, @NotBlank @Size(max=2000) String reason) {}
