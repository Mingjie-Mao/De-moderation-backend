package com.campusguard.evaluation;

/**
 * Where a labelled sample's text came from.
 *
 * <p>Recorded per sample because the two sources have different weaknesses, and a
 * single overall score hides that.
 *
 * <p>Neither source is real traffic, and the distinction is narrower than it
 * first looks. What separates them is not authentic against invented — both were
 * written by somebody — but whether the writer knew about this evaluation.
 * Content written to populate a demo could not have been shaped to suit an
 * engine that did not exist yet; content written for the evaluation could, and
 * the author had already read the rule list.
 *
 * <p>The report breaks metrics down by this so a large gap stays visible rather
 * than averaged away. A gap means the half written for the evaluation is too easy
 * and the headline number is flattering by that much.
 */
public enum SampleProvenance {

    /**
     * Taken verbatim from the seeded content of the De-discussion app: the
     * bilingual campus-forum register this system would actually see, written to
     * fill a demo rather than to test anything.
     *
     * <p>Not real user traffic. Nobody posted it to a forum and nobody reported
     * it; it was authored to make a demo look inhabited. What it does have is
     * independence from this evaluation, which is the property the comparison
     * needs. It is also almost entirely benign, because nobody seeds a demo
     * application with abuse.
     */
    SEEDED,

    /**
     * Written for this evaluation. Unavoidable for the violating classes, and the
     * weakest part of the dataset: it measures an engine against one person's idea
     * of what a violation looks like, held by someone who had read the rules.
     */
    AUTHORED
}
