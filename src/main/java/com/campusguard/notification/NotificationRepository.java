package com.campusguard.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    @Query("select n from Notification n where n.user.id=:userId order by n.createdAt desc")
    List<Notification> findForUser(@Param("userId") UUID userId, Pageable pageable);
    @Query("select n from Notification n where n.id=:id and n.user.id=:userId")
    Optional<Notification> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);
    long countByUserIdAndReadAtIsNull(UUID userId);
}
