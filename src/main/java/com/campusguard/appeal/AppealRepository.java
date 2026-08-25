package com.campusguard.appeal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface AppealRepository extends JpaRepository<Appeal, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Appeal a where a.id = :id")
    java.util.Optional<Appeal> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    boolean existsByModerationCaseIdAndAppellantIdAndStatus(UUID caseId, UUID appellantId, AppealStatus status);
    List<Appeal> findByStatusOrderByCreatedAtAsc(AppealStatus status, Pageable pageable);
    List<Appeal> findByAppellantIdOrderByCreatedAtDesc(UUID appellantId, Pageable pageable);
}
