package org.pm.billingservice.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.pm.billingservice.idempotency.IdempotencyFilter;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Chargeback;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.Refund;
import org.pm.billingservice.risk.RiskContext;
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
 * The gateway sets X-User-Id / X-User-Role from the token (Day 12); review needs role ADMIN (Day 19).
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    static final String ROLE_HEADER = "X-User-Role";
    static final String ADMIN = "ADMIN";

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
        // who is paying, for the risk check (Day 19): client IP (the gateway's X-Forwarded-For) + optional X-Device-Id
        RiskContext context = RiskContext.of(http.getHeader("X-Forwarded-For"), http.getRemoteAddr(),
                http.getHeader(RiskContext.DEVICE_HEADER));
        Payment payment = paymentService.authorize(IdempotencyFilter.serviceKey(http, idempotencyKey), request.billingAccountId(),
                Money.parse(request.amount(), request.currency()), request.paymentMethodToken(), context);
        return ResponseEntity.created(URI.create("/payments/" + payment.getId())).body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    /**
     * Decide on a payment the risk check held (PENDING_REVIEW). ADMIN only (403 otherwise); 409 if it isn't held.
     * Approve -> authorized now (or FAILED if the network declines); reject -> FAILED, never sent to the network.
     */
    @PostMapping("/{id}/review")
    public PaymentResponse review(@PathVariable UUID id, @Valid @RequestBody ReviewPaymentRequest request,
                                  HttpServletRequest http) {
        requireAdmin(http, "reviewing a held payment");
        String user = http.getHeader(IdempotencyFilter.USER_HEADER);
        String reviewer = user == null || user.isBlank() ? "unknown" : user.trim();
        Payment.ReviewDecision decision = request.decision() == ReviewPaymentRequest.Action.APPROVE
                ? Payment.ReviewDecision.APPROVED : Payment.ReviewDecision.REJECTED;
        return PaymentResponse.from(paymentService.review(id, decision,
                reviewer.length() > 100 ? reviewer.substring(0, 100) : reviewer, request.note()));
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

    /**
     * Record a chargeback from the acquirer's dispute notice (Day 19b, ADR 0018). ADMIN only (an ops task).
     * 201 + Location; a retry with the same Idempotency-Key returns the same chargeback.
     */
    @PostMapping("/{id}/chargebacks")
    public ResponseEntity<ChargebackResponse> chargeBack(@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody ChargebackRequest request,
                                                         HttpServletRequest http) {
        requireAdmin(http, "recording a chargeback");
        Money amount = request.amount() == null ? null : Money.parse(request.amount(), request.currency());
        String caseReference = request.caseReference() == null || request.caseReference().isEmpty() ? null : request.caseReference();
        Chargeback chargeback = paymentService.chargeBack(IdempotencyFilter.serviceKey(http, idempotencyKey), id, amount,
                request.reason(), caseReference);
        return ResponseEntity.created(URI.create("/payments/" + id + "/chargebacks/" + chargeback.getId()))
                .body(ChargebackResponse.from(chargeback));
    }

    @GetMapping("/{id}/chargebacks")
    public List<ChargebackResponse> chargebacks(@PathVariable UUID id) {
        return paymentService.chargebacks(id).stream().map(ChargebackResponse::from).toList();
    }

    private static void requireAdmin(HttpServletRequest http, String what) {
        if (!ADMIN.equals(http.getHeader(ROLE_HEADER))) {
            throw new ForbiddenException(what + " needs the ADMIN role");
        }
    }

    @GetMapping("/{id}/refunds")
    public List<RefundResponse> refunds(@PathVariable UUID id) {
        return paymentService.refunds(id).stream().map(RefundResponse::from).toList();
    }
}
