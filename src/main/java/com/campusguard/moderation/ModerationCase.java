package com.campusguard.moderation;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One piece of content under review, however many people reported it.
 *
 * <p>The state transitions are methods rather than an exposed setter for
 * {@code status}, so that every move through the workflow has one place it can
 * happen and one place its preconditions are checked.
 */
@Entity
@Table(name = "moderation_cases")
public class ModerationCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaseStatus status;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(length = 40)
    private String engine;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ModerationDecision decision;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(columnDefinition = "text")
    private String rationale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_codes", nullable = false, columnDefinition = "jsonb")
    private List<String> ruleCodes = List.of();

    @Column(name = "analysed_at")
    private Instant analysedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_action", length = 20)
    private FinalAction finalAction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModerationCase() {
        // for JPA
    }

    public void markAnalysing() {
        requireStatus(CaseStatus.QUEUED);
        this.status = CaseStatus.ANALYSING;
    }

    /**
     * Note what an engine concluded and hand the case to a person. There is
     * deliberately no path from here straight to resolved, however confident the
     * verdict: an automated system that removes content on its own is one nobody
     * can appeal to, and this is the constraint the whole workflow exists to
     * enforce.
     */
    public void recordVerdict(String engineName, ModerationVerdict verdict) {
        requireStatus(CaseStatus.ANALYSING);
        this.engine = engineName;
        this.decision = verdict.decision();
        this.confidence = BigDecimal.valueOf(verdict.confidence()).setScale(3, RoundingMode.HALF_UP);
        this.rationale = verdict.rationale();
        this.ruleCodes = List.copyOf(verdict.ruleCodes());
        this.analysedAt = Instant.now();
        this.status = CaseStatus.AWAITING_REVIEW;
    }

    public void resolve(User admin, FinalAction action) {
        requireStatus(CaseStatus.AWAITING_REVIEW);
        this.decidedBy = admin;
        this.decidedAt = Instant.now();
        this.finalAction = action;
        this.status = CaseStatus.RESOLVED;
    }

    /**
     * Replace the outcome of a case that has already been decided.
     *
     * <p>Reviewers are wrong sometimes, and a decision nobody can revisit turns
     * every mistake into a permanent one. The case stays {@code RESOLVED} — this
     * is a correction, not a reopening — and the fields move to whoever made the
     * correction, so "who decided this" always names the person answerable for
     * the outcome that is actually in force. The history of how it got here
     * lives in the audit trail, which only ever appends.
     */
    public void revise(User admin, FinalAction action) {
        requireStatus(CaseStatus.RESOLVED);
        this.decidedBy = admin;
        this.decidedAt = Instant.now();
        this.finalAction = action;
    }

    /**
     * Hand a stalled case back to the queue after the worker holding it died.
     * Deliberately not a reset of the verdict fields: nothing was written to them,
     * because a verdict and the move out of {@code ANALYSING} happen together.
     */
    public void requeue() {
        requireStatus(CaseStatus.ANALYSING);
        this.status = CaseStatus.QUEUED;
    }

    private void requireStatus(CaseStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Case " + id + " is " + status + ", expected " + expected + ".");
        }
    }

    public UUID getId() {
        return id;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public int getReportCount() {
        return reportCount;
    }

    public String getEngine() {
        return engine;
    }

    public ModerationDecision getDecision() {
        return decision;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getRationale() {
        return rationale;
    }

    public List<String> getRuleCodes() {
        return ruleCodes == null ? List.of() : ruleCodes;
    }

    public Instant getAnalysedAt() {
        return analysedAt;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public FinalAction getFinalAction() {
        return finalAction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
