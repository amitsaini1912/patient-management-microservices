package org.pm.billingservice.payment;

import java.util.UUID;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.JournalEntry;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.model.BillingAccount;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.processor.AuthorizationResult;
import org.pm.billingservice.processor.PaymentProcessor;
import org.pm.billingservice.repository.BillingAccountRepository;
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

    private final PaymentRepository paymentRepository;
    private final BillingAccountRepository billingAccountRepository;
    private final LedgerService ledgerService;
    private final PaymentProcessor paymentProcessor;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          BillingAccountRepository billingAccountRepository,
                          LedgerService ledgerService,
                          PaymentProcessor paymentProcessor,
                          TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
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

    private Payment load(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
