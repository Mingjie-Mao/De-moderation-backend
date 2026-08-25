package com.campusguard.post;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, UUID> {

    /**
     * The feed. Written out rather than derived because the author has to be
     * fetched in the same round trip: rendering a feed of N posts through a lazy
     * author association is the classic N+1, and a derived query name cannot
     * express the fetch.
     *
     * <p>Ordering and the {@code deleted_at IS NULL} predicate match
     * {@code idx_posts_forum_created} so the partial index actually gets used.
     */
    /**
     * The feed, from the top or from where a previous page stopped.
     *
     * <p>Written out rather than derived because the author has to be fetched in
     * the same round trip: rendering a feed of N posts through a lazy author
     * association is the classic N+1, and a derived query name cannot express the
     * fetch.
     *
     * <p>Paged by position rather than by offset. A forum feed has new rows
     * arriving at the top, and with an offset every insertion shifts everything
     * down, so a reader paging through sees some posts twice and never sees
     * others. Seeking past a known position is immune to that, and it also lets
     * the database stop reading as soon as it has enough rows instead of counting
     * past the ones it is skipping.
     *
     * <p>The ordering and the predicate match {@code idx_posts_forum_created}, so
     * the partial index carries the whole query.
     */
    @Query("""
            select p from Post p
            join fetch p.author
            left join fetch p.media
            where p.forumKey = :forumKey and p.deletedAt is null
            order by p.createdAt desc, p.id desc
            """)
    List<Post> findFeedFirstPage(@Param("forumKey") String forumKey, Pageable pageable);

    /**
     * The next slice, seeking past the position the previous one ended at.
     *
     * <p>A separate method rather than one query with a nullable cursor. Written
     * that way, PostgreSQL cannot infer the type of the null parameter and refuses
     * the statement outright; written this way, each query also carries a single
     * clean predicate for the planner instead of a disjunction it has to see
     * through.
     *
     * <p>The comparison is on the pair, not the instant. Two posts created in the
     * same microsecond would make a timestamp-only boundary ambiguous, and a
     * reader paging across it would see one of them twice or neither.
     */
    @Query("""
            select p from Post p
            join fetch p.author
            left join fetch p.media
            where p.forumKey = :forumKey
              and p.deletedAt is null
              and (p.createdAt < :beforeCreatedAt
                   or (p.createdAt = :beforeCreatedAt and p.id < :beforeId))
            order by p.createdAt desc, p.id desc
            """)
    List<Post> findFeedAfter(
            @Param("forumKey") String forumKey,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") UUID beforeId,
            Pageable pageable);

    @Query("""
            select p from Post p
            join fetch p.author
            left join fetch p.media
            where p.id = :id and p.deletedAt is null
            """)
    Optional<Post> findLiveById(@Param("id") UUID id);

    /** Backs the authoring rate limit. Deleted posts still count: the cost being limited was already paid. */
    long countByAuthorIdAndCreatedAtAfter(UUID authorId, Instant since);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
