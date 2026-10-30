package org.pm.authservice.dto;

/**
 * Who a valid token belongs to: the body of GET /validate (Day 12). The api-gateway forwards it to the services
 * as X-User-Id / X-User-Role, and rate-limits per subject.
 */
public class TokenIdentityDTO {
    private final String subject;
    private final String role;

    public TokenIdentityDTO(String subject, String role) {
        this.subject = subject;
        this.role = role;
    }

    public String getSubject() {
        return subject;
    }

    public String getRole() {
        return role;
    }
}
