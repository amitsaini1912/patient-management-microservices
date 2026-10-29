package org.pm.billingservice.payment;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.pm.billingservice.ledger.AccountPurpose;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every operation has the same three steps:
 *   1. short DB transaction: read + check (fail fast, before touching the card)
 *   2. call the processor, with NO transaction open (it may take seconds)
 *   3. short DB transaction: apply the result; @Version catches anyone who changed the payment meanwhile
 *
 * Known gap until Day 5 (idempotency) and Day 8 (reconciliation): if the processor call in step 2
 * succeeds and step 3 fails (crash, version conflict), the processor and our DB disagree.
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
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          BillingAccountRepository billingAccountRepository,
                          LedgerService ledgerService,
                          PaymentProcessor paymentProcessor,
                          TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.billingAccountRepository = billingAccountRepository;
        this.ledgerService = ledgerService;
        this.paymentProcessor = paymentProcessor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Creates the payment and asks the processor to hold the money.
     * A decline is a normal outcome: the payment is returned as FAILED. No money moved, so no ledger entry.
     */
    public Payment authorize(UUID billingAccountId, Money amount, String paymentMethodToken) {
        // Saved as CREATED before calling the processor, so a crash mid-call still leaves a trace to reconcile.
        Payment created = transactionTemplate.execute(tx -> {
            BillingAccount account = billingAccountRepository.findById(billingAccountId)
                    .orElseThrow(() -> new UnknownBillingAccountException(billingAccountId));
            if (!account.getCurrency().equals(amount.currency())) {
                // only same-currency payments until Day 9 (FX)
                throw new IllegalArgumentException("billing account " + billingAccountId + " is in "
                        + account.getCurrency() + ", payment is in " + amount.currency());
            }
            return paymentRepository.save(Payment.create(billingAccountId, amount));
        });

        AuthorizationResult result = paymentProcessor.authorize(created.getId(), amount, paymentMethodToken);

        return transactionTemplate.execute(tx -> {
            Payment payment = load(created.getId());
            if (result.approved()) {
                payment.authorize(result.processorReference());
            } else {
                payment.fail(result.declineReason());
            }
            return payment;
        });
    }

    /**
     * Takes {@code amount} (or the full authorized amount when null) and records it in the ledger,
     * in the same transaction as the status change.
     */
    public Payment capture(UUID paymentId, Money amount) {
        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        Money captureAmount = amount != null ? amount : current.getAmount();
        current.checkCapture(captureAmount); // 409/400 here, before the card is touched

        paymentProcessor.capture(current.getProcessorReference(), captureAmount);

        return transactionTemplate.execute(tx -> {
            Payment payment = load(paymentId);
            payment.capture(captureAmount);
            postCapture(payment, captureAmount);
            return payment;
        });
    }

    /** Releases the hold. No money moved, so no ledger entry. */
    public Payment voidAuthorization(UUID paymentId) {
        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        current.checkVoid();

        paymentProcessor.voidAuthorization(current.getProcessorReference());

        return transactionTemplate.execute(tx -> {
            Payment payment = load(paymentId);
            payment.voidAuthorization();
            return payment;
        });
    }

    /**
     * Sends {@code amount} (or everything still refundable when null) back to the card,
     * records the refund and posts it to the ledger in one transaction.
     */
    public Refund refund(UUID paymentId, Money amount, String reason) {
        Payment current = transactionTemplate.execute(tx -> load(paymentId));
        Money refundAmount = amount != null ? amount : current.getRefundableAmount();
        current.checkRefund(refundAmount); // 409/400 here, before any money goes back

        String processorRefundReference = paymentProcessor.refund(current.getProcessorReference(), refundAmount);

        // The money HAS gone back. If a concurrent refund on the same payment committed first, our
        // @Version check fails; re-reading and applying again is correct, because refunds add up and
        // the processor already checked the total. Returning 409 here would lose a real refund.
        return retryOnVersionConflict(() -> transactionTemplate.execute(tx -> {
            Payment payment = load(paymentId);
            payment.refund(refundAmount);
            Refund refund = refundRepository.save(new Refund(paymentId, refundAmount, processorRefundReference, reason));
            postRefund(payment, refundAmount);
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
        LedgerAccount patientReceivable = ledgerService.patientReceivable(payment.getBillingAccountId());
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, amount.currency());
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());

        ledgerService.post(JournalEntry.draft("capture payment " + payment.getId())
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
    private void postRefund(Payment payment, Money amount) {
        LedgerAccount patientReceivable = ledgerService.patientReceivable(payment.getBillingAccountId());
        LedgerAccount refunds = ledgerService.systemAccount(AccountPurpose.REFUNDS, amount.currency());
        LedgerAccount processorReceivable = ledgerService.systemAccount(AccountPurpose.PROCESSOR_RECEIVABLE, amount.currency());

        ledgerService.post(JournalEntry.draft("refund payment " + payment.getId())
                .debit(refunds, amount)
                .credit(patientReceivable, amount)
                .debit(patientReceivable, amount)
                .credit(processorReceivable, amount));
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
