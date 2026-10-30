package org.pm.billingservice.payment;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 11: every card-network response code ends in the right payment state, with the code and the retryable flag
 * stored. FakePaymentProcessor answers like the simulator's issuer (same test tokens, same codes).
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class NetworkResponseCodeIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private JdbcTemplate jdbc;

    private UUID billingAccountId;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(nullValues = "null", value = {
            "pm_card_visa,               AUTHORIZED, 00, null,               false",
            "pm_card_declined,           FAILED,     05, card_declined,      false",
            "pm_card_insufficient_funds, FAILED,     51, insufficient_funds, false",
            "pm_card_expired,            FAILED,     54, expired_card,       false",
            "pm_card_issuer_unavailable, FAILED,     91, issuer_unavailable, true"})
    void eachResponseCodeEndsInTheRightState(String token, PaymentStatus status, String code, String reason,
                                             boolean retryable) {
        Payment payment = paymentService.authorize(newKey(), billingAccountId, inr(500_00), token);

        Payment stored = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getNetworkResponseCode()).isEqualTo(code);
        assertThat(stored.getFailureReason()).isEqualTo(reason);
        assertThat(stored.isRetryable()).isEqualTo(retryable);
        assertThat(outboxEvents(payment, status == PaymentStatus.FAILED ? "payment.failed" : "payment.authorized"))
                .isEqualTo(1);
    }

    @Test
    void a91IsRetriedAsANewPaymentWithANewKeyTheSameKeyReplaysTheFailure() {
        String key = newKey();
        Payment failed = paymentService.authorize(key, billingAccountId, inr(500_00), FakePaymentProcessor.ISSUER_UNAVAILABLE_TOKEN);
        assertThat(failed.isRetryable()).isTrue();

        Payment replay = paymentService.authorize(key, billingAccountId, inr(500_00), FakePaymentProcessor.ISSUER_UNAVAILABLE_TOKEN);
        assertThat(replay.getId()).isEqualTo(failed.getId());
        assertThat(replay.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(processor.executions("authorize:" + failed.getId())).isEqualTo(1); // not sent to the network again

        // the client's retry: new key = new payment; this time the issuer is back (here: another test card)
        Payment retry = paymentService.authorize(newKey(), billingAccountId, inr(500_00), "pm_card_visa");
        assertThat(retry.getId()).isNotEqualTo(failed.getId());
        assertThat(retry.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(retry.isRetryable()).isFalse();
    }

    @Test
    void databaseRefusesARetryablePaymentThatIsNotFailed() {
        Payment approved = paymentService.authorize(newKey(), billingAccountId, inr(500_00), "pm_card_visa");

        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET retryable = true WHERE id = ?", approved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payment_retryable_only_failed");
    }

    private int outboxEvents(Payment payment, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, payment.getId(), type);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
