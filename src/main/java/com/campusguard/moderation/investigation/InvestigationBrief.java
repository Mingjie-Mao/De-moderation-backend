package com.campusguard.moderation.investigation;

import com.campusguard.moderation.FinalAction;
import java.util.List;
import java.util.UUID;

/**
 * What the reviewer is handed.
 *
 * <p>Three shapes, and none of them is an exception. The reviewer is mid-decision
 * with a queue behind them; an assistant that could not finish should say so in a
 * sentence they can read past, not surface as an error page. This mirrors
 * {@code ModerationCaseProcessor}, where every path out of automated analysis
 * ends with a case a person can act on, including the paths where the engine
 * failed.
 */
public sealed interface InvestigationBrief {

    /** Always safe to display. */
    String summary();

    /** Cases the brief refers to, each one verified to have actually been read. */
    List<UUID> citedCaseIds();

    /**
     * The investigation ran to a conclusion.
     *
     * @param recommendation a suggestion and nothing more. Acting on it is still
     *     {@code AdminModerationService.decide}, which remains the only place
     *     content is removed or an account banned.
     * @param evidenceStrength how far what was found settles the question. A band
     *     rather than a number, for the reasons in {@link EvidenceStrength}.
     * @param counterEvidence the case against the recommendation, required rather
     *     than optional. A brief that only argues one way reads as authority, and
     *     the reviewer's job is to disagree with it when it is wrong — which they
     *     cannot do from an argument that never mentions its own weak points.
     */
    record Complete(
            String summary,
            FinalAction recommendation,
            EvidenceStrength evidenceStrength,
            String counterEvidence,
            List<UUID> citedCaseIds)
            implements InvestigationBrief {

        public Complete {
            citedCaseIds = List.copyOf(citedCaseIds);
        }
    }

    /**
     * The model stopped answering part way through.
     *
     * <p>Carries what was gathered before it stopped, because half a history is
     * still worth reading as long as it is labelled as half.
     */
    record Partial(String summary, List<UUID> citedCaseIds, String why) implements InvestigationBrief {

        public Partial {
            citedCaseIds = List.copyOf(citedCaseIds);
        }
    }

    /** It ran, and produced nothing usable: out of steps, or unable to write a brief that held up. */
    record Inconclusive(String why) implements InvestigationBrief {

        @Override
        public String summary() {
            return why;
        }

        @Override
        public List<UUID> citedCaseIds() {
            return List.of();
        }
    }
}
