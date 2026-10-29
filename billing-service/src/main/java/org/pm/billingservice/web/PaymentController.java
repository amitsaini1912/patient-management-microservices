package org.pm.billingservice.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reached through the gateway as /api/payments/** (JWT checked there; StripPrefix removes /api). */
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
    public ResponseEntity<PaymentResponse> authorize(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.authorize(request.billingAccountId(),
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
}
