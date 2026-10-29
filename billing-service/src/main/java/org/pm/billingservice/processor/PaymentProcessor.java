package org.pm.billingservice.processor;

import java.util.UUID;
import org.pm.billingservice.money.Money;

/**
 * The card acquirer we send payments to. Today: {@link FakePaymentProcessor} (in memory).
 * Day 7: a client for payment-network-simulator, behind this same interface.
 *
 * Implementations are called OUTSIDE any database transaction: they may be slow, and we
 * never hold DB connections or row locks while waiting on the network.
 */
public interface PaymentProcessor {

    /** Ask the issuer to hold {@code amount} on the card. A decline is a normal result, not an exception. */
    AuthorizationResult authorize(UUID paymentId, Money amount, String paymentMethodToken);

    /** Take all or part of a held amount. Throws {@link PaymentProcessorException} if the processor refuses. */
    void capture(String processorReference, Money amount);

    /** Release the hold. Throws {@link PaymentProcessorException} if the processor refuses. */
    void voidAuthorization(String processorReference);
}
