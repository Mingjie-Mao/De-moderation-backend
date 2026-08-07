package com.campusguard.evaluation;

/**
 * Where a labelled sample's text came from.
 *
 * <p>Recorded per sample because the two sources have different weaknesses, and a
 * single overall score hides that. Content lifted from the real application is in
 * the right register and was not written with any engine in mind; content written
 * for the evaluation was, and an engine can score well on it for the uninteresting
 * reason that the author and the rule set share a vocabulary.
 *
 * <p>The report breaks metrics down by this, so a large gap between the two is
 * visible rather than averaged away. A gap means the authored half is too easy and
 * the headline number is flattering.
 */
public enum SampleProvenance {

    /**
     * Taken verbatim from the seeded content of the De-discussion app: genuine
     * campus-forum posts and comments in the bilingual register this system will
     * actually see. Almost entirely benign, because nobody seeds a demo
     * application with abuse.
     */
    REAL_SEED,

    /**
     * Written for this evaluation. Unavoidable for the violating classes, and the
     * weakest part of the dataset: it measures an engine against one person's idea
     * of what a violation looks like.
     */
    AUTHORED
}
