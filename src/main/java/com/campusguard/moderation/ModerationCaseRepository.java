package com.campusguard.moderation;

import com.campusguard.common.TargetType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationCaseRepository extends JpaRepository<ModerationCase, UUID> {

    /**
     * Open a case for this target unless one is already open.
     *
     * <p>Written as an insert that tolerates a conflict rather than as
     * "check, then insert", because two people reporting the same post at the
     * same moment can both pass a check before either writes. The partial unique
     * index decides which one wins; the loser silently does nothing and then
     * reads the winner's row.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    insert into moderation_cases (target_type, target_id, status, report_count)
                    values (:targetType, :targetId, 'QUEUED', 0)
                    on conflict do nothing
                    """,
            nativeQuery = true)
    int openCaseIfAbsent(
            @Param("targetType") String targetType, @Param("targetId") UUID targetId);

    /**
     * Only the id, deliberately.
     *
     * <p>{@link #incrementReportCount} updates the counter straight in the
     * database. Holding a managed copy of the same row across that update would
     * leave an entity whose {@code reportCount} is one behind, and dirty checking
     * would then write the stale value back over the increment.
     */
    @Query("""
            select c.id from ModerationCase c
            where c.targetType = :targetType
              and c.targetId = :targetId
              and c.status <> com.campusguard.moderation.CaseStatus.RESOLVED
            """)
    Optional<UUID> findOpenCaseId(
            @Param("targetType") TargetType targetType, @Param("targetId") UUID targetId);

    @Query("""
            select c from ModerationCase c
            where c.targetType = :targetType
              and c.targetId = :targetId
              and c.status <> com.campusguard.moderation.CaseStatus.RESOLVED
            """)
    Optional<ModerationCase> findOpenByTarget(
            @Param("targetType") TargetType targetType, @Param("targetId") UUID targetId);

    /**
     * Incremented in the database rather than by reading, adding one and saving.
     *
     * <p>Two people reporting the same post at once would both read the same
     * count and both write the same successor, losing one of the two reports from
     * the tally. Making the read and the write one statement removes the window.
     */
    @Modifying(flushAutomatically = true)
    @Query("update ModerationCase c set c.reportCount = c.reportCount + 1 where c.id = :id")
    int incrementReportCount(@Param("id") UUID id);

    /**
     * Cases claimed by a worker that then died. Without this the row sits in
     * {@code ANALYSING} forever and the report is silently never acted on, which
     * is the failure mode a durable queue is supposed to rule out.
     */
    @Query("""
            select c from ModerationCase c
            where c.status = com.campusguard.moderation.CaseStatus.ANALYSING
              and c.updatedAt < :threshold
            """)
    List<ModerationCase> findStalled(@Param("threshold") Instant threshold);

    /**
     * Take the next batch of queued cases and lock them for this transaction.
     *
     * <p>{@code SKIP LOCKED} is what makes more than one worker safe: instead of
     * queueing behind rows another instance already holds, a second worker steps
     * over them and takes the next free ones. Without it, running two instances
     * would serialise them; with a plain {@code SELECT} and no lock at all, both
     * would analyse the same case.
     *
     * <p>Native because JPQL has no way to express row locking with a skip.
     */
    @Query(
            value =
                    """
                    select * from moderation_cases
                    where status = 'QUEUED'
                    order by created_at
                    limit :batchSize
                    for update skip locked
                    """,
            nativeQuery = true)
    List<ModerationCase> claimQueued(@Param("batchSize") int batchSize);

    @Query("""
            select c from ModerationCase c
            where c.status = :status
            order by c.createdAt asc
            """)
    List<ModerationCase> findByStatus(@Param("status") CaseStatus status, Pageable pageable);

    long countByStatus(CaseStatus status);
}
