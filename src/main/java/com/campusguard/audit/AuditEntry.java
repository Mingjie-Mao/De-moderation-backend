package com.campusguard.audit;

import com.campusguard.common.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One thing that happened, written once and never changed.
 *
 * <p>There are no setters and no update path. An audit trail that can be edited
 * after the fact answers a different, much weaker question than one that cannot.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private AuditActorType actorType;

    /**
     * Null for engines and for the system, which have no account. Carries no
     * foreign key on purpose: an entry has to outlive the account it describes,
     * or deleting a user quietly erases the record of what they did.
     */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 60)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    /**
     * Whatever detail the action carries. JSONB rather than columns because the
     * useful detail differs per action, and adding a nullable column for every
     * one of them would leave a table that is mostly empty.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = Map.of();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEntry() {
        // for JPA
    }

    AuditEntry(
            AuditActorType actorType,
            UUID actorId,
            String action,
            TargetType targetType,
            UUID targetId,
            Map<String, Object> payload) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public UUID getId() {
        return id;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Map<String, Object> getPayload() {
        return payload == null ? Map.of() : payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
