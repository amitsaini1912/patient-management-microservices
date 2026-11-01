package org.pm.authservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;

    // Login lockout (ADR 0027). Nullable: ddl-auto=update adds them to existing rows; null = no failures / not locked.
    @Column(name = "failed_logins")
    private Integer failedLogins;

    @Column(name = "first_failed_login_at")
    private Instant firstFailedLoginAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    /** A good password: the failure count starts again. */
    public void loginSucceeded() {
        failedLogins = null;
        firstFailedLoginAt = null;
        lockedUntil = null;
    }

    /**
     * A wrong password. Failures count within {@code window} of the first one; the {@code maxFailures}-th locks the
     * account for {@code lockFor}. Returns true when this failure locked it.
     */
    public boolean loginFailed(Instant now, int maxFailures, Duration window, Duration lockFor) {
        if (firstFailedLoginAt == null || !now.isBefore(firstFailedLoginAt.plus(window))) {
            firstFailedLoginAt = now;
            failedLogins = 0;
        }
        failedLogins = failedLogins + 1;
        if (failedLogins >= maxFailures) {
            lockedUntil = now.plus(lockFor);
            failedLogins = null;
            firstFailedLoginAt = null;
            return true;
        }
        return false;
    }
}
