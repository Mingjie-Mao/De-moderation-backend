package com.campusguard.comment;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * Every live comment on a post, flat, with authors already fetched. The tree
     * is built from this one result set in the service, which keeps the query
     * count at one regardless of how deep the thread goes.
     *
     * <p>The parent association stays lazy and is never dereferenced during
     * assembly. Reading {@code getParent().getId()} off the proxy is safe:
     * Hibernate serves the identifier from the join column without initialising
     * the proxy, so no extra query is issued.
     */
    @Query("""
            select c from Comment c
            join fetch c.author
            left join fetch c.media
            where c.post.id = :postId and c.deletedAt is null
            order by c.createdAt asc
            """)
    List<Comment> findLiveByPostId(@Param("postId") UUID postId);

    /**
     * Top-level comments only, oldest first, seeking past a known position.
     *
     * <p>Oldest first because a thread is read in the order it happened, which is
     * the opposite of the feed and the reason the two cannot share a query even
     * though they share a cursor.
     */
    @Query("""
            select c from Comment c
            join fetch c.author
            left join fetch c.media
            where c.post.id = :postId and c.parent is null and c.deletedAt is null
            order by c.createdAt asc, c.id asc
            """)
    List<Comment> findRootsFirstPage(@Param("postId") UUID postId, Pageable pageable);

    /** The next slice of roots. Split from the first page for the same reason the feed's is. */
    @Query("""
            select c from Comment c
            join fetch c.author
            left join fetch c.media
            where c.post.id = :postId and c.parent is null and c.deletedAt is null
              and (c.createdAt > :afterCreatedAt
                   or (c.createdAt = :afterCreatedAt and c.id > :afterId))
            order by c.createdAt asc, c.id asc
            """)
    List<Comment> findRootsAfter(
            @Param("postId") UUID postId,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterId") UUID afterId,
            Pageable pageable);

    /**
     * Every live reply beneath the given roots, in one query.
     *
     * <p>One query for the whole page's replies rather than one per root: a page
     * of twenty roots would otherwise be twenty round trips, which is the N+1 the
     * rest of this codebase goes out of its way to avoid.
     */
    @Query("""
            select c from Comment c
            join fetch c.author
            left join fetch c.media
            where c.post.id = :postId and c.parent is not null and c.deletedAt is null
            order by c.createdAt asc, c.id asc
            """)
    List<Comment> findRepliesForPost(@Param("postId") UUID postId);

    @Query("""
            select c from Comment c
            join fetch c.author
            left join fetch c.media
            where c.id = :id and c.deletedAt is null
            """)
    Optional<Comment> findLiveById(@Param("id") UUID id);

    /** Backs the authoring rate limit. Deleted comments still count: the cost being limited was already paid. */
    long countByAuthorIdAndCreatedAtAfter(UUID authorId, Instant since);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
