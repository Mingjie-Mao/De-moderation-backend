package com.campusguard.audit;

import com.campusguard.common.TargetType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    List<AuditEntry> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType targetType, UUID targetId);
}
