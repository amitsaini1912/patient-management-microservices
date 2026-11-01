package org.pm.billingservice.payment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.StaleAuthorizationResolver.Resolution;
import org.pm.billingservice.processor.AuthorizationResult;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.processor.FakePaymentProcessor.HoldState;
import org.pm.billingservice.processor.PaymentProcessor;
import org.pm.billingservice.processor.PaymentProcessorException;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "outcome unknown" payments from the Day 23 chaos run: saved CREATED, then the client's request ended without an
 * answer and nobody retried. Each test leaves the row CREATED the way it happens for real (the acquirer call done or
 * not, the recording step never run), then runs the resolver with a clock 20 minutes ahead. Other test classes leave
 * CREATED rows in the shared DB; the resolver settles those too, so assertions only look at this test's own payments.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class StaleAuthorizationResolverIntegrationTest {

    private static final String CARD = "pm_card_visa";

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private PaymentEvents paymentEvents;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private UUID billingAccountId;

    @BeforeEach
    void freshBillingAccount() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @Test
    void neverReachedTheAcquirerEndsFailedWithNothingSent() {
        UUID paymentId = createdOnly();

        assertThat(resolverIn(Duration.ofMinutes(20)).resolveStalePayments()).containsEntry(paymentId, Resolution.NOT_RECEIVED);

        Payment payment = load(paymentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo(StaleAuthorizationResolver.NOT_RECEIVED_REASON);
        assertThat(processor.executions("authorize:" + paymentId)).isZero(); // asked, never authorized
        assertThat(eventTypes(paymentId)).containsExactly("payment.failed");
    }

    @Test
    void authorizedAtTheAcquirerButNeverRecordedIsVoidedSoTheHoldIsReleased() {
        UUID paymentId = createdOnly();
        AuthorizationResult hold = processor.authorize("authorize:" + paymentId, paymentId, inr(1_000_00), CARD);

        assertThat(resolverIn(Duration.ofMinutes(20)).resolveStalePayments()).containsEntry(paymentId, Resolution.VOIDED);

        Payment payment = load(paymentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(payment.getProcessorReference()).isEqualTo(hold.processorReference());
        assertThat(processor.hold(hold.processorReference()).state()).isEqualTo(HoldState.VOIDED);
        assertThat(processor.executions("authorize:" + paymentId)).isEqualTo(1); // inquiry, not a second authorization
        assertThat(eventTypes(paymentId)).containsExactly("payment.authorized", "payment.voided");
    }

    @Test
    void declinedAtTheAcquirerEndsFailedWithItsReasonAndCode() {
        UUID paymentId = createdOnly();
        processor.authorize("authorize:" + paymentId, paymentId, inr(1_000_00), FakePaymentProcessor.INSUFFICIENT_FUNDS_TOKEN);

        assertThat(resolverIn(Duration.ofMinutes(20)).resolveStalePayments()).containsEntry(paymentId, Resolution.DECLINED);

        Payment payment = load(paymentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("insufficient_funds");
        assertThat(payment.getNetworkResponseCode()).isEqualTo("51");
    }

    @Test
    void youngCreatedPaymentsAreLeftAlone() {
        UUID paymentId = createdOnly();

        assertThat(resolverIn(Duration.ofMinutes(10)).resolveStalePayments()).doesNotContainKey(paymentId);
        assertThat(load(paymentId).getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void aLateClientRetryGetsTheSettledPaymentAndNoSecondAuthorization() {
        String key = UUID.randomUUID().toString();
        UUID paymentId = PaymentService.paymentIdFor(key);
        paymentRepository.save(Payment.create(paymentId, billingAccountId, inr(1_000_00)));
        processor.authorize("authorize:" + paymentId, paymentId, inr(1_000_00), CARD);
        StaleAuthorizationResolver resolver = resolverIn(Duration.ofMinutes(20));
        resolver.resolveStalePayments();

        Payment retried = paymentService.authorize(key, billingAccountId, inr(1_000_00), CARD);

        assertThat(retried.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(processor.executions("authorize:" + paymentId)).isEqualTo(1);
        assertThat(resolver.resolveStalePayments()).doesNotContainKey(paymentId); // settled once
    }

    @Test
    void paymentSettledMeanwhileByARetryIsNotTouched() {
        String key = UUID.randomUUID().toString();
        UUID paymentId = PaymentService.paymentIdFor(key);
        paymentRepository.save(Payment.create(paymentId, billingAccountId, inr(1_000_00)));
        // the client's retry arrives while the resolver is asking the acquirer
        PaymentProcessor retryDuringInquiry = new DelegatingProcessor(processor) {
            @Override
            public Optional<AuthorizationResult> findAuthorization(UUID id) {
                Optional<AuthorizationResult> answer = super.findAuthorization(id);
                if (id.equals(paymentId)) {
                    paymentService.authorize(key, billingAccountId, inr(1_000_00), CARD);
                }
                return answer; // still "never received": the answer is out of date by now
            }
        };

        assertThat(resolver(retryDuringInquiry, Duration.ofMinutes(20)).resolveStalePayments())
                .containsEntry(paymentId, Resolution.ALREADY_SETTLED);
        assertThat(load(paymentId).getStatus()).isEqualTo(PaymentStatus.AUTHORIZED); // the retry's result stands
    }

    @Test
    void acquirerDownLeavesPaymentsCreatedAndStopsAfterTheFirstFailure() {
        UUID first = createdOnly();
        UUID second = createdOnly();
        int[] inquiries = {0};
        PaymentProcessor down = new DelegatingProcessor(processor) {
            @Override
            public Optional<AuthorizationResult> findAuthorization(UUID id) {
                inquiries[0]++;
                throw PaymentProcessorException.noAnswer("inquiry: no answer from acquirer", null);
            }
        };

        Map<UUID, Resolution> results = resolver(down, Duration.ofMinutes(20)).resolveStalePayments();

        assertThat(results.values()).containsOnly(Resolution.UNRESOLVED);
        assertThat(inquiries[0]).isEqualTo(1); // one timeout, not one per stale payment
        assertThat(load(first).getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(load(second).getStatus()).isEqualTo(PaymentStatus.CREATED);

        // acquirer back: the next run settles them
        Map<UUID, Resolution> next = resolverIn(Duration.ofMinutes(20)).resolveStalePayments();
        assertThat(next).containsEntry(first, Resolution.NOT_RECEIVED).containsEntry(second, Resolution.NOT_RECEIVED);
    }

    @Test
    void pendingReviewIsNotTheResolversBusiness() {
        UUID paymentId = createdOnly();
        transactionTemplate.executeWithoutResult(tx -> {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
            payment.holdForReview();
        });

        assertThat(resolverIn(Duration.ofMinutes(20)).resolveStalePayments()).doesNotContainKey(paymentId);
        assertThat(load(paymentId).getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
    }

    // ---- helpers ----

    /** Saved CREATED; the request that saved it never got further. */
    private UUID createdOnly() {
        UUID paymentId = UUID.randomUUID();
        paymentRepository.save(Payment.create(paymentId, billingAccountId, inr(1_000_00)));
        return paymentId;
    }

    private StaleAuthorizationResolver resolverIn(Duration ahead) {
        return resolver(processor, ahead);
    }

    /** Batch large enough for every CREATED row other test classes left behind (ours are the newest). */
    private StaleAuthorizationResolver resolver(PaymentProcessor acquirer, Duration ahead) {
        Clock later = Clock.fixed(Instant.now().plus(ahead), ZoneOffset.UTC);
        return new StaleAuthorizationResolver(paymentRepository, acquirer, paymentService, paymentEvents,
                transactionTemplate, later, new PaymentProperties(Duration.ofDays(7), 100, Duration.ofMinutes(15), 100_000),
                new SimpleMeterRegistry());
    }

    private Payment load(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private List<String> eventTypes(UUID paymentId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? AND topic = 'payment.events' "
                + "ORDER BY position", String.class, paymentId);
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }

    /** The real fake acquirer, with one method overridden per test. */
    private static class DelegatingProcessor implements PaymentProcessor {
        private final PaymentProcessor target;

        DelegatingProcessor(PaymentProcessor target) {
            this.target = target;
        }

        @Override
        public AuthorizationResult authorize(String key, UUID paymentId, Money amount, String token) {
            return target.authorize(key, paymentId, amount, token);
        }

        @Override
        public void capture(String key, String reference, Money amount) {
            target.capture(key, reference, amount);
        }

        @Override
        public void voidAuthorization(String key, String reference) {
            target.voidAuthorization(key, reference);
        }

        @Override
        public String refund(String key, String reference, Money amount) {
            return target.refund(key, reference, amount);
        }

        @Override
        public Optional<AuthorizationResult> findAuthorization(UUID paymentId) {
            return target.findAuthorization(paymentId);
        }
    }
}
