package com.campusguard.post;

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
    @Query("""
            select p from Post p
            join fetch p.author
            where p.forumKey = :forumKey and p.deletedAt is null
            order by p.createdAt desc
            """)
    List<Post> findFeed(@Param("forumKey") String forumKey, Pageable pageable);

    @Query("""
            select p from Post p
            join fetch p.author
            where p.id = :id and p.deletedAt is null
            """)
    Optional<Post> findLiveById(@Param("id") UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
