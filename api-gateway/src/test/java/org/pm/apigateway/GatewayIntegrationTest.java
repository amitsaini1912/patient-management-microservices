package org.pm.apigateway;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway over real HTTP with a real Redis. auth-service and the backends are JDK stub servers
 * ({@link GatewayTestSupport}). Small limits so a burst is a handful of requests:
 * payments 1 token/s, bucket 3; cards 1 token/s, bucket 2. Every test uses its own user = its own bucket.
 *
 * The built-in limiter's Lua script reads Redis TIME in WHOLE seconds, so tokens come back in steps at each second
 * boundary. A burst that crosses a boundary gets an extra token; tests that count tokens therefore start right
 * after a boundary ({@link #startOfNextSecond()}). Redis runs on this machine's clock, so the boundaries line up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GATEWAY_PAYMENTS_RATE_PER_SECOND=1", "GATEWAY_PAYMENTS_BURST=3",
        "GATEWAY_CARDS_RATE_PER_SECOND=1", "GATEWAY_CARDS_BURST=2"})
@AutoConfigureWebTestClient
@Import(RedisTestcontainer.class)
class GatewayIntegrationTest extends GatewayTestSupport {

    @Autowired private WebTestClient web;

    @BeforeEach
    void reset() {
        received.clear();
        authStatusOverride = 0;
    }

    // ---- JWT (weakness #7) ----

    @Test
    void missingOrRejectedTokenIs401AndNeverReachesTheBackend() {
        web.get().uri("/api/payments/1").exchange()
                .expectStatus().isUnauthorized().expectHeader().valueEquals("WWW-Authenticate", "Bearer");
        web.get().uri("/api/payments/1").header("Authorization", "Bearer " + INVALID_TOKEN).exchange()
                .expectStatus().isUnauthorized();
        assertThat(received).isEmpty();
    }

    @Test
    void brokenAuthServiceIs503Not500() {
        authStatusOverride = 500;
        web.get().uri("/api/payments/1").header("Authorization", "Bearer valid-" + user()).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(received).isEmpty();
    }

    // ---- identity headers ----

    @Test
    void backendGetsTheUserFromAuthServiceNotFromTheClient() {
        String alice = user();
        web.get().uri("/api/payments/1").header("Authorization", "Bearer valid-" + alice)
                .header("X-User-Id", "mallory@evil.com").header("X-User-Role", "ADMIN")
                .exchange().expectStatus().isOk();

        assertThat(received).hasSize(1);
        assertThat(received.get(0).path()).isEqualTo("/payments/1");
        assertThat(received.get(0).header("X-User-Id")).isEqualTo(alice);
        assertThat(received.get(0).header("X-User-Role")).isEqualTo("USER");
    }

    @Test
    void forgedIdentityIsStrippedEvenOnRoutesWithoutJwt() {
        // /api-docs/patients has no JwtValidation; the global filter still removes the client's X-User-Id
        web.get().uri("/api-docs/patients").header("X-User-Id", "mallory@evil.com").exchange().expectStatus().isOk();

        assertThat(received).hasSize(1);
        assertThat(received.get(0).header("X-User-Id")).isNull();
    }

    // ---- rate limiting ----

    @Test
    void burstAboveTheBucketIs429WithRetryAfterAndTheBackendSeesOnlyTheAllowedOnes() throws InterruptedException {
        String token = "Bearer valid-" + user();
        startOfNextSecond();
        for (int i = 0; i < 3; i++) {
            web.get().uri("/api/payments/1").header("Authorization", token).exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Remaining", String.valueOf(2 - i));
        }

        web.get().uri("/api/payments/1").header("Authorization", token).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");
        assertThat(received).hasSize(3);
    }

    @Test
    void theBucketRefillsAfterRetryAfter() throws InterruptedException {
        String token = "Bearer valid-" + user();
        startOfNextSecond();
        for (int i = 0; i < 3; i++) {
            web.get().uri("/api/payments/1").header("Authorization", token).exchange().expectStatus().isOk();
        }
        web.get().uri("/api/payments/1").header("Authorization", token).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        startOfNextSecond(); // Retry-After: 1 -> one second later exactly one token is back

        web.get().uri("/api/payments/1").header("Authorization", token).exchange().expectStatus().isOk();
        web.get().uri("/api/payments/1").header("Authorization", token).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS); // only one came back
    }

    @Test
    void oneUsersBurstDoesNotLimitAnotherUser() throws InterruptedException {
        String alice = "Bearer valid-" + user();
        startOfNextSecond();
        for (int i = 0; i < 4; i++) {
            web.get().uri("/api/payments/1").header("Authorization", alice).exchange();
        }
        web.get().uri("/api/payments/1").header("Authorization", alice).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        web.get().uri("/api/payments/1").header("Authorization", "Bearer valid-" + user()).exchange()
                .expectStatus().isOk();
    }

    @Test
    void cardTokenizationHasItsOwnStricterBucketAndTheGatewaysVaultKey() throws InterruptedException {
        String token = "Bearer valid-" + user();
        startOfNextSecond();
        for (int i = 0; i < 2; i++) {
            web.post().uri("/api/cards").header("Authorization", token).header("X-Api-Key", "client-brought-key")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        }
        web.post().uri("/api/cards").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "1");

        assertThat(received).extracting(Received::path).containsOnly("/cards");
        assertThat(received).extracting(r -> r.header("X-Api-Key")).containsOnly("gateway-vault-key");
        // the same user's payments bucket is untouched
        web.get().uri("/api/payments/1").header("Authorization", token).exchange().expectStatus().isOk();
        // only POST /api/cards is routed (Day 10)
        web.get().uri("/api/cards/tok_123").header("Authorization", token).exchange().expectStatus().isNotFound();
    }

    /** Sleeps until just after the next whole second, so the next few requests share one refill step. */
    private static void startOfNextSecond() throws InterruptedException {
        Thread.sleep(1_000 - System.currentTimeMillis() % 1_000 + 30);
    }

    private static String user() {
        return "user-" + UUID.randomUUID() + "@test.com";
    }
}
