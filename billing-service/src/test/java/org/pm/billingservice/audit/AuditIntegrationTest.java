package org.pm.billingservice.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.fx.FxRateService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.PaymentStatus;
import org.pm.billingservice.processor.FakePaymentProcessor;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit chain against real Postgres (ADR 0016). Same config as IdempotencyIntegrationTest -> same context, so
 * the chain also holds rows from other test classes; that's fine, verify() must pass over all of them.
 * Tamper tests switch the append-only trigger off (as a DBA could), change a row, check verify() points at exactly
 * that row, and ALWAYS put the row back in finally - the chain is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class AuditIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private AuditLog audit;
    @Autowired private AuditVerifier verifier;
    @Autowired private PaymentService paymentService;
    @Autowired private org.pm.billingservice.payment.PaymentRepository paymentRepository;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private FakePaymentProcessor processor;
    @Autowired private FxRateService fxRates;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private ObjectMapper objectMapper;
    @Value("${billing.audit.hmac-key}") private String key;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID billingAccountId;

    @BeforeEach
    void setUp() {
        billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
    }

    @AfterEach
    void chainIsIntactAfterEveryTest() {
        processor.setLatency(Duration.ZERO);
        assertThat(verifier.verify().valid()).as("a test left the shared chain broken").isTrue();
    }

    // ---- what gets written ----

    @Test
    void everyPaymentChangeIsAuditedInOrderWithWhoDidIt() throws Exception {
        HttpResponse<String> created = post("/payments", "alice@test.com", """
                {"billingAccountId":"%s","amount":"500.00","currency":"INR","paymentMethodToken":"pm_card_visa"}"""
                .formatted(billingAccountId));
        String paymentId = objectMapper.readTree(created.body()).get("id").asText();
        post("/payments/" + paymentId + "/capture", null, "");                  // no X-User-Id: direct call
        paymentService.refund(UUID.randomUUID().toString(), UUID.fromString(paymentId), null, "test"); // no request

        List<AuditEntry> history = audit.forEntity("payment", paymentId, 50);

        assertThat(history).extracting(AuditEntry::action)
                .containsExactly("payment.authorized", "payment.captured", "payment.refunded");
        assertThat(history).extracting(AuditEntry::actor).containsExactly("user:alice@test.com", "internal", "system");
        assertThat(history.get(0).seq()).isLessThan(history.get(1).seq());
        JsonNode refunded = objectMapper.readTree(history.get(2).details());
        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.get("refund_amount_minor").asLong()).isEqualTo(500_00);
        assertThat(refunded.get("card_last4").asText()).isEqualTo("4242");
        assertThat(history.get(2).details()).doesNotContain("pm_card_visa"); // no token, never a PAN
        // the API shows the same, and the whole chain checks out
        JsonNode verify = objectMapper.readTree(get("/audit/verify").body());
        assertThat(verify.get("valid").asBoolean()).isTrue();
        assertThat(get("/audit?entityType=payment&entityId=" + paymentId).body()).contains("payment.captured");
    }

    @Test
    void aManuallyEnteredFxRateIsAudited() {
        var rate = fxRates.record(Currency.getInstance("EUR"), Currency.getInstance("INR"), new BigDecimal("90.12"),
                "treasury desk", null);

        List<AuditEntry> rows = audit.forEntity("fx_rate", rate.id().toString(), 5);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo("fx.rate_recorded");
            assertThat(row.details()).contains("\"rate\":\"90.12\"", "\"source\":\"treasury desk\"");
        });
    }

    @Test
    void aRolledBackChangeLeavesNoRowAndNoGap() {
        long headBefore = head();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            audit.append("test.rolled_back", "test", "x", Map.of());
            throw new IllegalStateException("business step failed after the audit append");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(head()).isEqualTo(headBefore);
        AuditEntry next = tx.execute(status -> audit.append("test.after_rollback", "test", "y", Map.of()));
        assertThat(next.seq()).isEqualTo(headBefore + 1); // the rolled-back number was not burned
    }

    // ---- append-only ----

    @Test
    void theApplicationCannotUpdateOrDeleteAuditRows() {
        long seq = appendTestRows(1).get(0);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'mallory' WHERE seq = ?", seq))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE seq = ?", seq))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_chain_head")).hasMessageContaining("append-only");
    }

    // ---- tampering (trigger switched off, as a DBA could) ----

    @Test
    void aChangedRowIsFoundExactly() {
        List<Long> seqs = appendTestRows(3);
        long middle = seqs.get(1);
        AuditEntry original = row(middle);

        tampered(() -> jdbc.update("UPDATE audit_log SET details = ? WHERE seq = ?",
                        "{\"amount_minor\":1}", middle),
                () -> restore(original),
                () -> {
                    AuditVerifier.Result result = verifier.verify();
                    assertThat(result.valid()).isFalse();
                    assertThat(result.firstBrokenSeq()).isEqualTo(middle);
                    assertThat(result.reason()).contains("content");
                });
    }

    @Test
    void recomputingTheHashWithoutTheKeyDoesNotHelp() {
        long seq = appendTestRows(2).get(0);
        AuditEntry original = row(seq);
        String forgedDetails = "{\"amount_minor\":1}";
        // the attacker recomputes a plain SHA-256 over the same fields: still not our HMAC
        String forgedHash = sha256(original.prevHash() + seq + forgedDetails);

        tampered(() -> jdbc.update("UPDATE audit_log SET details = ?, hash = ? WHERE seq = ?", forgedDetails, forgedHash, seq),
                () -> restore(original),
                () -> assertThat(verifier.verify().firstBrokenSeq()).isEqualTo(seq));
    }

    @Test
    void evenWithTheKeyAForgedRowBreaksTheLinkToTheNextOne() {
        List<Long> seqs = appendTestRows(2);
        long seq = seqs.get(0);
        AuditEntry original = row(seq);
        AuditEntry forged = new AuditEntry(seq, original.occurredAt(), original.actor(), original.action(),
                original.entityType(), original.entityId(), "{\"amount_minor\":1}", original.prevHash(), null);
        String validHash = new AuditHasher(key).hash(forged); // a perfect forgery of THIS row...

        tampered(() -> jdbc.update("UPDATE audit_log SET details = ?, hash = ? WHERE seq = ?", forged.details(), validHash, seq),
                () -> restore(original),
                () -> {
                    AuditVerifier.Result result = verifier.verify();
                    assertThat(result.firstBrokenSeq()).isEqualTo(seqs.get(1)); // ...the next row still points at the old hash
                    assertThat(result.reason()).contains("prev_hash");
                });
    }

    @Test
    void aDeletedRowIsFound() {
        List<Long> seqs = appendTestRows(3);
        long middle = seqs.get(1);
        AuditEntry original = row(middle);

        tampered(() -> jdbc.update("DELETE FROM audit_log WHERE seq = ?", middle),
                () -> reinsert(original),
                () -> {
                    AuditVerifier.Result result = verifier.verify();
                    assertThat(result.firstBrokenSeq()).isEqualTo(middle);
                    assertThat(result.reason()).contains("missing");
                });
    }

    @Test
    void rowsCutOffTheEndAreFound() {
        long last = appendTestRows(2).get(1);
        AuditEntry original = row(last);

        tampered(() -> jdbc.update("DELETE FROM audit_log WHERE seq = ?", last),
                () -> reinsert(original),
                () -> {
                    AuditVerifier.Result result = verifier.verify();
                    assertThat(result.firstBrokenSeq()).isEqualTo(last);
                    assertThat(result.reason()).contains("cut off");
                });
    }

    // ---- concurrency ----

    @Test
    void concurrentWritersBuildOneUnbrokenChain() throws Exception {
        long headBefore = head();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        Set<Long> seqs = ConcurrentHashMap.newKeySet();
        for (int t = 0; t < 8; t++) {
            int writer = t;
            writers.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 25; i++) {
                    int n = i;
                    seqs.add(tx.execute(status -> audit.append("test.concurrent", "test", "w" + writer,
                            Map.of("n", n))).seq());
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> writer : writers) {
            writer.get();
        }
        pool.shutdown();

        assertThat(seqs).hasSize(200);                                   // nobody got the same number
        assertThat(head()).isEqualTo(headBefore + 200);                  // and no number was skipped
        AuditVerifier.Result result = verifier.verify();
        assertThat(result.valid()).isTrue();                             // one chain, no fork
        assertThat(result.headSeq()).isEqualTo(headBefore + 200);
    }

    @Test
    void auditAppendTakesLocksInOneOrderSoCrossingTransactionsDontDeadlock() throws Exception {
        // T1 changes the payment WITHOUT flushing (like void/authorize: Hibernate writes at commit), then appends.
        // T2 changes the same payment WITH an early flush (like capture/refund: claim()), then appends.
        // If append locked the chain head before T1's payment UPDATE, T1 = head -> payment, T2 = payment -> head:
        // a deadlock. AuditLog.append flushes first, so T1 = payment -> head too and T2 simply waits.
        // (Today the processor refuses one of capture/void before both reach the DB; this pins the lock order anyway.)
        Payment payment = paymentService.authorize(UUID.randomUUID().toString(), billingAccountId,
                Money.ofMinor(100_00, "INR"), "pm_card_visa");
        CountDownLatch t1Appended = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> t1 = pool.submit(() -> tx.executeWithoutResult(status -> {
            Payment p = paymentRepository.findById(payment.getId()).orElseThrow();
            p.voidAuthorization();                                   // dirty, not flushed
            audit.append("test.lock_order", "payment", p.getId().toString(), Map.of("t", 1));
            t1Appended.countDown();
            sleep(300);                                              // T2 runs into us meanwhile
        }));
        Future<?> t2 = pool.submit(() -> {
            await(t1Appended);
            return tx.execute(status -> {
                Payment p = paymentRepository.findById(payment.getId()).orElseThrow();
                p.capture(Money.ofMinor(100_00, "INR"));
                paymentRepository.saveAndFlush(p);                    // early flush: locks the payment row
                return audit.append("test.lock_order", "payment", p.getId().toString(), Map.of("t", 2));
            });
        });

        t1.get(); // T1 commits (VOIDED)
        Throwable t2Failure = null;
        try {
            t2.get();
        } catch (java.util.concurrent.ExecutionException e) {
            t2Failure = e.getCause();
        }
        pool.shutdown();

        // T2 waited for T1's payment lock and then lost the @Version check: a normal, retryable conflict, no deadlock
        assertThat(t2Failure).isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(paymentService.get(payment.getId()).getStatus()).isEqualTo(PaymentStatus.VOIDED);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("T1 never appended");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- helpers ----

    private List<Long> appendTestRows(int n) {
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            seqs.add(tx.execute(status -> audit.append("test.row", "test", UUID.randomUUID().toString(),
                    Map.of("i", index))).seq());
        }
        return seqs;
    }

    /** Trigger off, tamper, check, and in any case put the row back and the trigger on again. */
    private void tampered(Runnable tamper, Runnable undo, Runnable check) {
        jdbc.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only");
        try {
            tamper.run();
            check.run();
        } finally {
            undo.run();
            jdbc.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only");
        }
    }

    private void restore(AuditEntry original) {
        jdbc.update("UPDATE audit_log SET details = ?, hash = ? WHERE seq = ?", original.details(), original.hash(), original.seq());
    }

    private void reinsert(AuditEntry e) {
        jdbc.update("""
                        INSERT INTO audit_log (seq, occurred_at, actor, action, entity_type, entity_id, details, prev_hash, hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                e.seq(), Timestamp.from(e.occurredAt()), e.actor(), e.action(), e.entityType(), e.entityId(),
                e.details(), e.prevHash(), e.hash());
    }

    private AuditEntry row(long seq) {
        return audit.page(seq - 1, seq, 1).get(0);
    }

    private long head() {
        return jdbc.queryForObject("SELECT last_seq FROM audit_chain_head", Long.class);
    }

    private HttpResponse<String> post(String path, String user, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            request.header("X-User-Id", user);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
