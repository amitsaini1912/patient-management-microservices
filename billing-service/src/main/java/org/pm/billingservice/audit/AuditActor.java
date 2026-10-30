package org.pm.billingservice.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.pm.billingservice.idempotency.IdempotencyFilter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who caused the change being audited, from the current HTTP request (same thread as the transaction):
 *   user:<X-User-Id>   a logged-in user, identified by api-gateway from the JWT (Day 12)
 *   acquirer           a verified acquirer webhook (POST /webhooks/...)
 *   internal           an HTTP call that didn't come through the gateway (ops, tests, scripts)
 *   system             no HTTP request at all: scheduled jobs (auth expiry, revaluation)
 */
final class AuditActor {

    private static final int MAX = 100;

    private AuditActor() {}

    static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return "system";
        }
        HttpServletRequest request = attributes.getRequest();
        String user = request.getHeader(IdempotencyFilter.USER_HEADER);
        if (user != null && !user.isBlank()) {
            String actor = "user:" + user.trim();
            return actor.length() > MAX ? actor.substring(0, MAX) : actor;
        }
        if (request.getRequestURI().startsWith("/webhooks/")) {
            return "acquirer";
        }
        return "internal";
    }
}
