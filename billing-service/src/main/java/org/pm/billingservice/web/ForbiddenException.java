package org.pm.billingservice.web;

/** The caller is known but its role may not do this (403). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
