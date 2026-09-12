package com.campusguard.moderation.investigation;

import com.campusguard.auth.RequestRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Running an investigation once, and remembering that it ran.
 *
 * <p>Sits between the controller and the loop because two things have to happen
 * around the loop and neither belongs inside it: the result is written to the
 * audit trail, and a case already investigated is not investigated again.
 *
 * <p>The second is not an optimisation. Cases are worked by more than one
 * reviewer and reassigned between them, so without it the second person to open
 * a case pays for the same lookups again and — because a model's output varies
 * between runs — may be shown a different recommendation than their colleague
 * saw, with nothing on screen to say why they disagree.
 *
 * <p>Deliberately not transactional. The loop makes several calls to a model,
 * and holding a database connection across them is what
 * {@code ModerationCaseProcessor} splits its own transactions to avoid. The
 * lookups take short read-only transactions inside {@link ToolRegistry}, and
 * storing the result is {@link InvestigationRecorder}'s own.
 */
@Service
public class InvestigationService {

    /**
     * Absent when the assistant is switched off, which is the default.
     *
     * <p>An {@code ObjectProvider} rather than a hard dependency so that this
     * service, the controller and the audit action all exist either way. The
     * endpoint then answers "this is not enabled" instead of not existing, which
     * is far easier to diagnose from the console.
     */
    private final ObjectProvider<Investigator> investigator;

    private final InvestigationRecorder recorder;
    private final InvestigatorProperties properties;
    private final RequestRateLimiter rateLimiter;

    public InvestigationService(
            ObjectProvider<Investigator> investigator,
            InvestigationRecorder recorder,
            InvestigatorProperties properties,
            RequestRateLimiter rateLimiter) {
        this.investigator = investigator;
        this.recorder = recorder;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    public Optional<InvestigationBriefView> existingBrief(UUID caseId) {
        return recorder.existing(caseId);
    }

    /** Run the assistant, unless it has already run. */
    public InvestigationBriefView investigate(UUID adminId, UUID caseId, boolean force) {
        if (!force) {
            Optional<InvestigationBriefView> existing = recorder.existing(caseId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Investigator loop = investigator.getIfAvailable();
        if (loop == null) {
            throw new InvestigationNotEnabledException();
        }

        // Counted after the cache check and before the call, so returning a brief
        // somebody already paid for is free and only a real model call is
        // charged. Counted per reviewer rather than per case: a case is capped at
        // one investigation anyway unless it is forced, and forcing is exactly
        // the path worth bounding.
        rateLimiter.consume(
                "investigate-admin",
                String.valueOf(adminId),
                properties.perReviewerPerHour(),
                Duration.ofHours(1),
                "You have started %d investigations in the last hour, which is the limit. Each one calls a model."
                        .formatted(properties.perReviewerPerHour()));

        InvestigationBriefView view = InvestigationBriefView.of(
                loop.investigate(caseId), properties.promptVersion(), Instant.now());

        recorder.record(adminId, caseId, view);
        return view;
    }
}
