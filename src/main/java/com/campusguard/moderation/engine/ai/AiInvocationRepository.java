package com.campusguard.moderation.engine.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AiInvocationRepository extends JpaRepository<AiInvocation, UUID> {

    List<AiInvocation> findByCaseIdOrderByAttemptAsc(UUID caseId);

    long countByStatus(InvocationStatus status);

    /**
     * Native because the percentile is. {@code percentile_disc} returns a latency
     * that an actual call took rather than an interpolation between two, which is
     * the same choice the benchmark makes, and JPQL cannot express it at all.
     */
    @Query(
            value =
                    """
                    select engine                                                              as engine,
                           count(*)                                                            as calls,
                           count(*) filter (where status = 'SUCCESS')                          as successes,
                           count(*) filter (where status <> 'SUCCESS')                         as failures,
                           coalesce(avg(latency_ms), 0)                                        as avg_latency_ms,
                           coalesce(percentile_disc(0.95) within group (order by latency_ms), 0) as p95_latency_ms,
                           coalesce(sum(prompt_tokens), 0)                                     as prompt_tokens,
                           coalesce(sum(completion_tokens), 0)                                 as completion_tokens
                    from ai_invocations
                    group by engine
                    order by engine
                    """,
            nativeQuery = true)
    List<EngineInvocationStats> statsByEngine();
}
