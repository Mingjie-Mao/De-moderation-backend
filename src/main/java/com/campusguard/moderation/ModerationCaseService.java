package com.campusguard.moderation;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditLogger;
import com.campusguard.common.TargetType;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns reports into the single unit of work an administrator acts on.
 *
 * <p>Several people reporting the same post has to produce one case, not one per
 * reporter: it is one decision to make, and once an engine costs money per call
 * it is also one call to pay for.
 */
@Service
public class ModerationCaseService {

    private final ModerationCaseRepository caseRepository;
    private final AuditLogger auditLogger;

    public ModerationCaseService(ModerationCaseRepository caseRepository, AuditLogger auditLogger) {
        this.caseRepository = caseRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * Attach a new report to the open case for this target, opening one if there
     * is none.
     *
     * <p>Runs inside the caller's transaction, so a case is never opened for a
     * report that then fails to save.
     *
     * <p>The insert tolerates a conflict instead of being guarded by a prior
     * check, because two reports on the same content can be in flight at once and
     * both would see no open case. Which of them creates the row is settled by
     * the partial unique index; the other one's insert affects no rows and it
     * reads the winner's case instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openOrJoinCase(TargetType targetType, UUID targetId) {
        int inserted = caseRepository.openCaseIfAbsent(targetType.name(), targetId);

        UUID caseId = caseRepository
                .findOpenCaseId(targetType, targetId)
                .orElseThrow(() -> new IllegalStateException(
                        "No open case for " + targetType + " " + targetId + " immediately after opening one."));

        caseRepository.incrementReportCount(caseId);

        if (inserted > 0) {
            auditLogger.record(
                    AuditActorType.SYSTEM,
                    null,
                    AuditLogger.CASE_OPENED,
                    targetType,
                    targetId,
                    Map.of("caseId", caseId.toString()));
        }

        return caseId;
    }
}
