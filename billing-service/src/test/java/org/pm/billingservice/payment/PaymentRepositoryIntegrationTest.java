package org.pm.billingservice.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class PaymentRepositoryIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BillingAccountService billingAccountService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID billingAccountId;

    @BeforeEach
    void freshBillingAccount() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @Test
    void savesAndLoadsWithVersion() {
        Payment saved = paymentRepository.save(Payment.create(billingAccountId, inr(1_000_00)));

        Payment loaded = paymentRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(loaded.getAmount()).isEqualTo(inr(1_000_00));
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void everyChangeBumpsVersion() {
        UUID id = paymentRepository.save(Payment.create(billingAccountId, inr(1_000_00))).getId();

        change(id, p -> p.authorize("auth_test"));
        change(id, p -> p.capture(inr(1_000_00)));

        Payment loaded = paymentRepository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(loaded.getVersion()).isEqualTo(2);
        assertThat(loaded.getUpdatedAt()).isAfter(loaded.getCreatedAt());
    }

    /**
     * The classic race: the merchant clicks "capture" while a timeout job "voids" the same payment.
     * Both threads read version 0, both change it, both try to commit. Without @Version the
     * second commit would silently overwrite the first (a captured payment that says VOIDED).
     */
    @Test
    void concurrentCaptureAndVoidOneWinsOneFails() throws Exception {
        UUID id = paymentRepository.save(Payment.create(billingAccountId, inr(1_000_00))).getId();
        change(id, p -> p.authorize("auth_test"));

        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results = List.of(
                    pool.submit(() -> changeAfterBarrier(id, bothLoaded, p -> p.capture(inr(1_000_00)))),
                    pool.submit(() -> changeAfterBarrier(id, bothLoaded, Payment::voidAuthorization)));

            List<Throwable> failures = new ArrayList<>();
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }

            assertThat(failures).hasSize(1);
            assertThat(failures.get(0)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            pool.shutdownNow();
        }

        Payment winner = paymentRepository.findById(id).orElseThrow();
        assertThat(winner.getStatus()).isIn(PaymentStatus.CAPTURED, PaymentStatus.VOIDED);
        assertThat(winner.getVersion()).isEqualTo(2); // create 0, authorize 1, exactly ONE more change
    }

    @Test
    void staleCopyCannotOverwriteNewerRow() {
        UUID id = paymentRepository.save(Payment.create(billingAccountId, inr(1_000_00))).getId();
        change(id, p -> p.authorize("auth_test"));
        Payment stale = paymentRepository.findById(id).orElseThrow(); // version 1, detached after this call

        change(id, Payment::voidAuthorization);                       // DB is now version 2
        stale.capture(inr(1_000_00));

        assertThatThrownBy(() -> paymentRepository.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VOIDED);
    }

    @Test
    void databaseRejectsAmountsThatContradictStatus() {
        // raw SQL, bypassing the entity: the CHECK constraints are the last line of defence
        assertThatThrownBy(() -> insertRaw("CAPTURED", 1_000_00, 0, 0))
                .rootCause().hasMessageContaining("payment_captured_matches_status");
        assertThatThrownBy(() -> insertRaw("CAPTURED", 1_000_00, 1_000_01, 0))
                .rootCause().hasMessageContaining("payment_captured_within_authorized");
        assertThatThrownBy(() -> insertRaw("PARTIALLY_REFUNDED", 1_000_00, 500_00, 600_00))
                .rootCause().hasMessageContaining("payment_refunded_within_captured");
        assertThatThrownBy(() -> insertRaw("PARTIALLY_REFUNDED", 1_000_00, 500_00, 500_00))
                .rootCause().hasMessageContaining("payment_fully_refunded_matches_status");
    }

    private void change(UUID id, Consumer<Payment> action) {
        transactionTemplate.executeWithoutResult(tx -> action.accept(paymentRepository.findById(id).orElseThrow()));
    }

    private void changeAfterBarrier(UUID id, CyclicBarrier barrier, Consumer<Payment> action) {
        transactionTemplate.executeWithoutResult(tx -> {
            Payment payment = paymentRepository.findById(id).orElseThrow();
            try {
                barrier.await(10, TimeUnit.SECONDS); // both threads have read the same version
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            action.accept(payment);
            // the UPDATE ... WHERE version = ? runs at commit, when this lambda returns
        });
    }

    // every status used here comes after authorization, so authorized_at is set (V5 CHECK)
    private void insertRaw(String status, long amount, long captured, long refunded) {
        jdbc.update("""
                INSERT INTO payment (id, billing_account_id, amount_minor, currency, captured_amount_minor,
                                     refunded_amount_minor, status, version, authorized_at, book_currency, fx_rate)
                VALUES (?, ?, ?, 'INR', ?, ?, ?, 0, now(), 'INR', 1)""",
                UUID.randomUUID(), billingAccountId, amount, captured, refunded, status);
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
