package org.pm.cardvault.security;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String caller, Permission permission) {
        super(caller + " may not " + permission.name().toLowerCase().replace('_', ' '));
    }
}
