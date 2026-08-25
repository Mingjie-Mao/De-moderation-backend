package com.campusguard.appeal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AppealDecisionRequest(@NotNull AppealDecision decision, @Size(max=2000) String response) {}
