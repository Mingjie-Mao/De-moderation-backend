package com.campusguard.moderation.investigation;

import java.util.UUID;

/**
 * Something that produces a brief about a case.
 *
 * <p>An interface because there are now two: one that asks a model once, and one
 * that asks several times and reports what they agreed on. Everything above —
 * the service, the caching, the endpoint — should not be able to tell which it
 * has, and adding a third should not touch any of them.
 */
public interface Investigator {

    InvestigationBrief investigate(UUID caseId);
}
