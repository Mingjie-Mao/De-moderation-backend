package com.campusguard.moderation.investigation;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditEntry;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.audit.AuditLogger;
import com.campusguard.common.NotFoundException;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storing a brief and reading it back.
 *
 * <p>A bean of its own rather than two methods on {@link InvestigationService},
 * and not for tidiness. That class must not be transactional — it makes several
 * calls to a model and a transaction spanning them would hold a connection
 * across the network waits — so a {@code @Transactional} method beside them
 * would be reached by self-invocation, which does not pass through the proxy and
 * therefore is not transactional at all. {@code AuditLogger} propagates
 * {@code MANDATORY} and would refuse the write.
 *
 * <p>That is not hypothetical. It is what happened: the tests called the storing
 * method directly, which does pass through a proxy, and the failure appeared
 * only on the first real request.
 */
@Service
public class InvestigationRecorder {

    /** Kept in the audit trail rather than a table of its own; the payload column is JSONB and already exists. */
    public static final String INVESTIGATION_RECORDED = "INVESTIGATION_RECORDED";

    private final ModerationCaseRepository cases;
    private final AuditEntryRepository auditEntries;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public InvestigationRecorder(
            ModerationCaseRepository cases,
            AuditEntryRepository auditEntries,
            AuditLogger auditLogger,
            ObjectMapper objectMapper) {
        this.cases = cases;
        this.auditEntries = auditEntries;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    /**
     * The brief for this case, if one has already been produced.
     *
     * <p>Read from the audit trail, which is keyed by content rather than by
     * case, so the case id in the payload is what narrows it: one piece of
     * content can be reported, dismissed and reported again. The most recent
     * wins, because a reviewer who asked again after new reports wanted the
     * newer answer.
     */
    @Transactional(readOnly = true)
    public Optional<InvestigationBriefView> existing(UUID caseId) {
        ModerationCase moderationCase = requireCase(caseId);

        return auditEntries
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                        moderationCase.getTargetType(), moderationCase.getTargetId())
                .stream()
                .filter(entry -> INVESTIGATION_RECORDED.equals(entry.getAction()))
                .filter(entry -> caseId.toString().equals(String.valueOf(entry.getPayload().get("caseId"))))
                .max(Comparator.comparing(AuditEntry::getCreatedAt))
                .map(entry -> objectMapper.convertValue(entry.getPayload(), InvestigationBriefView.class));
    }

    /**
     * Written as an ADMIN action rather than an ENGINE one.
     *
     * <p>Nothing happens on its own here: a person asked for this, it cost their
     * budget, and the brief may end up quoted in the reasoning for a ban. The
     * trail should say who asked.
     */
    @Transactional
    public void record(UUID adminId, UUID caseId, InvestigationBriefView view) {
        ModerationCase moderationCase = requireCase(caseId);

        Map<String, Object> payload = objectMapper.convertValue(
                view, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        payload.put("caseId", caseId.toString());

        auditLogger.record(
                AuditActorType.ADMIN,
                adminId,
                INVESTIGATION_RECORDED,
                moderationCase.getTargetType(),
                moderationCase.getTargetId(),
                payload);
    }

    private ModerationCase requireCase(UUID caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new NotFoundException("No moderation case with id " + caseId));
    }
}
