package com.campusguard.moderation.engine.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiInvocationRepository extends JpaRepository<AiInvocation, UUID> {

    List<AiInvocation> findByCaseIdOrderByAttemptAsc(UUID caseId);

    long countByStatus(InvocationStatus status);
}
