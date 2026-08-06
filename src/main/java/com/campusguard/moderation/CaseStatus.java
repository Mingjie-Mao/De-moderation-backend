package com.campusguard.moderation;

/**
 * Where a case sits in the workflow.
 *
 * <p>The plan originally separated "an engine has judged it" from "an
 * administrator is looking at it". Those collapsed into a single
 * {@link #AWAITING_REVIEW}, because nothing in the system claims a case for one
 * administrator: with no claim there is no transition to trigger the second
 * state, and a state nothing can move into is a state that lies about the
 * workflow. A claim step would reintroduce it.
 */
public enum CaseStatus {

    /** Reported, waiting for the worker to pick it up. */
    QUEUED,

    /** Claimed by a worker. Any case left here after a crash is retried. */
    ANALYSING,

    /** An engine has produced a verdict. A human decides what actually happens. */
    AWAITING_REVIEW,

    /** Closed, with a recorded action and the administrator who took it. */
    RESOLVED
}
