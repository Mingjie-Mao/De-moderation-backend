package com.campusguard.post;

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
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "forum_key", nullable = false, length = 50)
    private String forumKey;

    /**
     * Lazy on purpose. {@code open-in-view} is disabled, so anything that needs
     * the author has to say so in the query. The feed uses an explicit join
     * fetch; a lazy default plus a closed session turns an accidental N+1 into a
     * loud failure rather than a slow endpoint.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Post() {
        // for JPA
    }

    public Post(String forumKey, User author, String title, String body) {
        this.forumKey = forumKey;
        this.author = author;
        this.title = title;
        this.body = body;
    }

    public void softDelete(Instant at) {
        this.deletedAt = at;
    }

    /**
     * Put a soft-deleted post back.
     *
     * <p>Only moderation calls this, to undo a hide an administrator has since
     * decided was wrong. An author deleting their own post is not something to
     * reverse on their behalf.
     */
    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getForumKey() {
        return forumKey;
    }

    public User getAuthor() {
        return author;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
