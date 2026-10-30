package org.pm.billingservice.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.pm.billingservice.idempotency.IdempotencyFilter;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.Refund;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reached through the gateway as /api/payments/** (JWT checked there; StripPrefix removes /api).
 * Every POST needs an Idempotency-Key header; IdempotencyFilter checks it before we get here.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 201 even when the card is declined: the payment resource was created, its status says FAILED.
     * The client reads the status, not just the HTTP code.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> authorize(@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
                                                     @Valid @RequestBody CreatePaymentRequest request,
                                                     HttpServletRequest http) {
        // the key within the caller's scope (Day 12): two users' equal keys must not become one payment
        Payment payment = paymentService.authorize(IdempotencyFilter.serviceKey(http, idempotencyKey), request.billingAccountId(),
                Money.parse(request.amount(), request.currency()), request.paymentMethodToken());
        return ResponseEntity.created(URI.create("/payments/" + payment.getId())).body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@PathVariable UUID id,
                                   @Valid @RequestBody(required = false) CapturePaymentRequest request) {
        Money amount = request == null || request.amount() == null
                ? null // full capture
                : Money.parse(request.amount(), request.currency());
        return PaymentResponse.from(paymentService.capture(id, amount));
    }

    @PostMapping("/{id}/void")
    public PaymentResponse voidAuthorization(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.voidAuthorization(id));
    }

    /** A refund is its own resource (one payment can have several), so this is 201 + Location. */
    @PostMapping("/{id}/refunds")
    public ResponseEntity<RefundResponse> refund(@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody(required = false) RefundPaymentRequest request,
                                                 HttpServletRequest http) {
        Money amount = request == null || request.amount() == null
                ? null // refund everything still refundable
                : Money.parse(request.amount(), request.currency());
        Refund refund = paymentService.refund(IdempotencyFilter.serviceKey(http, idempotencyKey), id, amount, request == null ? null : request.reason());
        return ResponseEntity.created(URI.create("/payments/" + id + "/refunds/" + refund.getId()))
                .body(RefundResponse.from(refund));
    }

    @GetMapping("/{id}/refunds")
    public List<RefundResponse> refunds(@PathVariable UUID id) {
        return paymentService.refunds(id).stream().map(RefundResponse::from).toList();
    }
}
