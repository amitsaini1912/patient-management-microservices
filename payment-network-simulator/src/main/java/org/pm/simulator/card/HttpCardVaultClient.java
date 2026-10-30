package org.pm.simulator.card;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.net.URI;
import java.time.Duration;
import java.time.YearMonth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** POST /cards/{token}/detokenize on card-vault with our own X-Api-Key (the one with DETOKENIZE). */
@Component
@ConditionalOnProperty(name = "simulator.card-vault.mode", havingValue = "http", matchIfMissing = true)
public class HttpCardVaultClient implements CardVaultClient {

    private final RestClient http;

    public HttpCardVaultClient(RestClient.Builder builder,
                               @Value("${simulator.card-vault.base-url}") URI baseUrl,
                               @Value("${simulator.card-vault.api-key}") String apiKey,
                               @Value("${simulator.card-vault.timeout:2s}") Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("simulator.card-vault.api-key must be set (env CARD_VAULT_KEY_SIMULATOR)");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.http = builder.baseUrl(baseUrl.toString()).requestFactory(requestFactory)
                .defaultHeader("X-Api-Key", apiKey).build();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PanView(String token, String pan, int expiryMonth, int expiryYear) {
        @Override
        public String toString() {
            return "PanView{token=" + token + ", pan=<redacted>}";
        }
    }

    @Override
    public Card detokenize(String token) {
        PanView view;
        try {
            view = http.post().uri("/cards/{token}/detokenize", token).retrieve().body(PanView.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new CardNotFoundException("card-vault doesn't know this token");
            }
            // 401/403: our key is wrong or lacks DETOKENIZE - a config error
            throw new CardVaultUnavailableException("card-vault refused the simulator: HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new CardVaultUnavailableException("card-vault unavailable: " + e.getMessage(), e);
        }
        if (view == null || view.pan() == null) {
            throw new CardVaultUnavailableException("card-vault sent no card", null);
        }
        return new Card(view.pan(), YearMonth.of(view.expiryYear(), view.expiryMonth()));
    }
}
