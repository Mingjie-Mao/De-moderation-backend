package com.campusguard.moderation.investigation;

/**
 * The brief was not usable, with a message written to be read by the model.
 *
 * <p>Thrown by the parser and fed straight back as the correction, which is why
 * the message says what was wrong in the second person rather than describing a
 * field's state.
 */
public class InvalidBriefException extends RuntimeException {

    public InvalidBriefException(String message) {
        super(message);
    }
}
