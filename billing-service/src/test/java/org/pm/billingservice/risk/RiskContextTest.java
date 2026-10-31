package org.pm.billingservice.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskContextTest {

    @Test
    void theFirstForwardedAddressIsTheClient() {
        assertThat(RiskContext.of("203.0.113.9, 10.0.0.2", "10.0.0.2", "dev-1"))
                .isEqualTo(new RiskContext("203.0.113.9", "dev-1"));
    }

    @Test
    void withoutForwardedForTheTcpPeerIsUsed() {
        assertThat(RiskContext.of(null, "198.51.100.4", null)).isEqualTo(new RiskContext("198.51.100.4", "unknown"));
        assertThat(RiskContext.of("", "0:0:0:0:0:0:0:1", " dev-2 ").clientIp()).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void anythingMalformedBecomesUnknownInsteadOfFailingThePayment() {
        assertThat(RiskContext.of("not-an-ip", "also bad", "dev with spaces"))
                .isEqualTo(new RiskContext(RiskContext.UNKNOWN_IP, RiskContext.UNKNOWN_DEVICE));
        assertThat(RiskContext.of("999.1.1.1", null, "x".repeat(129))).isEqualTo(RiskContext.none());
        assertThat(RiskContext.of("evil.example.com", null, null).clientIp()).isEqualTo(RiskContext.UNKNOWN_IP); // no DNS
    }
}
