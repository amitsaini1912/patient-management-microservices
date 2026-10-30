package org.pm.billingservice.payment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.EntrySource;
import org.pm.billingservice.ledger.JournalEntry;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.model.BillingAccount;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.AuthorizationResult;
import org.pm.billingservice.processor.PaymentProcessor;
import org.pm.billingservice.repository.BillingAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every operation has the same three steps:
 *   1. short DB transaction: read + check (fail fast, before touching the card)
 *   2. call the processor, with NO transaction open (it may take seconds)
 *   3. short DB transaction: apply the result; @Version catches anyone who changed the payment meanwhile.
 *      The payment event goes into the outbox in this same transaction (ADR 0008): no change without its
 *      event, no event without its change.
 *
 * If step 2 succeeds and step 3 fails (crash, DB down), the processor and our DB disagree until the
 * client retries with the same Idempotency-Key. Every step is safe to run again (ADR 0007):
 *   - authorize / refund: the new row's id is derived from the client's key, so a retry finds it
 *   - every processor call carries a key derived from what it does, so the processor does it once
 * A client that never retries leaves the gap to reconciliation (Day 8).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    // enough for a burst of concurrent refunds on one payment; each round at least one of them commits
    private static final int MAX_RECORD_ATTEMPTS = 10;

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final BillingAccountRepository billingAccountRepository;
    private final LedgerService ledgerService;
    private final PaymentProcessor paymentProcessor;
    private final PaymentEvents paymentEvents;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          BillingAccountRepository billingAccountRepository,
                          LedgerService ledgerService,
                          PaymentProcessor paymentProcessor,
                          PaymentEvents paymentEvents,
                          TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.billingAccountRepository = billingAccountRepository;
        this.ledgerService = ledgerService;
        this.paymentProcessor = paymentProcessor;
        this.paymentEvents = paymentEvents;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Creates the payment and asks the processor to hold the money.
     * A decline is a normal outcome: the payment is returned as FAILED. No money moved, so no ledger entry.
     */
    public Payment authorize(String idempotencyKey, UUID billingAccountId, Money amount, String paymentMethodToken) {
        UUID paymentId = paymentIdFor(idempotencyKey);
        Optional<Payment> earlier = transactionTemplate.execute(tx -> paymentRepository.findById(paymentId));
        if (earlier.isPresent() && earlier.get().getStatus() != PaymentStatus.CREATED) {
            return earlier.get(); // a previous attempt finished; only its response was lost
        }

        // Saved as CREATED before calling the processor, so a crash mid-call still leaves a trace to reconcile.
        Payment created = earlier.isPresent() ? earlier.get() : transactionTemplate.execute(tx -> {
            BillingAccount account = billingAccountRepository.findById(billingAccountId)
                    .orElseThrow(() -> new UnknownBillingAccountException(billingAccountId));
            if (!account.getCurrency().equals(amount.currency())) {
                // only same-currency payments until Day 9 (FX)
                throw new IllegalArgumentException("billing account " + billingAccountId + " is in "
                        + account.getCurrency() + ", payment is in " + amount.currency());
            }
            return paymentRepository.save(Payment.create(paymentId, billingAccountId, amount));
        });

        AuthorizationResult result = paymentProcessor.authorize("authorize:" + paymentId, paymentId, amount, paymentMethodToken);

        return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = load(created.getId());
            if (payment.getStatus() != PaymentStatus.CREATED && sameOutcome(payment, result)) {
                return payment; // the acquirer's webhook got here first and recorded exactly this (ADR 0010)
            }
            if (result.approved()) {
                payment.authorize(result.processorReference());
                paymentEvents.authorized(payment);
            } else {
                payment.fail(result.declineReason());
                paymentEvents.failed(payment);
            }
            return payment;
        }));
    }

    /**
     * Takes {@code amount} (or the full authorized amount when null) and records it in the ledger,
     * in the same transaction as the status change.
     */
    public Payment capture(UUID paymentId, Money amount) {
        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        Money captureAmount = amount != null ? amount : current.getAmount();
        current.checkCapture(captureAmount); // 409/400 here, before the card is touched

        // One capture per payment, so the payment + amount is the key: a retry, or a second request racing
        // this one, gets the processor's first answer instead of a refusal, then gets 409 from the state check / @Version.
        paymentProcessor.capture(captureKey(paymentId, captureAmount), current.getProcessorReference(), captureAmount);

        return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = load(paymentId);
            if (payment.getStatus() == PaymentStatus.CAPTURED && payment.getCapturedAmount().equals(captureAmount)) {
                // Already recorded: by the acquirer's webhook, or by a concurrent request for the same capture
                // (same processor key, so the acquirer captured once). Either way this capture happened, once.
                return payment;
            }
            payment.capture(captureAmount);
            postCapture(payment, captureAmount);
            paymentEvents.captured(payment);
            return payment;
        }));
    }

    /** Releases the hold. No money moved, so no ledger entry. */
    public Payment voidAuthorization(UUID paymentId) {
        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        current.checkVoid();

        paymentProcessor.voidAuthorization(voidKey(paymentId), current.getProcessorReference());

        return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = load(paymentId);
            if (payment.getStatus() == PaymentStatus.VOIDED) {
                return payment; // already recorded (webhook or a concurrent void): same processor key, one void
            }
            payment.voidAuthorization();
            paymentEvents.voided(payment);
            return payment;
        }));
    }

    /**
     * Sends {@code amount} (or everything still refundable when null) back to the card,
     * records the refund and posts it to the ledger in one transaction.
     */
    public Refund refund(String idempotencyKey, UUID paymentId, Money amount, String reason) {
        // One payment can have many refunds, so unlike capture the key has to come from the client.
        UUID refundId = refundIdFor(idempotencyKey);
        Optional<Refund> earlier = transactionTemplate.execute(tx -> refundRepository.findById(refundId));
        if (earlier.isPresent()) {
            return earlier.get(); // a previous attempt finished; only its response was lost
        }

        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        Money refundAmount = amount != null ? amount : current.getRefundableAmount();
        current.checkRefund(refundAmount); // 409/400 here, before any money goes back

        String processorRefundReference = paymentProcessor.refund("refund:" + refundId, current.getProcessorReference(), refundAmount);

        // The money HAS gone back. If a concurrent refund on the same payment committed first, our
        // @Version check fails; re-reading and applying again is correct, because refunds add up and
        // the processor already checked the total. Returning 409 here would lose a real refund.
        return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = load(paymentId);
            payment.refund(refundAmount);
            Refund refund = refundRepository.save(new Refund(refundId, paymentId, refundAmount, processorRefundReference, reason));
            postRefund(payment, refund);
            paymentEvents.refunded(payment, refund); // a retried attempt rolls back its event too: still one event
            return refund;
        }));
    }

    public List<Refund> refunds(UUID paymentId) {
        return transactionTemplate.execute(tx -> {
            load(paymentId); // 404 for an unknown payment, not an empty list
            return refundRepository.findByPaymentIdOrderByCreatedAt(paymentId);
        });
    }

    public Payment get(UUID paymentId) {
        return transactionTemplate.execute(tx -> load(paymentId));
    }

    /**
     * One compound entry, two facts:
     *   the patient is charged for the service:   DR patient receivable  / CR hospital revenue
     *   the patient pays it by card at once:      DR processor receivable / CR patient receivable
     * Net: patient owes 0, the acquirer owes us the money until settlement, revenue is earned.
     */
    private void postCapture(Payment payment, Money amount) {
        claim(payment);
        LedgerAccount patientReceivable = ledgerService.patientReceivable(payment.getBillingAccountId());
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, amount.currency());
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());

        ledgerService.post(JournalEntry.draft("capture payment " + payment.getId())
                .source(EntrySource.CAPTURE, payment.getId())
                .debit(patientReceivable, amount)
                .credit(revenue, amount)
                .debit(processorReceivable, amount)
                .credit(patientReceivable, amount));
    }

    /**
     * The mirror image of the capture entry:
     *   the patient's charge is credited back:    DR refunds (contra-revenue) / CR patient receivable
     *   the money goes back to their card:        DR patient receivable     / CR processor receivable
     * Net: patient owes 0, the acquirer owes us less, refunds are visible next to revenue.
     */
    private void postRefund(Payment payment, Refund refund) {
        claim(payment);
        Money amount = refund.getAmount();
        LedgerAccount patientReceivable = ledgerService.patientReceivable(payment.getBillingAccountId());
        LedgerAccount refunds = ledgerService.systemAccount(AccountPurpose.REFUNDS, amount.currency());
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());

        ledgerService.post(JournalEntry.draft("refund payment " + payment.getId())
                .source(EntrySource.REFUND, refund.getId())
                .debit(refunds, amount)
                .credit(patientReceivable, amount)
                .debit(patientReceivable, amount)
                .credit(processorReceivable, amount));
    }

    /**
     * Applies a verified acquirer webhook (ADR 0010). Must run inside the caller's transaction, which also stores the
     * webhook_event row. Webhooks only move a payment FORWARD, along the state machine:
     *   - the payment is behind the acquirer (our step 3 failed: crash, DB down) -> catch up = APPLIED
     *   - the payment is already there or past it (normal case, duplicates, out-of-order) -> IGNORED_STALE
     *   - the event needs a state we haven't reached yet (captured while we're still CREATED) -> not ready,
     *     the caller answers 409 so the acquirer retries later
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GatewayEventOutcome applyGatewayEvent(GatewayEvent event) {
        Optional<Payment> found = paymentRepository.findById(event.paymentId());
        if (found.isEmpty()) {
            return GatewayEventOutcome.IGNORED_UNKNOWN_PAYMENT;
        }
        Payment payment = found.get();
        if (!payment.getAmount().currency().getCurrencyCode().equals(event.currency())
                || (payment.getProcessorReference() != null && !payment.getProcessorReference().equals(event.authorizationId()))) {
            log.warn("webhook {} doesn't match payment {} (currency/authorization), ignored", event.eventId(), payment.getId());
            return GatewayEventOutcome.IGNORED_MISMATCH;
        }
        PaymentStatus status = payment.getStatus();
        switch (event.type()) {
            case "payment.authorized" -> {
                if (status != PaymentStatus.CREATED) {
                    return GatewayEventOutcome.IGNORED_STALE;
                }
                payment.authorize(event.authorizationId());
                paymentEvents.authorized(payment);
            }
            case "payment.failed" -> {
                if (status != PaymentStatus.CREATED) {
                    return GatewayEventOutcome.IGNORED_STALE;
                }
                payment.fail(event.declineReason() != null ? event.declineReason() : "declined");
                paymentEvents.failed(payment);
            }
            case "payment.captured" -> {
                if (status == PaymentStatus.CREATED) {
                    throw new GatewayEventNotReadyException(event.eventId(), status);
                }
                if (status != PaymentStatus.AUTHORIZED) {
                    return GatewayEventOutcome.IGNORED_STALE;
                }
                Money captured = new Money(event.capturedMinor(), payment.getAmount().currency());
                payment.capture(captured);
                postCapture(payment, captured);
                paymentEvents.captured(payment);
            }
            case "payment.voided" -> {
                if (status == PaymentStatus.CREATED) {
                    throw new GatewayEventNotReadyException(event.eventId(), status);
                }
                if (status != PaymentStatus.AUTHORIZED) {
                    return GatewayEventOutcome.IGNORED_STALE;
                }
                payment.voidAuthorization();
                paymentEvents.voided(payment);
            }
            case "payment.refunded" -> {
                // Refund rows are created by our own call path (they carry our refund id). A refund the webhook
                // knows about but we don't is left to reconciliation (Day 8), not guessed here.
                return GatewayEventOutcome.RECORDED;
            }
            default -> {
                return GatewayEventOutcome.IGNORED_UNKNOWN_TYPE;
            }
        }
        log.info("webhook {} ({}) moved payment {} from {} to {}", event.eventId(), event.type(), payment.getId(),
                status, payment.getStatus());
        return GatewayEventOutcome.APPLIED;
    }

    /**
     * Write the payment's status change NOW (UPDATE ... WHERE version = ?), before the ledger entry. Hibernate would
     * otherwise flush the journal_entry INSERT first, and a concurrent request that lost the race would hit the
     * ledger's one-posting-per-capture unique index (500) instead of the @Version conflict we retry on.
     */
    private void claim(Payment payment) {
        paymentRepository.saveAndFlush(payment);
    }

    private static boolean sameOutcome(Payment payment, AuthorizationResult result) {
        return result.approved()
                ? result.processorReference().equals(payment.getProcessorReference())
                : payment.getStatus() == PaymentStatus.FAILED;
    }

    /** Shared with the expiry job: our void and the job's void of the same hold are one processor call. */
    static String voidKey(UUID paymentId) {
        return "void:" + paymentId;
    }

    private static String captureKey(UUID paymentId, Money amount) {
        return "capture:" + paymentId + ":" + amount.amountMinor() + ":" + amount.currency();
    }

    static UUID paymentIdFor(String idempotencyKey) {
        return idFromKey("payment", idempotencyKey);
    }

    static UUID refundIdFor(String idempotencyKey) {
        return idFromKey("refund", idempotencyKey);
    }

    /** Same key -> same id (name-based UUID v3), so a retried request finds the row the first attempt wrote. */
    private static UUID idFromKey(String kind, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        return UUID.nameUUIDFromBytes((kind + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T retryOnVersionConflict(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_RECORD_ATTEMPTS) {
                    // processor and DB now disagree; reconciliation (Day 8) must catch this
                    log.error("gave up recording a completed processor operation after {} attempts", attempt, e);
                    throw e;
                }
            }
        }
    }

    private Payment load(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
