package org.pm.authservice.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pm.authservice.dto.LoginRequestDTO;
import org.pm.authservice.dto.TokenIdentityDTO;
import org.pm.authservice.model.User;
import org.pm.authservice.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login with brute-force protection (ADR 0027; the gateway also limits /auth/login per client IP):
 *   - every attempt costs exactly ONE bcrypt check - also for an unknown email (against a dummy hash), so the response
 *     time doesn't tell an attacker which emails have accounts (before: unknown email = no bcrypt = much faster);
 *   - {@code maxFailures} wrong passwords within {@code window} lock the account for {@code lockFor}; while locked
 *     every login is refused, the right password too. A refusal is the same 401 as a wrong password (no "locked"
 *     answer to probe which accounts exist); the lock is logged and counted instead.
 * Counting happens under a row lock (concurrent wrong passwords are all counted), after the slow bcrypt check.
 */
@Service
@EnableConfigurationProperties(LoginProperties.class)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final EntityManager entityManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final LoginProperties properties;
    private final MeterRegistry meters;
    private final String dummyHash;

    public AuthService(UserService userService, EntityManager entityManager, PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil, TransactionTemplate transactions, Clock clock, LoginProperties properties,
                       MeterRegistry meters) {
        this.userService = userService;
        this.entityManager = entityManager;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
        this.meters = meters;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString()); // same cost as real hashes
    }

    public Optional<String> authenticate(LoginRequestDTO loginRequestDTO) {
        String password = loginRequestDTO.getPassword() == null ? "" : loginRequestDTO.getPassword();
        Optional<User> found = loginRequestDTO.getEmail() == null ? Optional.empty()
                : userService.findByEmail(loginRequestDTO.getEmail());
        boolean passwordMatches = passwordEncoder.matches(password, found.map(User::getPassword).orElse(dummyHash));
        if (found.isEmpty()) {
            count("unknown_user");
            return Optional.empty();
        }

        Outcome outcome = transactions.execute(tx -> record(found.get().getId(), passwordMatches));
        count(outcome.name().toLowerCase());
        if (outcome == Outcome.LOCKED_NOW) {
            log.warn("account {} locked for {} after {} wrong passwords", found.get().getId(), properties.lockFor(),
                    properties.maxFailures());
        }
        return outcome == Outcome.SUCCESS
                ? Optional.of(jwtUtil.generateToken(found.get().getEmail(), found.get().getRole()))
                : Optional.empty();
    }

    enum Outcome { SUCCESS, BAD_PASSWORD, LOCKED_NOW, LOCKED }

    private Outcome record(UUID userId, boolean passwordMatches) {
        // refresh under a row lock, not a plain locked find: with open-session-in-view (the test config has it) the
        // user loaded at the start of this request is still in the persistence context, and a find would return that
        // stale copy - concurrent wrong passwords were lost and the account never locked (found by the test)
        User user = entityManager.find(User.class, userId);
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        Instant now = clock.instant();
        if (user.isLockedAt(now)) {
            return Outcome.LOCKED; // not counted: the lock doesn't get longer, it just runs out
        }
        if (passwordMatches) {
            user.loginSucceeded();
            return Outcome.SUCCESS;
        }
        return user.loginFailed(now, properties.maxFailures(), properties.window(), properties.lockFor())
                ? Outcome.LOCKED_NOW : Outcome.BAD_PASSWORD;
    }

    private void count(String outcome) {
        meters.counter("auth.login.attempts", "outcome", outcome).increment();
    }

    /** Empty for an invalid/expired token; otherwise who it belongs to. */
    public Optional<TokenIdentityDTO> validateToken(String token) {
        try {
            Claims claims = jwtUtil.validateToken(token);
            return Optional.of(new TokenIdentityDTO(claims.getSubject(), claims.get("role", String.class)));
        } catch (JwtException e){
            return Optional.empty();
        }
    }
}
