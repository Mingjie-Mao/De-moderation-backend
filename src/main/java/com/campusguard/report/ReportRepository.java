package com.campusguard.report;

import com.campusguard.common.TargetType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    boolean existsByReporterIdAndTargetTypeAndTargetId(
            UUID reporterId, TargetType targetType, UUID targetId);

    @Query("""
            select r from Report r
            join fetch r.reporter
            where r.id = :id
            """)
    Optional<Report> findByIdWithReporter(@Param("id") UUID id);

    long countByTargetTypeAndTargetId(TargetType targetType, UUID targetId);

    List<Report> findByCaseId(UUID caseId);

    /**
     * Counted in the database rather than tracked in memory, so the limit holds
     * across instances. An in-process counter would let a second replica double
     * the effective allowance, which is the wrong direction for a control whose
     * whole job is to bound cost.
     */
    long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant since);
}
