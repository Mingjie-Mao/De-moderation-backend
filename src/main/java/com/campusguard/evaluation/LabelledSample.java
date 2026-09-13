package com.campusguard.evaluation;

import com.campusguard.moderation.ModerationDecision;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One hand-labelled piece of content.
 *
 * @param category the five-way editorial label: NORMAL, ABUSE, SPAM, ILLEGAL or
 *     BORDERLINE. Kept because it says what kind of mistake an engine made, which
 *     a three-way action cannot.
 * @param expected the action a correct system would take. Metrics are reported
 *     over this rather than over {@code category}, because with a couple of
 *     hundred samples split five ways each class has too few members for its F1
 *     to mean much, and because these three are what the system can actually do.
 * @param provenance whether the text is real application content or was written
 *     for the evaluation. Defaults to {@link SampleProvenance#AUTHORED}, the less
 *     flattering assumption, so an unlabelled sample cannot quietly claim to be
 *     real.
 * @param pairId names the minimal pair this sample belongs to, or null when it
 *     stands alone.
 *     <p>Two samples sharing a pair id are the same post with one deliberate
 *     difference, and they carry different expected actions. Scoring them as a
 *     unit asks a question a per-sample average cannot: not "how often is the
 *     engine right" but "can it tell these two apart". An engine that answers by
 *     topic — anything about exams is suspicious, anything about lost keys is
 *     fine — scores well per sample on a set where topic predicts label, and
 *     scores at chance on pairs where it does not.
 *     <p>It is also the fix for the flaw the 192-sample set has and cannot lose:
 *     there, provenance almost perfectly predicts the label, so a good score is
 *     partly a reward for noticing which half a sample came from. Within a pair
 *     both halves have one author, one topic and one register, and the only thing
 *     left to notice is the thing being measured.
 * @param note why this sample was labelled the way it was; only read by humans,
 *     and the thing that makes a disputed label settleable. For a paired sample
 *     it says what the edit was, so the label can be checked against the policy
 *     rather than against the labeller's taste.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelledSample(
        String id,
        String category,
        ModerationDecision expected,
        String title,
        String body,
        SampleProvenance provenance,
        String pairId,
        String note) {

    public LabelledSample {
        provenance = provenance == null ? SampleProvenance.AUTHORED : provenance;
    }

    public boolean paired() {
        return pairId != null && !pairId.isBlank();
    }
}
