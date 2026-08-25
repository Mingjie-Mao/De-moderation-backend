package com.campusguard.media;

import java.util.UUID;
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
}
