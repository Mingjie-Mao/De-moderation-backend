package com.campusguard.moderation.engine.ai;

public enum InvocationStatus {

    /** A model answered and the answer survived validation. */
    SUCCESS,

    /** A model answered with something that was not a usable verdict. */
    INVALID_RESPONSE,

    /** The call did not come back within the budget. */
    TIMEOUT,

    /** Refused locally without being sent, because recent calls were failing. */
    CIRCUIT_OPEN,

    /**
     * The provider refused because we are asking too fast.
     *
     * <p>Kept apart from {@link #ERROR} because it is the one failure that waiting
     * fixes. A refused credential will still be refused in thirty seconds; a 429
     * usually will not. Counted together, a healthy service under load and a dead
     * one produce the same failure rate.
     */
    RATE_LIMITED,

    /** Anything else: transport failure, refused credentials, rate limiting. */
    ERROR
}
