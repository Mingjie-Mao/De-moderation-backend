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

    /** Anything else: transport failure, refused credentials, rate limiting. */
    ERROR
}
