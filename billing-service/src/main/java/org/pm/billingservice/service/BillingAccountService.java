package org.pm.billingservice.service;

import org.pm.billingservice.model.BillingAccount;
import org.pm.billingservice.repository.BillingAccountRepository;
import java.util.Currency;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class BillingAccountService {

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("INR");

    private final BillingAccountRepository billingAccountRepository;

    public BillingAccountService(BillingAccountRepository billingAccountRepository) {
        this.billingAccountRepository = billingAccountRepository;
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
            return billingAccountRepository.saveAndFlush(
                    new BillingAccount(patientId, name, email, DEFAULT_CURRENCY));
        } catch (DataIntegrityViolationException e) {
            // Race: two calls both saw "no account" and both inserted. The UNIQUE
            // constraint on patient_id rejected the second insert -> return the winner's row.
            return billingAccountRepository.findByPatientId(patientId).orElseThrow(() -> e);
        }
    }
}
