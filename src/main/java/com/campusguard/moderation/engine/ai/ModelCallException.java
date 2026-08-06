package com.campusguard.moderation.engine.ai;

/**
 * A model call that did not produce a usable answer.
 *
 * <p>Carries the reason as a status rather than only a message, because the
 * reason is what gets counted: a week of timeouts and a week of refused
 * credentials are the same number of failures and completely different problems.
 */
public class ModelCallException extends RuntimeException {

    private final InvocationStatus status;

    public ModelCallException(InvocationStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public ModelCallException(InvocationStatus status, String message) {
        this(status, message, null);
    }

    public InvocationStatus status() {
        return status;
    }
}
