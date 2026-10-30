package org.pm.billingservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.processor.FakePaymentProcessor.HoldState;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The job is built by hand with a FIXED clock set days into the future, instead of waiting a week.
 * Other test classes leave AUTHORIZED payments behind in the shared DB; the job may expire those too,
 * so assertions only look at this test's own payment ids.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class AuthorizationExpiryJobIntegrationTest {

    private static final Duration TTL = Duration.ofDays(7);

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private UUID billingAccountId;

    @BeforeEach
    void freshBillingAccount() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @Test
    void expiresOnlyAuthorizationsOlderThanTtl() {
        Payment payment = authorized();

        assertThat(jobAt(daysFromNow(6)).expireDueAuthorizations()).doesNotContain(payment.getId());
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.AUTHORIZED);

        assertThat(jobAt(daysFromNow(8)).expireDueAuthorizations()).contains(payment.getId());
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(processor.hold(payment.getProcessorReference()).state()).isEqualTo(HoldState.VOIDED); // hold released
    }

    @Test
    void capturedVoidedAndFailedPaymentsAreNotTouched() {
        Payment captured = authorized();
        paymentService.capture(captured.getId(), null);
        Payment voided = authorized();
        paymentService.voidAuthorization(voided.getId());
        Payment failed = paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), FakePaymentProcessor.DECLINED_TOKEN);

        List<UUID> expired = jobAt(daysFromNow(30)).expireDueAuthorizations();

        assertThat(expired).doesNotContain(captured.getId(), voided.getId(), failed.getId());
        assertThat(statusOf(captured)).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void expiredPaymentCannotBeCaptured() {
        Payment payment = authorized();
        jobAt(daysFromNow(8)).expireDueAuthorizations();

        assertThatThrownBy(() -> paymentService.capture(payment.getId(), null))
                .isInstanceOf(IllegalPaymentStateException.class);
    }

    @Test
    void secondRunDoesNotExpireAgain() {
        Payment payment = authorized();
        AuthorizationExpiryJob job = jobAt(daysFromNow(8));

        assertThat(job.expireDueAuthorizations()).contains(payment.getId());
        assertThat(job.expireDueAuthorizations()).doesNotContain(payment.getId());
    }

    /**
     * Simulates a second billing instance that has already claimed one payment (holds its row lock).
     * This instance must skip that row and expire the rest WITHOUT waiting. With plain FOR UPDATE
     * (no SKIP LOCKED) the job would block here until the other transaction ends, and the timeout fails the test.
     */
    @Test
    void skipsPaymentsLockedByAnotherInstance() throws Exception {
        Payment lockedElsewhere = authorized();
        Payment free = authorized();
        AuthorizationExpiryJob job = jobAt(daysFromNow(8));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService otherInstance = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = otherInstance.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM payment WHERE id = ? FOR UPDATE", UUID.class, lockedElsewhere.getId());
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            List<UUID> expired = assertTimeoutPreemptively(Duration.ofSeconds(10), job::expireDueAuthorizations);

            assertThat(expired).contains(free.getId()).doesNotContain(lockedElsewhere.getId());
            assertThat(statusOf(lockedElsewhere)).isEqualTo(PaymentStatus.AUTHORIZED);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            otherInstance.shutdownNow();
        }

        // lock gone: the next run picks it up
        assertThat(job.expireDueAuthorizations()).contains(lockedElsewhere.getId());
    }

    private AuthorizationExpiryJob jobAt(Instant now) {
        return new AuthorizationExpiryJob(paymentRepository, processor, transactionTemplate,
                Clock.fixed(now, ZoneOffset.UTC), new PaymentProperties(TTL, 100));
    }

    private Payment authorized() {
        return paymentService.authorize(newKey(), billingAccountId, inr(1_000_00), "pm_card_visa");
    }

    private PaymentStatus statusOf(Payment payment) {
        return paymentRepository.findById(payment.getId()).orElseThrow().getStatus();
    }

    private static Instant daysFromNow(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Each call is a new client request, so a new Idempotency-Key. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
