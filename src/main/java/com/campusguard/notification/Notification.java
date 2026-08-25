package com.campusguard.notification;

import com.campusguard.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "notifications")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 40)
    private String type;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(nullable = false, length = 1000)
    private String body;
    @Column(name = "reference_type", length = 30)
    private String referenceType;
    @Column(name = "reference_id")
    private UUID referenceId;
    @Column(name = "read_at")
    private Instant readAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {}
    Notification(User user, String type, String title, String body, String referenceType, UUID referenceId) {
        this.user=user; this.type=type; this.title=title; this.body=body;
        this.referenceType=referenceType; this.referenceId=referenceId;
    }
    void markRead() { if (readAt == null) readAt = Instant.now(); }
    public UUID getId(){return id;} public User getUser(){return user;} public String getType(){return type;}
    public String getTitle(){return title;} public String getBody(){return body;}
    public String getReferenceType(){return referenceType;} public UUID getReferenceId(){return referenceId;}
    public Instant getReadAt(){return readAt;} public Instant getCreatedAt(){return createdAt;}
}
