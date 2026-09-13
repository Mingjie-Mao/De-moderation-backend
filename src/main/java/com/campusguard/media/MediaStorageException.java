package com.campusguard.media;

/**
 * A storage backend could not do what it was asked.
 *
 * <p>Unchecked, and deliberately not carrying an HTTP status. Whether a failed
 * write is a bad request or a server fault is a question about the caller, not
 * about the disk, and {@link MediaService} is the only place with enough context
 * to answer it.
 */
public class MediaStorageException extends RuntimeException {

    public MediaStorageException(String message) {
        super(message);
    }

    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
