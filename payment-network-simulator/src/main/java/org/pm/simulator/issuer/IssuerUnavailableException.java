package org.pm.simulator.issuer;

/** The issuer's host is down: it never answers. The network turns this into response code 91. */
public class IssuerUnavailableException extends RuntimeException {

    public IssuerUnavailableException(String message) {
        super(message);
    }
}
