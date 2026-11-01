package org.pm.authservice.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * auth.login.* - account lockout (ADR 0027).
 *
 * @param maxFailures wrong passwords within {@code window} that lock the account
 * @param window      how long failures keep counting after the first one
 * @param lockFor     how long a locked account refuses every login, also with the right password
 */
@ConfigurationProperties("auth.login")
public record LoginProperties(
        @DefaultValue("5") int maxFailures,
        @DefaultValue("15m") Duration window,
        @DefaultValue("15m") Duration lockFor) {
}
