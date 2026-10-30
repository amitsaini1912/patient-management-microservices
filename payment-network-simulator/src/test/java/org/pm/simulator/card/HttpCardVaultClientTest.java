package org.pm.simulator.card;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The simulator's side of card-vault's detokenize contract, against a stub vault. */
class HttpCardVaultClientTest {

    private HttpServer vault;
    private volatile int status = 200;
    private volatile String response = "{}";
    private volatile String seenMethod;
    private volatile String seenPath;
    private volatile String seenKey;
    private HttpCardVaultClient client;

    @BeforeEach
    void start() throws IOException {
        vault = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        vault.createContext("/", exchange -> {
            seenMethod = exchange.getRequestMethod();
            seenPath = exchange.getRequestURI().getPath();
            seenKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        vault.start();
        client = new HttpCardVaultClient(RestClient.builder(),
                URI.create("http://localhost:" + vault.getAddress().getPort()), "sim-vault-key", Duration.ofSeconds(1));
    }

    @AfterEach
    void stop() {
        vault.stop(0);
    }

    @Test
    void postsToDetokenizeWithOurKeyAndReadsTheCard() {
        response = "{\"token\":\"tok_abc\",\"pan\":\"4111111111111111\",\"expiry_month\":7,\"expiry_year\":2031}";

        CardVaultClient.Card card = client.detokenize("tok_abc");

        assertThat(seenMethod).isEqualTo("POST");
        assertThat(seenPath).isEqualTo("/cards/tok_abc/detokenize");
        assertThat(seenKey).isEqualTo("sim-vault-key");
        assertThat(card.pan()).isEqualTo("4111111111111111");
        assertThat(card.expiry()).isEqualTo(YearMonth.of(2031, 7));
        assertThat(card.toString()).doesNotContain("4111111111111111").contains("****1111");
    }

    @Test
    void unknownTokenIsNotFoundEverythingElseIsUnavailable() {
        status = 404;
        assertThatThrownBy(() -> client.detokenize("tok_x")).isInstanceOf(CardVaultClient.CardNotFoundException.class);

        status = 403; // our key lacks DETOKENIZE: config error, not the payer's fault
        assertThatThrownBy(() -> client.detokenize("tok_x"))
                .isInstanceOf(CardVaultClient.CardVaultUnavailableException.class).hasMessageContaining("403");

        HttpCardVaultClient nobodyHome = new HttpCardVaultClient(RestClient.builder(), URI.create("http://localhost:1"),
                "k", Duration.ofMillis(500));
        assertThatThrownBy(() -> nobodyHome.detokenize("tok_x"))
                .isInstanceOf(CardVaultClient.CardVaultUnavailableException.class);
    }
}
