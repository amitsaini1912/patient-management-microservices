package org.pm.billingservice.payment;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 20b (ADR 0020): what /actuator/prometheus shows for the dashboard - payment events (after commit only), outbox
 * lag, the risk circuit state, risk decision counts. Reads the same registry Prometheus scrapes.
 */
@SpringBootTest(properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class BillingMetricsIntegrationTest {

    @Autowired private PrometheusMeterRegistry prometheus;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentEvents paymentEvents;
    @Autowired private PaymentRepository payments;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private double value(String name, String labels) {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "\\{([^}]*)\\} (\\S+)$", Pattern.MULTILINE).matcher(prometheus.scrape());
        double total = 0;
        while (m.find()) {
            if (labels == null || m.group(1).contains(labels)) {
                total += Double.parseDouble(m.group(2));
            }
        }
        return total;
    }

    private UUID account() {
        return billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Metrics Patient", "m@test.com").getId();
    }

    @Test
    void paymentEventsAreCountedByTypeAndFailureReason() {
        double authorized = value("billing_payment_events_total", "type=\"payment.authorized\"");
        double captured = value("billing_payment_events_total", "type=\"payment.captured\"");
        double declined = value("billing_payment_events_total", "reason=\"card_declined\"");
        UUID account = account();

        Payment ok = paymentService.authorize(UUID.randomUUID().toString(), account, Money.ofMinor(500_00, "INR"), "pm_card_visa");
        paymentService.capture(ok.getId(), null);
        paymentService.authorize(UUID.randomUUID().toString(), account, Money.ofMinor(500_00, "INR"), "pm_card_declined");

        assertThat(value("billing_payment_events_total", "type=\"payment.authorized\"") - authorized).isEqualTo(1);
        assertThat(value("billing_payment_events_total", "type=\"payment.captured\"") - captured).isEqualTo(1);
        assertThat(value("billing_payment_events_total", "reason=\"card_declined\"") - declined).isEqualTo(1);
    }

    @Test
    void aRolledBackChangeIsNotCounted() {
        Payment payment = paymentService.authorize(UUID.randomUUID().toString(), account(), Money.ofMinor(100_00, "INR"), "pm_card_visa");
        double voided = value("billing_payment_events_total", "type=\"payment.voided\"");

        transactionTemplate.executeWithoutResult(tx -> {
            Payment loaded = payments.findById(payment.getId()).orElseThrow();
            loaded.voidAuthorization();
            paymentEvents.voided(loaded);
            tx.setRollbackOnly();                                  // e.g. a version conflict; the retry counts once
        });

        assertThat(value("billing_payment_events_total", "type=\"payment.voided\"")).isEqualTo(voided);
    }

    @Test
    void outboxLagRiskAndLatencyAreExported() {
        String scrape = prometheus.scrape();
        assertThat(scrape).contains("billing_outbox_pending", "billing_outbox_failed",
                "billing_outbox_oldest_pending_age_seconds", "billing_risk_circuit_state", "application=\"billing-service\"");
        assertThat(value("billing_risk_circuit_state", null)).isZero();                          // closed
        long pending = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status = 'PENDING'", Long.class);
        assertThat(value("billing_outbox_pending", null)).isEqualTo(pending);

        paymentService.authorize(UUID.randomUUID().toString(), account(), Money.ofMinor(100_00, "INR"), "pm_card_visa");
        assertThat(prometheus.scrape()).contains("billing_risk_decisions_total", "billing_risk_duration_seconds_bucket");
    }
}
