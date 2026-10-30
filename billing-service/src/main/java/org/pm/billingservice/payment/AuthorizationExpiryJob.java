package org.pm.billingservice.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.pm.billingservice.processor.PaymentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires authorizations that were never captured or voided within the TTL.
 *
 * Safe with several billing-service instances running: each batch is claimed with
 * SELECT ... FOR UPDATE SKIP LOCKED, so instances split the work instead of doing it twice or
 * waiting on each other. No scheduler-lock library needed.
 */
@Component
public class AuthorizationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpiryJob.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;
    private final PaymentEvents paymentEvents;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final PaymentProperties properties;

    public AuthorizationExpiryJob(PaymentRepository paymentRepository,
                                  PaymentProcessor paymentProcessor,
                                  PaymentEvents paymentEvents,
                                  TransactionTemplate transactionTemplate,
                                  Clock clock,
                                  PaymentProperties properties) {
        this.paymentRepository = paymentRepository;
        this.paymentProcessor = paymentProcessor;
        this.paymentEvents = paymentEvents;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${billing.payments.expiry-job.fixed-delay:PT5M}",
               initialDelayString = "${billing.payments.expiry-job.fixed-delay:PT5M}")
    public void run() {
        expireDueAuthorizations();
    }

    /** Returns the ids this run expired (only ever ones no other instance claimed). */
    public List<UUID> expireDueAuthorizations() {
        Instant cutoff = clock.instant().minus(properties.authorizationTtl());
        List<UUID> expiredIds = new ArrayList<>();

        while (true) {
            // Claim + expire in one short transaction. The row locks end at commit.
            List<Payment> batch = transactionTemplate.execute(tx -> {
                List<Payment> due = paymentRepository.lockAuthorizedBefore(cutoff, properties.expiryBatchSize());
                due.forEach(payment -> {
                    payment.expire();
                    paymentEvents.expired(payment); // same transaction as the status change
                });
                return due;
            });

            // Outside the transaction: never hold row locks while calling the processor.
            batch.forEach(this::releaseHold);
            batch.forEach(payment -> expiredIds.add(payment.getId()));

            if (batch.size() < properties.expiryBatchSize()) {
                break; // nothing more is due (or the rest is claimed by another instance)
            }
        }

        if (!expiredIds.isEmpty()) {
            log.info("expired {} authorization(s) older than {}", expiredIds.size(), cutoff);
        }
        return expiredIds;
    }

    /**
     * Best effort: the payment is already EXPIRED on our side either way, and the issuer drops an old
     * hold by itself. Releasing it early just gives the customer their available balance back sooner.
     */
    private void releaseHold(Payment payment) {
        try {
            paymentProcessor.voidAuthorization(PaymentService.voidKey(payment.getId()), payment.getProcessorReference());
        } catch (RuntimeException e) {
            log.warn("could not release hold for expired payment {}: {}", payment.getId(), e.getMessage());
        }
    }
}
