package com.campusguard.moderation.investigation;

import com.campusguard.moderation.FinalAction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A brief as the console sees it, and as the audit trail stores it.
 *
 * <p>Flat where {@link InvestigationBrief} is a sealed hierarchy, because this
 * one has to survive a round trip through a JSONB column. A sealed interface
 * needs type information to come back out, and encoding that into the audit
 * payload would tie the shape of stored history to the shape of today's Java.
 * An {@code outcome} string is read the same way in five years.
 *
 * <p>Three of the fields are null unless the investigation concluded. That is
 * deliberately visible rather than smoothed over: a console that renders a
 * recommendation must not be able to render one that was never made.
 *
 * @param promptVersion stored alongside, so a brief read back next month is
 *     still attributable to the wording that wrote it
 */
public record InvestigationBriefView(
        String outcome,
        String summary,
        FinalAction recommendation,
        EvidenceStrength evidenceStrength,
        String counterEvidence,
        List<UUID> citedCaseIds,
        String promptVersion,
        Instant producedAt) {

    public static final String COMPLETE = "COMPLETE";
    public static final String PARTIAL = "PARTIAL";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";

    public static InvestigationBriefView of(InvestigationBrief brief, String promptVersion, Instant producedAt) {
        return switch (brief) {
            case InvestigationBrief.Complete complete -> new InvestigationBriefView(
                    COMPLETE,
                    complete.summary(),
                    complete.recommendation(),
                    complete.evidenceStrength(),
                    complete.counterEvidence(),
                    complete.citedCaseIds(),
                    promptVersion,
                    producedAt);

            case InvestigationBrief.Partial partial -> new InvestigationBriefView(
                    PARTIAL, partial.summary(), null, null, null, partial.citedCaseIds(),
                    promptVersion, producedAt);

            case InvestigationBrief.Inconclusive inconclusive -> new InvestigationBriefView(
                    INCONCLUSIVE, inconclusive.summary(), null, null, null, List.of(),
                    promptVersion, producedAt);
        };
    }
}
