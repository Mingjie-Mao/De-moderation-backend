package com.campusguard.comment;

import com.campusguard.post.Post;
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
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    /**
     * Null for a top-level comment. The thread is stored as a plain parent
     * pointer and assembled in memory after a single query per post, rather than
     * walked recursively: a campus thread is small enough that one flat read
     * beats a recursive CTE, and the shape stays obvious to read.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private Comment parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    /**
     * How many parents sit above this one; zero for a top-level comment.
     *
     * <p>Stored rather than derived, because the reader that needs it most is the
     * one assembling a whole thread, and making that reader walk upwards per
     * comment turns one query into thousands. Kept correct at write time, where
     * the parent is already in hand, and bounded by a check constraint so it
     * holds for writers that never pass through this class.
     */
    @Column(nullable = false)
    private int depth;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Comment() {
        // for JPA
    }

    /** The deepest a reply may be nested. Past this, no client renders the nesting anyway. */
    public static final int MAX_DEPTH = 10;

    public Comment(Post post, Comment parent, User author, String body) {
        this.post = post;
        this.parent = parent;
        this.author = author;
        this.body = body;
        this.depth = parent == null ? 0 : parent.getDepth() + 1;
    }

    public void softDelete(Instant at) {
        this.deletedAt = at;
    }

    /** Put a soft-deleted comment back. See {@code Post#restore()}. */
    public void restore() {
        this.deletedAt = null;
    }

    public int getDepth() {
        return depth;
    }

    public UUID getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public Comment getParent() {
        return parent;
    }

    public User getAuthor() {
        return author;
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
