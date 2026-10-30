package org.pm.apigateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterFilterTest {

    @Test
    void secondsUntilEnoughTokensAreBackRoundedUpAtLeastOne() {
        assertThat(RetryAfterFilter.retryAfterSeconds(headers("2", "5"))).isEqualTo(3); // 5 tokens at 2/s -> 2.5 -> 3
        assertThat(RetryAfterFilter.retryAfterSeconds(headers("10", "1"))).isEqualTo(1); // 0.1 s -> at least 1
        assertThat(RetryAfterFilter.retryAfterSeconds(new HttpHeaders())).isEqualTo(1);  // no limiter headers
        assertThat(RetryAfterFilter.retryAfterSeconds(headers("0", "1"))).isEqualTo(1);  // never divide by 0
    }

    private static HttpHeaders headers(String replenishRate, String requested) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Replenish-Rate", replenishRate);
        headers.set("X-RateLimit-Requested-Tokens", requested);
        return headers;
    }
}
