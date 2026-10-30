package org.pm.billingservice.card;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "billing.card-vault.mode", havingValue = "http", matchIfMissing = true)
public class HttpCardVaultClient implements CardVaultClient {

    private final RestClient http;

    public HttpCardVaultClient(RestClient.Builder builder,
                               @Value("${billing.card-vault.base-url}") URI baseUrl,
                               @Value("${billing.card-vault.api-key}") String apiKey,
                               @Value("${billing.card-vault.timeout:2s}") Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("billing.card-vault.api-key must be set (env CARD_VAULT_KEY_BILLING)");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.http = builder.baseUrl(baseUrl.toString()).requestFactory(requestFactory)
                .defaultHeader("X-Api-Key", apiKey).build();
    }

    @Override
    public Optional<CardInfo> metadata(String token) {
        try {
            return Optional.ofNullable(http.get().uri("/cards/{token}", token).retrieve().body(CardInfo.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            // 401/403 = our key is wrong or lacks READ_METADATA: a config error, not the client's fault
            throw new CardVaultUnavailableException("card-vault refused billing: HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new CardVaultUnavailableException("card-vault unavailable: " + e.getMessage(), e);
        }
    }
}
