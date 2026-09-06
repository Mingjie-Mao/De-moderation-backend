package com.campusguard.moderation.investigation;

import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditEntry;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.audit.AuditLogger;
import com.campusguard.common.NotFoundException;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Running an investigation once, and remembering that it ran.
 *
 * <p>Sits between the controller and the loop because two things have to happen
 * around the loop and neither belongs inside it: the result is written to the
 * audit trail, and a case already investigated is not investigated again.
 *
 * <p>The second is not an optimisation. Cases are worked by more than one
 * reviewer and reassigned between them, so without it the second person to open
 * a case pays for the same lookups a second time and — because the model's tool
 * choices vary between runs — may be shown a different recommendation than their
 * colleague saw, with nothing on screen to say why they disagree.
 */
@Service
public class InvestigationService {

    /** Kept in the audit trail rather than a table of its own; the payload column is JSONB and already exists. */
    public static final String INVESTIGATION_RECORDED = "INVESTIGATION_RECORDED";

    /**
     * Absent when the assistant is switched off, which is the default.
     *
     * <p>An {@code ObjectProvider} rather than a hard dependency so that this
     * service, the controller and the audit action all exist either way. The
     * endpoint then answers "this is not enabled" instead of not existing, which
     * is a far easier thing to diagnose from the console.
     */
    private final ObjectProvider<CaseInvestigator> investigator;

    private final ModerationCaseRepository cases;
    private final AuditEntryRepository auditEntries;
    private final AuditLogger auditLogger;
    private final InvestigatorProperties properties;
    private final ObjectMapper objectMapper;

    public InvestigationService(
            ObjectProvider<CaseInvestigator> investigator,
            ModerationCaseRepository cases,
            AuditEntryRepository auditEntries,
            AuditLogger auditLogger,
            InvestigatorProperties properties,
            ObjectMapper objectMapper) {
        this.investigator = investigator;
        this.cases = cases;
        this.auditEntries = auditEntries;
        this.auditLogger = auditLogger;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return investigator.getIfAvailable() != null;
    }

    /**
     * The brief for this case, if one has already been produced.
     *
     * <p>Read from the audit trail, which is keyed by content rather than by
     * case, so the case id in the payload is what narrows it. The most recent
     * wins: a reviewer who asked again after new reports arrived wanted the newer
     * answer.
     */
    @Transactional(readOnly = true)
    public Optional<InvestigationBriefView> existingBrief(UUID caseId) {
        ModerationCase moderationCase = requireCase(caseId);

        return auditEntries
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                        moderationCase.getTargetType(), moderationCase.getTargetId())
                .stream()
                .filter(entry -> INVESTIGATION_RECORDED.equals(entry.getAction()))
                .filter(entry -> caseId.toString().equals(String.valueOf(entry.getPayload().get("caseId"))))
                .max(Comparator.comparing(AuditEntry::getCreatedAt))
                .map(this::toView);
    }

    /**
     * Run the assistant, unless it has already run.
     *
     * <p>Not transactional, and that is the point: the loop makes several calls
     * to a model, and holding a database connection across them is the thing
     * {@code ModerationCaseProcessor} splits its own transactions to avoid. The
     * lookups take their own read-only transactions inside {@link ToolRegistry},
     * and the result is written in a short one at the end.
     */
    public InvestigationBriefView investigate(UUID adminId, UUID caseId, boolean force) {
        if (!force) {
            Optional<InvestigationBriefView> existing = existingBrief(caseId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        CaseInvestigator loop = investigator.getIfAvailable();
        if (loop == null) {
            throw new InvestigationNotEnabledException();
        }

        InvestigationBrief brief = loop.investigate(caseId);
        InvestigationBriefView view =
                InvestigationBriefView.of(brief, properties.promptVersion(), Instant.now());

        record(adminId, caseId, view);
        return view;
    }

    /**
     * Written as an audit entry, and by an ADMIN actor rather than an ENGINE one.
     *
     * <p>Nothing happens on its own here: a person asked for this, it cost their
     * budget, and the brief may end up quoted in the reasoning for a ban. The
     * trail should say who asked.
     */
    @Transactional
    public void record(UUID adminId, UUID caseId, InvestigationBriefView view) {
        ModerationCase moderationCase = requireCase(caseId);

        Map<String, Object> payload = objectMapper.convertValue(view, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        payload.put("caseId", caseId.toString());

        auditLogger.record(
                AuditActorType.ADMIN,
                adminId,
                INVESTIGATION_RECORDED,
                moderationCase.getTargetType(),
                moderationCase.getTargetId(),
                payload);
    }

    private InvestigationBriefView toView(AuditEntry entry) {
        return objectMapper.convertValue(entry.getPayload(), InvestigationBriefView.class);
    }

    private ModerationCase requireCase(UUID caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new NotFoundException("No moderation case with id " + caseId));
    }
}
