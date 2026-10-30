package org.pm.billingservice.reconciliation;

import java.time.LocalDate;
import java.util.List;
import org.pm.billingservice.processor.SimulatorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "billing.processor", havingValue = "simulator", matchIfMissing = true)
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorSettlementSource implements SettlementSource {

    private final RestClient http;

    public SimulatorSettlementSource(RestClient.Builder builder, SimulatorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout().multipliedBy(6)); // a report can be big
        this.http = builder.baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    record Report(LocalDate date, List<SettlementRow> rows) {}

    @Override
    public List<SettlementRow> settlementFor(LocalDate date) {
        Report report = http.get().uri("/v1/settlements/{date}", date).retrieve().body(Report.class);
        if (report == null || report.rows() == null) {
            throw new IllegalStateException("empty settlement report for " + date);
        }
        return report.rows();
    }
}
