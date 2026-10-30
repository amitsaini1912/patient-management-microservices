package org.pm.billingservice.card;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** billing's side of the card-vault contract, against a stub vault. */
class HttpCardVaultClientTest {

    private HttpServer vault;
    private volatile int status = 200;
    private volatile String apiKeySeen;
    private volatile String pathSeen;
    private HttpCardVaultClient client;

    @BeforeEach
    void start() throws IOException {
        vault = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        vault.createContext("/", exchange -> {
            apiKeySeen = exchange.getRequestHeaders().getFirst("X-Api-Key");
            pathSeen = exchange.getRequestURI().getPath();
            byte[] body = """
                    {"token":"tok_abc","last4":"1111","brand":"VISA","fingerprint":"%s","expiry_month":12,"expiry_year":2030}"""
                    .formatted("a".repeat(64)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        vault.start();
        client = new HttpCardVaultClient(RestClient.builder(), URI.create("http://localhost:" + vault.getAddress().getPort()),
                "billing-key-0123456789", Duration.ofSeconds(1));
    }

    @AfterEach
    void stop() {
        vault.stop(0);
    }

    @Test
    void readsMetadataWithBillingsKey() {
        CardInfo card = client.metadata("tok_abc").orElseThrow();

        assertThat(card).isEqualTo(new CardInfo("tok_abc", "1111", "VISA", "a".repeat(64), 12, 2030));
        assertThat(apiKeySeen).isEqualTo("billing-key-0123456789");
        assertThat(pathSeen).isEqualTo("/cards/tok_abc");
    }

    @Test
    void unknownTokenIsEmptyButRefusalsAndOutagesAreUnavailable() {
        status = 404;
        assertThat(client.metadata("tok_x")).isEmpty();

        status = 403; // our key lacks READ_METADATA: misconfiguration, not the client's fault
        assertThatThrownBy(() -> client.metadata("tok_x")).isInstanceOf(CardVaultUnavailableException.class);
        status = 503;
        assertThatThrownBy(() -> client.metadata("tok_x")).isInstanceOf(CardVaultUnavailableException.class);
        HttpCardVaultClient nobodyHome = new HttpCardVaultClient(RestClient.builder(), URI.create("http://localhost:1"),
                "billing-key-0123456789", Duration.ofMillis(500));
        assertThatThrownBy(() -> nobodyHome.metadata("tok_x")).isInstanceOf(CardVaultUnavailableException.class);
    }

    @Test
    void refusesToStartWithoutAnApiKey() {
        assertThatThrownBy(() -> new HttpCardVaultClient(RestClient.builder(), URI.create("http://localhost:1"), "",
                Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
    }
}
