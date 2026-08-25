package com.campusguard.report;

import com.campusguard.common.TargetType;
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
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "reports")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    /**
     * Deliberately a bare id rather than an association: it points at a post or
     * a comment depending on {@link #targetType}, and no single foreign key can
     * express that. Existence is checked in the service before insert.
     */
    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    /**
     * The case this report was folded into.
     *
     * <p>A plain id rather than an association: cases already reach back into
     * reports, and mapping the other direction as well would make the two
     * packages depend on each other's types for no gain at either end.
     */
    @Column(name = "case_id")
    private UUID caseId;

    @Column(length = 1000)
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Report() {
        // for JPA
    }

    /**
     * A report is constructed already belonging to a case. Taking the case id
     * here rather than through a later setter is what makes "a report without a
     * case" unrepresentable instead of merely unlikely.
     */
    public Report(TargetType targetType, UUID targetId, User reporter, ReportReason reason, UUID caseId) {
        this(targetType, targetId, reporter, reason, caseId, null);
    }

    public Report(TargetType targetType, UUID targetId, User reporter, ReportReason reason, UUID caseId, String details) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.reporter = reporter;
        this.reason = reason;
        this.caseId = caseId;
        this.status = ReportStatus.AGGREGATED;
        this.details = details == null || details.isBlank() ? null : details.strip();
    }

    /** Called when the case this report fed is decided. */
    public void markResolved() {
        this.status = ReportStatus.RESOLVED;
    }

    public UUID getCaseId() {
        return caseId;
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

    public User getReporter() {
        return reporter;
    }

    public ReportReason getReason() {
        return reason;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getDetails() { return details; }
}
