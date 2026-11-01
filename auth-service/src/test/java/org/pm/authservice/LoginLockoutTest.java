package org.pm.authservice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.authservice.model.User;
import org.pm.authservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR 0027: wrong passwords lock the account for a while; unknown emails cost the same bcrypt check. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(LoginLockoutTest.MovableClock.class)
class LoginLockoutTest {

    private static final String PASSWORD = "correct-horse-battery";

    /** A clock the test moves forward instead of waiting 15 minutes. */
    @TestConfiguration
    static class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-01T10:00:00Z");

        @Bean
        @Primary
        MovableClock testClock() {
            return this;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private MovableClock clock;
    @Autowired private MeterRegistry meters;

    private String email;

    @BeforeEach
    void freshUser() {
        email = "user-" + UUID.randomUUID() + "@test.com";
        User user = new User();
        user.setEmail(email);
        user.setPassword(encoder.encode(PASSWORD));
        user.setRole("USER");
        users.save(user);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheRightPasswordUntilTheLockRunsOut() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD).andExpect(status().isOk());            // 4 failures: still fine, and it resets
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }

        login(email, PASSWORD).andExpect(status().isUnauthorized());  // locked: same answer as a wrong password
        clock.advance(Duration.ofMinutes(14));
        login(email, PASSWORD).andExpect(status().isUnauthorized());  // still locked; attempts don't extend it
        clock.advance(Duration.ofMinutes(2));
        login(email, PASSWORD).andExpect(status().isOk());            // 16 min later: open again
        assertThat(meters.counter("auth.login.attempts", "outcome", "locked_now").count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void failuresOlderThanTheWindowStopCounting() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }
        clock.advance(Duration.ofMinutes(16));                        // window over: those 4 are forgotten
        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void concurrentWrongPasswordsAreAllCountedSoTheLockHolds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> login(email, "wrong-password").andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(401);            // never a 500 from the race
            }
        } finally {
            pool.shutdownNow();
        }
        login(email, PASSWORD).andExpect(status().isUnauthorized()); // without the row lock: lost updates, no lock
    }

    @Test
    void unknownEmailIsTheSame401AndTakesAsLongAsAWrongPassword() throws Exception {
        login("nobody-" + UUID.randomUUID() + "@test.com", "wrong-password").andExpect(status().isUnauthorized());

        long known = 0;
        long unknown = 0;
        for (int i = 0; i < 3; i++) { // 3 wrong passwords: below the lock
            known += timed(email);
            unknown += timed("nobody-" + UUID.randomUUID() + "@test.com");
        }
        // before ADR 0027 an unknown email skipped bcrypt (cost 12 = ~200 ms): well under half the time
        assertThat(unknown).isGreaterThan(known / 2);
    }

    private long timed(String who) throws Exception {
        long start = System.nanoTime();
        login(who, "wrong-password").andExpect(status().isUnauthorized());
        return System.nanoTime() - start;
    }

    private org.springframework.test.web.servlet.ResultActions login(String who, String password) throws Exception {
        return mvc.perform(post("/login").contentType("application/json")
                .content("{\"email\":\"" + who + "\",\"password\":\"" + password + "\"}"));
    }
}
