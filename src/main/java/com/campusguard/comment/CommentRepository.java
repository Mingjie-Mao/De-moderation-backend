package com.campusguard.comment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
            where c.post.id = :postId and c.deletedAt is null
            order by c.createdAt asc
            """)
    List<Comment> findLiveByPostId(@Param("postId") UUID postId);

    @Query("""
            select c from Comment c
            join fetch c.author
            where c.id = :id and c.deletedAt is null
            """)
    Optional<Comment> findLiveById(@Param("id") UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
