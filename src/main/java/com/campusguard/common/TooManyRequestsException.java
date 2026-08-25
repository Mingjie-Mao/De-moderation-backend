package com.campusguard.common;

/**
 * The caller is allowed to do this, just not this often.
 *
 * <p>Distinct from a conflict or a denial because the correct client behaviour is
 * different: wait and try again, rather than change the request or give up.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
