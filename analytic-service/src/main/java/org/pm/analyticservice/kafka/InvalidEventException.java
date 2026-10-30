package org.pm.analyticservice.kafka;

/**
 * The record can't be understood (bad bytes, missing or malformed ids). Retrying won't fix it, so the error
 * handler sends it straight to the dead-letter topic instead of retrying.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
