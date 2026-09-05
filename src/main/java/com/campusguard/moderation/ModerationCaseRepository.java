package com.campusguard.moderation;

import com.campusguard.common.TargetType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface ModerationCaseRepository extends JpaRepository<ModerationCase, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ModerationCase c where c.id = :id")
    Optional<ModerationCase> findByIdForUpdate(@Param("id") UUID id);

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
            left join fetch c.assignedTo
            left join fetch c.decidedBy
            where c.status = :status
            order by c.createdAt asc
            """)
    List<ModerationCase> findByStatus(@Param("status") CaseStatus status, Pageable pageable);

    long countByStatus(CaseStatus status);

    long countByStatusAndReviewDueAtBefore(CaseStatus status, Instant threshold);

    /**
     * Every other case whose standing outcome is a ban.
     *
     * <p>Read when a ban is being undone. An account can be banned by more than
     * one case, and lifting one of them must not quietly lift the rest: the
     * author would walk free on the strength of the mildest complaint against
     * them.
     */
    @Query("""
            select c from ModerationCase c
            where c.status = com.campusguard.moderation.CaseStatus.RESOLVED
              and c.finalAction = com.campusguard.moderation.FinalAction.BAN
              and c.id <> :excludedCaseId
            """)
    List<ModerationCase> findOtherStandingBans(@Param("excludedCaseId") UUID excludedCaseId);

    /**
     * Resolved cases about any of these targets, most recent first.
     *
     * <p>Paired with {@code ContentLocator.targetsOf}, this is one author's
     * moderation history. Split in two rather than expressed as a join because
     * a case points at a bare id: joining would mean a union across posts and
     * comments inside every query that wants a history, and the branch on target
     * type already has one home.
     *
     * <p>Callers must not pass an empty collection. Hibernate renders
     * {@code in ()} for one, which is a syntax error in PostgreSQL, and the
     * caller knows the list is empty before it asks.
     */
    @Query("""
            select c from ModerationCase c
            where c.status = com.campusguard.moderation.CaseStatus.RESOLVED
              and c.targetId in :targetIds
              and c.decidedAt >= :since
            order by c.decidedAt desc
            """)
    List<ModerationCase> findResolvedForTargets(
            @Param("targetIds") Collection<UUID> targetIds, @Param("since") Instant since);

    /**
     * How this rule has actually been enforced, most recent first.
     *
     * <p>Precedent rather than policy. What a rule says is in {@code
     * moderation_rules}; what reviewers have done about it is only recoverable
     * from the decisions themselves, and the two do diverge.
     *
     * <p>Native because {@code rule_codes} is JSONB and JPQL has no containment
     * operator. Matching against a bare string relies on PostgreSQL's documented
     * exception for arrays, so no array literal has to be built to test one code.
     *
     * <p>{@code final_action <> 'NONE'} keeps dismissed reports out. Those say a
     * reviewer looked and decided nothing was wrong, which is evidence about the
     * report and not a precedent for what an outcome should be. The predicate
     * matches {@code idx_moderation_cases_rule_codes} exactly.
     */
    @Query(
            value =
                    """
                    select * from moderation_cases
                    where status = 'RESOLVED'
                      and final_action is not null
                      and final_action <> 'NONE'
                      and rule_codes @> to_jsonb(cast(:code as text))
                    order by decided_at desc
                    limit :limit
                    """,
            nativeQuery = true)
    List<ModerationCase> findResolvedByRuleCode(@Param("code") String code, @Param("limit") int limit);
}
