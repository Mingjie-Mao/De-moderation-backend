package com.campusguard.moderation.engine.ai;

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
 * One call to a model, successful or not.
 *
 * <p>Append-only, like the audit log and for the same reason: this is the
 * evidence behind every claim about what a model costs and how often it fails.
 */
@Entity
@Table(name = "ai_invocations")
public class AiInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false, length = 40)
    private String engine;

    @Column(length = 80)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvocationStatus status;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private Map<String, Object> rawResponse;

    @Column(columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiInvocation() {
        // for JPA
    }

    AiInvocation(
            UUID caseId,
            String engine,
            String model,
            String promptVersion,
            String contentHash,
            int attempt,
            InvocationStatus status,
            Integer promptTokens,
            Integer completionTokens,
            int latencyMs,
            Map<String, Object> rawResponse,
            String error) {
        this.caseId = caseId;
        this.engine = engine;
        this.model = model;
        this.promptVersion = promptVersion;
        this.contentHash = contentHash;
        this.attempt = attempt;
        this.status = status;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.rawResponse = rawResponse;
        this.error = error;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getEngine() {
        return engine;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getAttempt() {
        return attempt;
    }

    public InvocationStatus getStatus() {
        return status;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public Map<String, Object> getRawResponse() {
        return rawResponse;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
