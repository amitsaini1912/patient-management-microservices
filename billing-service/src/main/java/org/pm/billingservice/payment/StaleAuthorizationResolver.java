package org.pm.billingservice.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.pm.billingservice.processor.AuthorizationResult;
import org.pm.billingservice.processor.PaymentProcessor;
import org.pm.billingservice.processor.PaymentProcessorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles payments whose authorization outcome we never learned (Day 23 open item, ADR 0024).
 *
 * A payment is saved CREATED before the acquirer is called. If the client's request ends without an answer - acquirer
 * timeout (503, "retry with the same key"), a crash between the call and the recording - and the client never retries,
 * the row stays CREATED for ever, and the acquirer may hold the money on the card with nobody ever capturing it.
 * Webhooks heal most of these; this job catches the rest. Every minute it takes payments CREATED longer than
 * {@code staleCreatedAfter} (15 min) and asks the acquirer - a status inquiry, never a second authorization:
 *
 *   acquirer never received it  -> FAILED "not_received_by_acquirer" (no money moved anywhere)
 *   acquirer declined it        -> FAILED with the acquirer's reason and code, as if we had heard the answer
 *   acquirer authorized it      -> AUTHORIZED, then VOIDED at once: nobody was told it succeeded, so nobody will
 *                                  capture it; voiding gives the patient their money back today instead of in 7 days
 *   acquirer can't say          -> left CREATED, asked again next run
 *
 * After this, a late client retry with the same key gets the settled payment (FAILED / VOIDED) and must start a new
 * payment. Several billing instances may look at the same rows: each recording locks the row and re-checks that it is
 * still CREATED (a client retry or a webhook may have settled it meanwhile - then nothing is done), and the void uses
 * the payment's one void key, so the acquirer voids once.
 *
 * Known gap: a request that reaches the acquirer AFTER we recorded "not received" (delayed in the network for more
 * than 15 minutes) would be authorized there; its webhook is then ignored as stale and the hold lapses after ~7 days.
 */
@Component
public class StaleAuthorizationResolver {

    public enum Resolution { NOT_RECEIVED, DECLINED, VOIDED, AUTHORIZED_NOT_VOIDED, ALREADY_SETTLED, UNRESOLVED }

    static final String NOT_RECEIVED_REASON = "not_received_by_acquirer";

    private static final Logger log = LoggerFactory.getLogger(StaleAuthorizationResolver.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;
    private final PaymentService paymentService;
    private final PaymentEvents paymentEvents;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final PaymentProperties properties;
    private final Map<Resolution, Counter> counters = new EnumMap<>(Resolution.class);

    public StaleAuthorizationResolver(PaymentRepository paymentRepository,
                                      PaymentProcessor paymentProcessor,
                                      PaymentService paymentService,
                                      PaymentEvents paymentEvents,
                                      TransactionTemplate transactionTemplate,
                                      Clock clock,
                                      PaymentProperties properties,
                                      MeterRegistry meters) {
        this.paymentRepository = paymentRepository;
        this.paymentProcessor = paymentProcessor;
        this.paymentService = paymentService;
        this.paymentEvents = paymentEvents;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.properties = properties;
        for (Resolution resolution : Resolution.values()) {
            counters.put(resolution, Counter.builder("billing.payments.stale.resolved")
                    .description("Payments with an unknown authorization outcome, by what the acquirer said")
                    .tag("outcome", resolution.name().toLowerCase())
                    .register(meters));
        }
    }

    @Scheduled(fixedDelayString = "${billing.payments.stale-resolver.fixed-delay:PT1M}",
               initialDelayString = "${billing.payments.stale-resolver.fixed-delay:PT1M}")
    public void run() {
        try {
            resolveStalePayments();
        } catch (RuntimeException e) {
            log.warn("stale payment resolver run failed, next run retries: {}", e.toString());
        }
    }

    /** One batch, oldest first. Returns what happened to each payment it looked at. */
    public Map<UUID, Resolution> resolveStalePayments() {
        Instant cutoff = clock.instant().minus(properties.staleCreatedAfter());
        List<UUID> stale = transactionTemplate.execute(tx ->
                paymentRepository.findCreatedBefore(cutoff, properties.staleBatchSize()));
        Map<UUID, Resolution> results = new LinkedHashMap<>();
        for (UUID paymentId : stale) {
            Resolution resolution = resolve(paymentId);
            results.put(paymentId, resolution);
            counters.get(resolution).increment();
            if (resolution == Resolution.UNRESOLVED && results.size() == 1 && stale.size() > 1) {
                // first inquiry already failed: the acquirer is probably down - don't wait one timeout per payment
                break;
            }
        }
        if (!results.isEmpty()) {
            log.info("stale payment resolver: {}", results.values().stream()
                    .collect(Collectors.groupingBy(r -> r, Collectors.counting())));
        }
        return results;
    }

    private Resolution resolve(UUID paymentId) {
        Optional<AuthorizationResult> atAcquirer;
        try {
            atAcquirer = paymentProcessor.findAuthorization(paymentId); // outside any transaction
        } catch (PaymentProcessorException e) {
            log.warn("stale payment {}: acquirer inquiry failed, retried next run: {}", paymentId, e.getMessage());
            return Resolution.UNRESOLVED;
        }

        Resolution recorded = transactionTemplate.execute(tx -> {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
            if (payment.getStatus() != PaymentStatus.CREATED) {
                return Resolution.ALREADY_SETTLED; // a client retry or a webhook got here first
            }
            if (atAcquirer.isEmpty()) {
                payment.fail(NOT_RECEIVED_REASON);
                paymentEvents.failed(payment);
                return Resolution.NOT_RECEIVED;
            }
            AuthorizationResult result = atAcquirer.get();
            if (!result.approved()) {
                payment.fail(result.declineReason() != null ? result.declineReason() : "declined",
                        result.networkResponseCode(), result.retryable());
                paymentEvents.failed(payment);
                return Resolution.DECLINED;
            }
            payment.authorize(result.processorReference(), result.networkResponseCode());
            paymentEvents.authorized(payment);
            return Resolution.AUTHORIZED_NOT_VOIDED;
        });

        if (recorded != Resolution.AUTHORIZED_NOT_VOIDED) {
            log.info("stale payment {}: {}", paymentId, recorded);
            return recorded;
        }
        try {
            paymentService.voidAuthorization(paymentId);
            log.info("stale payment {}: authorized at the acquirer but never confirmed to the client - voided", paymentId);
            return Resolution.VOIDED;
        } catch (RuntimeException e) {
            // stays AUTHORIZED: a client can still capture it, otherwise the expiry job releases it after the TTL
            log.warn("stale payment {}: recorded as authorized, void failed: {}", paymentId, e.getMessage());
            return Resolution.AUTHORIZED_NOT_VOIDED;
        }
    }
}
