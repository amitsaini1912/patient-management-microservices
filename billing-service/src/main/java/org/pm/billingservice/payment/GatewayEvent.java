package org.pm.billingservice.payment;

import java.util.UUID;

/** The parts of a verified acquirer webhook that PaymentService needs. paymentId = the event's merchant_reference. */
public record GatewayEvent(String eventId, String type, UUID paymentId, String authorizationId, String currency,
                           long capturedMinor, String declineReason) {
}
