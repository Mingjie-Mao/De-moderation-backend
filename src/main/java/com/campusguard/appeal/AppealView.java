package com.campusguard.appeal;

import java.time.Instant;
import java.util.UUID;

public record AppealView(UUID id, UUID caseId, UUID appellantId, String reason, AppealStatus status,
                         String response, UUID decidedBy, Instant decidedAt, Instant createdAt) {
    static AppealView of(Appeal a) {
        return new AppealView(a.getId(), a.getModerationCase().getId(), a.getAppellant().getId(), a.getReason(),
                a.getStatus(), a.getResponse(), a.getDecidedBy()==null?null:a.getDecidedBy().getId(),
                a.getDecidedAt(), a.getCreatedAt());
    }
}
