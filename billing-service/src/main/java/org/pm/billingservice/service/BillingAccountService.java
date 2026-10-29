package org.pm.billingservice.service;

import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerAccountRepository;
import org.pm.billingservice.model.BillingAccount;
import org.pm.billingservice.repository.BillingAccountRepository;
import java.util.Currency;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BillingAccountService {

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("INR");

    private final BillingAccountRepository billingAccountRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final TransactionTemplate transactionTemplate;

    public BillingAccountService(BillingAccountRepository billingAccountRepository,
                                 LedgerAccountRepository ledgerAccountRepository,
                                 TransactionTemplate transactionTemplate) {
        this.billingAccountRepository = billingAccountRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Idempotent: calling it twice for the same patient returns the SAME account.
     * patient-service may retry the gRPC call (timeouts, circuit breaker), so a
     * second call must not create a second account.
     */
    public BillingAccount getOrCreateAccount(UUID patientId, String name, String email) {
        return billingAccountRepository.findByPatientId(patientId)
                .orElseGet(() -> createAccount(patientId, name, email));
    }

    private BillingAccount createAccount(UUID patientId, String name, String email) {
        try {
            // Account + its patient receivable in ONE transaction: either both exist or neither does.
            // A TransactionTemplate (not @Transactional on this method) so the transaction has ended,
            // and rolled back, before the catch below re-reads in a fresh one.
            return transactionTemplate.execute(status -> {
                BillingAccount account = billingAccountRepository.saveAndFlush(
                        new BillingAccount(patientId, name, email, DEFAULT_CURRENCY));
                ledgerAccountRepository.save(LedgerAccount.patientReceivable(account.getId(), account.getCurrency()));
                return account;
            });
        } catch (DataIntegrityViolationException e) {
            // Race: two calls both saw "no account" and both inserted. The UNIQUE
            // constraint on patient_id rejected the second insert -> return the winner's row.
            return billingAccountRepository.findByPatientId(patientId).orElseThrow(() -> e);
        }
    }
}
