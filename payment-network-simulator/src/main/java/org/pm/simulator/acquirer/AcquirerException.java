package org.pm.simulator.acquirer;

import org.springframework.http.HttpStatus;

/** A refusal with an HTTP status and a stable machine-readable code, like a real PSP's error object. */
public class AcquirerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AcquirerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
