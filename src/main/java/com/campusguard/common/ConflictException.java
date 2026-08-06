package com.campusguard.common;

/**
 * A request that is well-formed and authorised but collides with existing state,
 * such as reporting the same target twice from one account.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
