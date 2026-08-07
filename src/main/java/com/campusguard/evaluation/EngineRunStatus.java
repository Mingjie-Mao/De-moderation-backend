package com.campusguard.evaluation;

/**
 * How a single engine's run went, kept separate from how well it scored.
 *
 * <p>An engine with no credentials scores zero on everything, which on a bare
 * table is indistinguishable from an engine that answered every question wrongly.
 * They call for opposite responses, so the report has to tell them apart.
 */
public enum EngineRunStatus {

    /** Every sample was answered. */
    OK,

    /** Some samples failed. The scores below cover only the ones that did not. */
    DEGRADED,

    /** Nothing was answered. There are no scores here, only a reason. */
    UNAVAILABLE
}
