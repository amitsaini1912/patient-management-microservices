package org.pm.apigateway;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.pm.apigateway.ratelimit.RateLimitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0027: /auth/login is limited per client IP - 10 attempts in a burst, then 1 per 6 s - before any user is known.
 * All requests here come from 127.0.0.1, so this class owns that IP's login bucket (its own Redis container).
 * Real HTTP (bindToServer): the auto-configured WebTestClient talks to a mock server that has no client address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisTestcontainer.class)
class LoginRateLimitTest extends GatewayTestSupport {

    @LocalServerPort private int port;
    @Autowired @Qualifier("clientIpKeyResolver") private KeyResolver clientIpKeyResolver;

    @Test
    void tenLoginAttemptsThenA429WithRetryAfterAndAForgedForwardedForDoesNotHelp() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        for (int i = 0; i < 10; i++) {
            login(null).expectStatus().isUnauthorized();                       // reached auth-service
        }
        login(null).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "6");
        // X-Forwarded-For is the client's own claim: not trusted without a proxy we run (gateway.trusted-proxies=0)
        login("198.51.100." + (int) (Math.random() * 200)).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void otherRoutesAreNotLimitedByTheLoginBucket() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        web.get().uri("/auth/validate").exchange().expectStatus().isUnauthorized(); // still answered, not 429
    }

    @Test
    void behindTrustedProxiesTheAddressOurProxySawIsTheKey() {
        KeyResolver behindOneProxy = new RateLimitConfig().clientIpKeyResolver(1);
        MockServerWebExchange viaProxy = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 4321))                 // our proxy
                .header("X-Forwarded-For", "6.6.6.6, 203.0.113.7"));                    // forged, then what the proxy saw
        assertThat(behindOneProxy.resolve(viaProxy).block()).isEqualTo("203.0.113.7");

        MockServerWebExchange direct = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2.1", 4321)).header("X-Forwarded-For", "6.6.6.6"));
        assertThat(clientIpKeyResolver.resolve(direct).block()).isEqualTo("192.0.2.1"); // default: header ignored
    }

    private WebTestClient web;

    private WebTestClient.ResponseSpec login(String forwardedFor) {
        WebTestClient.RequestBodySpec request = web.post().uri("/auth/login").header("Content-Type", "application/json");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return request.bodyValue("{\"email\":\"a@test.com\",\"password\":\"wrong-password\"}").exchange();
    }
}
