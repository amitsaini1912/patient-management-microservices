package org.pm.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 23 (chaos), found by freezing card-vault under load: the gateway had no timeouts, so a HUNG service held every
 * client for as long as the client would wait (60 s in k6), and a service whose accept queue was full gave a 500
 * after Netty's 30 s connect timeout. Now: no answer in time -> 504; can't connect -> 503 + Retry-After, quickly.
 * (A POST retried after either is safe: payments carry an Idempotency-Key, and a 503 means nothing reached the service.)
 * Timeouts are small here so the test is fast; production values are in application.yml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.httpclient.response-timeout=1s",
        "spring.cloud.gateway.httpclient.connect-timeout=500"})
@AutoConfigureWebTestClient
@Import(RedisTestcontainer.class)
class UpstreamFailureTest {   // own stubs: GatewayTestSupport's routes are static and shared

    /** Accepts the request and answers only after 5 s: a frozen / overloaded service. */
    static final HttpServer hung = hungServer();
    /** auth-service: every "Bearer valid-<user>" is that user. */
    static final HttpServer auth = authServer();

    @DynamicPropertySource
    static void brokenBackends(DynamicPropertyRegistry registry) {
        registry.add("auth.service.url", () -> url(auth));
        registry.add("BILLING_SERVICE_URL", () -> url(hung));
        registry.add("CARD_VAULT_URL", () -> "http://localhost:" + closedPort());
        registry.add("PATIENT_SERVICE_URL", () -> url(hung));
        registry.add("CARD_VAULT_KEY_GATEWAY", () -> "gateway-vault-key");
    }

    @Autowired private WebTestClient web;

    @Test
    void aHungServiceIsA504AfterTheResponseTimeoutNotAnEndlessWait() {
        Instant start = Instant.now();
        web.post().uri("/api/payments").header("Authorization", "Bearer valid-" + user())
                .header("Idempotency-Key", "k-" + System.nanoTime()).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void anUnreachableServiceIsA503WithRetryAfterNotA500() {
        Instant start = Instant.now();
        web.post().uri("/api/cards").header("Authorization", "Bearer valid-" + user()).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "1");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
    }

    private static String user() {
        return "u" + System.nanoTime() + "@test.com";
    }

    private static String url(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static HttpServer authServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/validate", exchange -> {
                String header = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = ("{\"subject\":\"" + header.substring("Bearer valid-".length()) + "\",\"role\":\"USER\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer hungServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A port nothing listens on: connecting is refused at once. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
