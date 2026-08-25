package com.campusguard.moderation.engine.ai;

/**
 * The model answered, but not with something that could be stored as a verdict.
 *
 * <p>The message is written for the model rather than for a log reader: it is
 * sent back as the correction on the retry, so "Field 'confidence' was 1.7. It
 * must be between 0 and 1 inclusive." is worth more than "validation failed".
 */
public class InvalidVerdictException extends RuntimeException {

    public InvalidVerdictException(String message) {
        super(message);
    }
}
