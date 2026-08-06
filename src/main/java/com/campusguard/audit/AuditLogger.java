package com.campusguard.audit;

import com.campusguard.common.TargetType;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way anything gets written to the audit trail.
 *
 * <p>Entries join the caller's transaction rather than running in their own. If
 * the action being described rolls back, it did not happen, and a log that
 * records things that did not happen is worse than no log. The trade-off is that
 * this only records what succeeded; a record of rejected attempts would need the
 * opposite choice, and belongs with the security layer rather than here.
 */
@Service
public class AuditLogger {

    public static final String REPORT_FILED = "REPORT_FILED";
    public static final String CASE_OPENED = "CASE_OPENED";
    public static final String CASE_CLAIMED = "CASE_CLAIMED";
    public static final String VERDICT_RECORDED = "VERDICT_RECORDED";
    public static final String ANALYSIS_FAILED = "ANALYSIS_FAILED";
    public static final String CASE_RESOLVED = "CASE_RESOLVED";
    public static final String CONTENT_HIDDEN = "CONTENT_HIDDEN";
    public static final String AUTHOR_BANNED = "AUTHOR_BANNED";

    private final AuditEntryRepository repository;

    public AuditLogger(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            AuditActorType actorType,
            UUID actorId,
            String action,
            TargetType targetType,
            UUID targetId,
            Map<String, Object> payload) {

        repository.save(new AuditEntry(actorType, actorId, action, targetType, targetId, payload));
    }
}
