package com.campusguard.media;

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
@Table(name = "media_objects")
public class MediaObject {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "storage_key", nullable = false, unique = true, length = 255)
    private String storageKey;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaObject() {}
    MediaObject(User owner, String contentType, long sizeBytes, String sha256, String storageKey) {
        this.owner = owner; this.contentType = contentType; this.sizeBytes = sizeBytes;
        this.sha256 = sha256; this.storageKey = storageKey;
    }
    public UUID getId() { return id; }
    public User getOwner() { return owner; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public Instant getCreatedAt() { return createdAt; }
}
