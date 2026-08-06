package com.campusguard.moderation.rule;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationRuleRepository extends JpaRepository<ModerationRule, UUID> {

    List<ModerationRule> findByActiveTrueOrderByCodeAsc();
}
