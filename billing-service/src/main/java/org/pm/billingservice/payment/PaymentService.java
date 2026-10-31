package org.pm.billingservice.payment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.pm.billingservice.card.CardInfo;
import org.pm.billingservice.card.CardVaultClient;
import org.pm.billingservice.card.UnknownCardException;
import org.pm.billingservice.fx.FxRate;
import org.pm.billingservice.fx.FxRateService;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.EntrySource;
import org.pm.billingservice.ledger.JournalEntry;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.model.BillingAccount;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.AuthorizationResult;
import org.pm.billingservice.processor.PaymentProcessor;
import org.pm.billingservice.ratelimit.CardRateLimiter;
import org.pm.billingservice.risk.RiskAssessor;
import org.pm.billingservice.risk.RiskContext;
import org.pm.billingservice.risk.RiskDecision;
import org.pm.billingservice.risk.RiskVerdict;
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
 * Step 3 locks the payment row (SELECT ... FOR UPDATE, Day 21): concurrent requests on one payment record one after
 * another; @Version and the retry stay for the paths that don't lock (acquirer webhooks).
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
    private final ChargebackRepository chargebackRepository;
    private final BillingAccountRepository billingAccountRepository;
    private final LedgerService ledgerService;
    private final PaymentProcessor paymentProcessor;
    private final PaymentEvents paymentEvents;
    private final FxRateService fxRates;
    private final CardVaultClient cardVault;
    private final CardRateLimiter cardRateLimiter;
    private final RiskAssessor riskAssessor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          ChargebackRepository chargebackRepository,
                          BillingAccountRepository billingAccountRepository,
                          LedgerService ledgerService,
                          PaymentProcessor paymentProcessor,
                          PaymentEvents paymentEvents,
                          FxRateService fxRates,
                          CardVaultClient cardVault,
                          CardRateLimiter cardRateLimiter,
                          RiskAssessor riskAssessor,
                          Clock clock,
                          TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.chargebackRepository = chargebackRepository;
        this.billingAccountRepository = billingAccountRepository;
        this.ledgerService = ledgerService;
        this.paymentProcessor = paymentProcessor;
        this.paymentEvents = paymentEvents;
        this.fxRates = fxRates;
        this.cardVault = cardVault;
        this.cardRateLimiter = cardRateLimiter;
        this.riskAssessor = riskAssessor;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    /** Without client context (internal callers, tests): the risk engine sees an unknown IP and device. */
    public Payment authorize(String idempotencyKey, UUID billingAccountId, Money amount, String paymentMethodToken) {
        return authorize(idempotencyKey, billingAccountId, amount, paymentMethodToken, RiskContext.none());
    }

    /**
     * Creates the payment, asks the risk engine, then asks the processor to hold the money.
     * A decline is a normal outcome: the payment is returned as FAILED. No money moved, so no ledger entry.
     * Risk (ADR 0017): HOLD -> PENDING_REVIEW, BLOCK -> FAILED (risk_declined); neither reaches the card network.
     */
    public Payment authorize(String idempotencyKey, UUID billingAccountId, Money amount, String paymentMethodToken,
                             RiskContext riskContext) {
        UUID paymentId = paymentIdFor(idempotencyKey);
        Optional<Payment> earlier = transactionTemplate.execute(tx -> paymentRepository.findById(paymentId));
        if (earlier.isPresent() && earlier.get().getStatus() != PaymentStatus.CREATED) {
            return earlier.get(); // a previous attempt finished; only its response was lost
        }

        // The card: only a vault token reaches us. Check it's real and not expired BEFORE anything is charged.
        // (HTTP call outside any transaction; skipped on a retry that finds the CREATED row, which has its card.)
        CardInfo card = earlier.isPresent() ? null : resolveCard(paymentMethodToken);
        if (card != null) {
            // one attempt per NEW authorization of this card (ADR 0015); a retry of our own CREATED row is free.
            // Before anything is saved or charged: a refused attempt leaves no trace but the 429.
            cardRateLimiter.acquire(card.fingerprint());
        }

        // Saved as CREATED before calling the processor, so a crash mid-call still leaves a trace to reconcile.
        Payment created = earlier.isPresent() ? earlier.get() : transactionTemplate.execute(tx -> {
            BillingAccount account = billingAccountRepository.findById(billingAccountId)
                    .orElseThrow(() -> new UnknownBillingAccountException(billingAccountId));
            // The card may be charged in another supported currency; our books stay in the account's currency.
            // The rate is LOCKED now: the patient's INR charge doesn't move if the rate does before capture.
            fxRates.requireSupported(amount.currency());
            FxRate locked = fxRates.current(amount.currency(), account.getCurrency());
            Payment payment = Payment.create(paymentId, billingAccountId, amount, locked);
            payment.attachCard(card);
            payment.attachRiskContext(riskContext);
            return paymentRepository.save(payment);
        });

        // Risk BEFORE the card network sees anything. No transaction open, like the processor call; it takes at most
        // the risk budget (300 ms) and never throws: engine trouble -> billing's fallback rules. A retry of this
        // request sends the same facts (stored context), so the engine answers with its stored decision.
        RiskVerdict verdict = riskAssessor.assess(created);
        if (verdict.decision() != RiskDecision.ALLOW) {
            return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
                Payment payment = lockForRecording(created.getId());
                if (payment.getStatus() != PaymentStatus.CREATED) {
                    return payment; // a concurrent attempt of this same request recorded its verdict first
                }
                payment.recordRisk(verdict);
                if (verdict.decision() == RiskDecision.HOLD) {
                    payment.holdForReview();
                    paymentEvents.heldForReview(payment);
                } else {
                    payment.fail("risk_declined");
                    paymentEvents.failed(payment);
                }
                return payment;
            }));
        }

        AuthorizationResult result = paymentProcessor.authorize("authorize:" + paymentId, paymentId, amount, paymentMethodToken);

        Payment recorded = retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = lockForRecording(created.getId());
            if (payment.getStatus() == PaymentStatus.PENDING_REVIEW) {
                // an earlier attempt of this request was HELD (e.g. fallback while the engine was down): the hold
                // stands - authorizing now would skip the review
                return payment;
            }
            if (payment.getRiskDecision() == null) {
                payment.recordRisk(verdict);
            }
            if (payment.getStatus() != PaymentStatus.CREATED && sameOutcome(payment, result)) {
                return payment; // the acquirer's webhook got here first and recorded exactly this (ADR 0010)
            }
            if (result.approved()) {
                payment.authorize(result.processorReference(), result.networkResponseCode());
                paymentEvents.authorized(payment);
            } else {
                payment.fail(result.declineReason(), result.networkResponseCode(), result.retryable());
                paymentEvents.failed(payment);
            }
            return payment;
        }));
        if (recorded.getStatus() == PaymentStatus.PENDING_REVIEW && result.approved()) {
            paymentProcessor.voidAuthorization(voidKey(paymentId), result.processorReference()); // release our hold
        }
        return recorded;
    }

    /**
     * A person's decision on a PENDING_REVIEW payment (the controller lets only ADMIN call this).
     *   REJECTED: FAILED (risk_review_rejected), never sent to the card network.
     *   APPROVED: authorized now, with the same three steps as authorize(); the network may still decline it.
     * If someone else rejects it while our authorization is in flight, the rejection wins (409 here) and the hold we
     * just placed is voided, so no money stays reserved on the card.
     */
    public Payment review(UUID paymentId, Payment.ReviewDecision decision, String reviewer, String note) {
        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        current.checkReview(decision); // 409 before anything is sent

        if (decision == Payment.ReviewDecision.REJECTED) {
            return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
                Payment payment = lockForRecording(paymentId);
                payment.review(decision, reviewer, note, clock.instant());
                paymentEvents.reviewed(payment);
                paymentEvents.failed(payment);
                return payment;
            }));
        }

        AuthorizationResult result = paymentProcessor.authorize("authorize:" + paymentId, paymentId, current.getAmount(),
                current.getCardToken());
        try {
            return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
                Payment payment = lockForRecording(paymentId);
                if (payment.getReviewDecision() == Payment.ReviewDecision.APPROVED && sameOutcome(payment, result)) {
                    return payment; // a concurrent approval recorded this already (same processor key: one hold)
                }
                payment.review(decision, reviewer, note, clock.instant());
                paymentEvents.reviewed(payment);
                if (result.approved()) {
                    payment.authorize(result.processorReference(), result.networkResponseCode());
                    paymentEvents.authorized(payment);
                } else {
                    payment.fail(result.declineReason(), result.networkResponseCode(), result.retryable());
                    paymentEvents.failed(payment);
                }
                return payment;
            }));
        } catch (IllegalPaymentStateException e) {
            if (result.approved()) {
                paymentProcessor.voidAuthorization(voidKey(paymentId), result.processorReference());
            }
            throw e;
        }
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
            Payment payment = lockForRecording(paymentId);
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
            Payment payment = lockForRecording(paymentId);
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
            Payment payment = lockForRecording(paymentId);
            payment.refund(refundAmount);
            // today's market rate: the foreign money leaves our position at it (1 for same-currency payments)
            FxRate refundRate = fxRates.current(payment.getAmount().currency(), payment.getBookCurrency());
            Refund refund = refundRepository.save(new Refund(refundId, paymentId, refundAmount, processorRefundReference,
                    reason, refundRate.rate()));
            postRefund(payment, refund, refundRate);
            paymentEvents.refunded(payment, refund); // a retried attempt rolls back its event too: still one event
            return refund;
        }));
    }

    /**
     * Records a chargeback the acquirer told us about (ADR 0018): the network already took the money back, so there is
     * no processor call. Ledger loss + chargeback.created (for the risk engine's labels) in one transaction.
     * {@code amount} null = everything still held (captured - refunded - charged back).
     */
    public Chargeback chargeBack(String idempotencyKey, UUID paymentId, Money amount, String reason, String caseReference) {
        UUID chargebackId = idFromKey("chargeback", idempotencyKey);
        Optional<Chargeback> earlier = transactionTemplate.execute(tx -> chargebackRepository.findById(chargebackId));
        if (earlier.isPresent()) {
            return earlier.get(); // a previous attempt finished; only its response was lost
        }
        return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = lockForRecording(paymentId);
            Money chargebackAmount = amount != null ? amount : payment.getRefundableAmount();
            payment.chargeBack(chargebackAmount);
            // today's market rate: the foreign money leaves our position at it (1 for same-currency payments)
            FxRate rate = fxRates.current(payment.getAmount().currency(), payment.getBookCurrency());
            Chargeback chargeback = chargebackRepository.save(new Chargeback(chargebackId, paymentId, chargebackAmount,
                    reason, caseReference, rate.rate(), clock.instant()));
            postChargeback(payment, chargeback, rate);
            paymentEvents.chargedBack(payment, chargeback);
            return chargeback;
        }));
    }

    public List<Chargeback> chargebacks(UUID paymentId) {
        return transactionTemplate.execute(tx -> {
            load(paymentId);
            return chargebackRepository.findByPaymentIdOrderByCreatedAt(paymentId);
        });
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
     *
     * Paid in a foreign currency (ADR 0012), e.g. USD 100 at the locked rate 83.00 = INR 8,300.00:
     *   INR  DR patient receivable 8,300 / CR revenue              8,300   (the charge, in our books' currency)
     *   INR  DR FX clearing        8,300 / CR patient receivable   8,300   (paid: INR leg of the conversion)
     *   USD  DR processor rcv        100 / CR FX clearing            100   (the acquirer owes us USD)
     * Each currency balances on its own; FX clearing now holds USD 100 "bought" for INR 8,300.
     */
    private void postCapture(Payment payment, Money amount) {
        claim(payment);
        Currency book = payment.getBookCurrency();
        Money booked = payment.getLockedRate().convert(amount);
        LedgerAccount patientReceivable = ledgerService.patientReceivable(payment.getBillingAccountId());
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, book);
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());

        JournalEntry entry = JournalEntry.draft("capture payment " + payment.getId())
                .source(EntrySource.CAPTURE, payment.getId())
                .debit(patientReceivable, booked)
                .credit(revenue, booked);
        if (!payment.isForeignCurrency()) {
            entry.debit(processorReceivable, amount).credit(patientReceivable, amount);
        } else {
            entry.debit(ledgerService.systemAccount(AccountPurpose.FX_CLEARING, book), booked)
                    .credit(patientReceivable, booked)
                    .debit(processorReceivable, amount)
                    .credit(ledgerService.systemAccount(AccountPurpose.FX_CLEARING, amount.currency()), amount);
        }
        ledgerService.post(entry);
    }

    /**
     * The mirror image of the capture entry:
     *   the patient's charge is credited back:    DR refunds (contra-revenue) / CR patient receivable
     *   the money goes back to their card:        DR patient receivable     / CR processor receivable
     * Net: patient owes 0, the acquirer owes us less, refunds are visible next to revenue.
     *
     * Foreign currency, e.g. USD 50 refunded, locked rate 83.00, today 85.00:
     *   INR  DR refunds            4,150 / CR patient receivable 4,150   (sale reversed at the rate it was booked)
     *   INR  DR patient receivable 4,150 / CR FX clearing        4,250   (USD 50 leave our position at TODAY's rate)
     *   INR  DR FX gain/loss         100                                  (realized loss: the USD cost more to give back)
     *   USD  DR FX clearing           50 / CR processor rcv         50
     * A falling rate makes it a gain (CR FX gain/loss).
     */
    private void postRefund(Payment payment, Refund refund, FxRate refundRate) {
        claim(payment);
        Money amount = refund.getAmount();
        Currency book = payment.getBookCurrency();
        Money reversed = payment.getLockedRate().convert(amount);
        LedgerAccount patientReceivable = ledgerService.patientReceivable(payment.getBillingAccountId());
        LedgerAccount refunds = ledgerService.systemAccount(AccountPurpose.REFUNDS, book);
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());

        JournalEntry entry = JournalEntry.draft("refund payment " + payment.getId())
                .source(EntrySource.REFUND, refund.getId())
                .debit(refunds, reversed)
                .credit(patientReceivable, reversed);
        if (!payment.isForeignCurrency()) {
            entry.debit(patientReceivable, amount).credit(processorReceivable, amount);
        } else {
            Money atToday = refundRate.convert(amount);
            Money realized = atToday.minus(reversed); // > 0 loss, < 0 gain
            LedgerAccount gainLoss = ledgerService.systemAccount(AccountPurpose.FX_GAIN_LOSS, book);
            entry.debit(patientReceivable, reversed)
                    .credit(ledgerService.systemAccount(AccountPurpose.FX_CLEARING, book), atToday)
                    .debit(ledgerService.systemAccount(AccountPurpose.FX_CLEARING, amount.currency()), amount)
                    .credit(processorReceivable, amount);
            if (realized.isPositive()) {
                entry.debit(gainLoss, realized);
            } else if (realized.isNegative()) {
                entry.credit(gainLoss, realized.negate());
            }
        }
        ledgerService.post(entry);
    }

    /**
     * The acquirer took the money back and owes us that much less; it is a loss, not a refund we chose:
     *   DR chargebacks (expense) / CR processor receivable
     * The patient's side and revenue stay as they are (whether the hospital tries to collect again is a separate,
     * human decision). Foreign currency, e.g. USD 50 charged back, today 85.00:
     *   INR  DR chargebacks       4,250 / CR FX clearing      4,250   (the loss, at today's rate)
     *   USD  DR FX clearing          50 / CR processor rcv       50   (the acquirer owes us USD 50 less)
     */
    private void postChargeback(Payment payment, Chargeback chargeback, FxRate rate) {
        claim(payment);
        Money amount = chargeback.getAmount();
        Currency book = payment.getBookCurrency();
        LedgerAccount losses = ledgerService.systemAccount(AccountPurpose.CHARGEBACKS, book);
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());
        JournalEntry entry = JournalEntry.draft("chargeback on payment " + payment.getId())
                .source(EntrySource.CHARGEBACK, chargeback.getId());
        if (!payment.isForeignCurrency()) {
            entry.debit(losses, amount).credit(processorReceivable, amount);
        } else {
            Money atToday = rate.convert(amount);
            entry.debit(losses, atToday)
                    .credit(ledgerService.systemAccount(AccountPurpose.FX_CLEARING, book), atToday)
                    .debit(ledgerService.systemAccount(AccountPurpose.FX_CLEARING, amount.currency()), amount)
                    .credit(processorReceivable, amount);
        }
        ledgerService.post(entry);
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
                payment.authorize(event.authorizationId(), event.networkResponseCode());
                paymentEvents.authorized(payment);
            }
            case "payment.failed" -> {
                if (status != PaymentStatus.CREATED) {
                    return GatewayEventOutcome.IGNORED_STALE;
                }
                payment.fail(event.declineReason() != null ? event.declineReason() : "declined",
                        event.networkResponseCode(), event.retryable());
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

    private CardInfo resolveCard(String token) {
        if (token == null || token.isBlank()) {
            throw new UnknownCardException("a card token is required");
        }
        CardInfo card = cardVault.metadata(token).orElseThrow(() -> new UnknownCardException("unknown card token"));
        if (card.isExpiredIn(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new UnknownCardException("card ending " + card.last4() + " has expired");
        }
        return card;
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

    /** In the short "record the result" transactions: the row stays locked until commit (see findByIdForUpdate). */
    private Payment lockForRecording(UUID paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private Payment load(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
