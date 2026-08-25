package com.campusguard.appeal;

import com.campusguard.moderation.ModerationCase;
import com.campusguard.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity @Table(name="appeals")
public class Appeal {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="case_id", nullable=false)
    private ModerationCase moderationCase;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="appellant_id", nullable=false)
    private User appellant;
    @Column(nullable=false, length=2000) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private AppealStatus status;
    @Column(length=2000) private String response;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="decided_by") private User decidedBy;
    @Column(name="decided_at") private Instant decidedAt;
    @CreationTimestamp @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    protected Appeal() {}
    Appeal(ModerationCase moderationCase, User appellant, String reason) {
        this.moderationCase=moderationCase; this.appellant=appellant; this.reason=reason.strip();
        this.status=AppealStatus.PENDING;
    }
    void decide(User admin, AppealDecision decision, String response) {
        if (status != AppealStatus.PENDING) throw new IllegalStateException("Appeal is already decided.");
        status = decision == AppealDecision.UPHOLD ? AppealStatus.UPHELD : AppealStatus.OVERTURNED;
        this.response=response == null || response.isBlank() ? null : response.strip();
        this.decidedBy=admin; this.decidedAt=Instant.now();
    }
    public UUID getId(){return id;} public ModerationCase getModerationCase(){return moderationCase;}
    public User getAppellant(){return appellant;} public String getReason(){return reason;}
    public AppealStatus getStatus(){return status;} public String getResponse(){return response;}
    public User getDecidedBy(){return decidedBy;} public Instant getDecidedAt(){return decidedAt;}
    public Instant getCreatedAt(){return createdAt;}
}
