package org.pm.billingservice.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Role checks on X-User-Role, which the api-gateway sets from auth-service's answer for the caller's JWT and strips
 * from what the client sent (Day 12). Trust boundary: billing must only be reachable through the gateway.
 * ADMIN-only: payment reviews, chargebacks (Day 19), reconciliation and FX writes (ADR 0031).
 */
public final class Roles {

    public static final String ROLE_HEADER = "X-User-Role";
    public static final String ADMIN = "ADMIN";

    private Roles() {}

    /** 403 (ForbiddenException) unless the caller is an ADMIN. */
    public static void requireAdmin(HttpServletRequest http, String what) {
        if (!ADMIN.equals(http.getHeader(ROLE_HEADER))) {
            throw new ForbiddenException(what + " needs the ADMIN role");
        }
    }
}
