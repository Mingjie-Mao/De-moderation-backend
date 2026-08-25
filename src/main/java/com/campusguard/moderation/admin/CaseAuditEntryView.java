package com.campusguard.moderation.admin;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditEntry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CaseAuditEntryView(
        AuditActorType actorType, UUID actorId, String action, Map<String, Object> payload, Instant at) {

    public static CaseAuditEntryView of(AuditEntry entry) {
        return new CaseAuditEntryView(
                entry.getActorType(),
                entry.getActorId(),
                entry.getAction(),
                entry.getPayload(),
                entry.getCreatedAt());
    }
}
