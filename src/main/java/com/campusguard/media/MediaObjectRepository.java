package com.campusguard.media;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaObjectRepository extends JpaRepository<MediaObject, UUID> {
    @Query("select count(p) from Post p where p.media.id = :mediaId and p.deletedAt is null")
    long countVisiblePostReferences(@Param("mediaId") UUID mediaId);

    @Query("""
            select count(c) from Comment c
            where c.media.id = :mediaId
              and c.deletedAt is null
              and c.post.deletedAt is null
            """)
    long countVisibleCommentReferences(@Param("mediaId") UUID mediaId);

    /**
     * Media that no post and no comment refers to at all.
     *
     * <p>Deliberately blind to {@code deletedAt}, which is the one decision in
     * this query that matters. A post removed by a moderator is soft-deleted, and
     * that decision can be reversed — re-decision and appeal are both supported
     * features. If this swept media belonging to hidden content, reversing a
     * takedown would restore a post with a hole where its image was, and the
     * reversal would be the thing that looked broken.
     *
     * <p>What is left is media nothing points to under any circumstances: an
     * upload that was never attached, or content that was hard-deleted. Those are
     * bytes nobody can ever reach again.
     */
    @Query("""
            select m from MediaObject m
            where m.createdAt < :before
              and not exists (select 1 from Post p where p.media = m)
              and not exists (select 1 from Comment c where c.media = m)
            order by m.createdAt
            """)
    List<MediaObject> findUnreferencedBefore(@Param("before") Instant before, Pageable pageable);

    /** Which of these stored keys the database knows about, asked once instead of once per key. */
    @Query("select m.storageKey from MediaObject m where m.storageKey in :keys")
    List<String> findKnownStorageKeys(@Param("keys") Collection<String> keys);
}
