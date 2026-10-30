package org.pm.billingservice.webhook;

import java.util.Map;
import org.pm.billingservice.payment.GatewayEventNotReadyException;
import org.pm.billingservice.payment.GatewayEventOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhooks from the acquirer. Called by payment-network-simulator directly, NOT through api-gateway (no user JWT:
 * the HMAC signature is the authentication). Status codes are what the acquirer's retry logic reads:
 *   200 processed (applied, ignored or duplicate - all "done, don't resend")
 *   401 signature/timestamp wrong - not retried successfully, someone must look
 *   409 arrived too early, resend later
 */
@RestController
public class GatewayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookController.class);
    static final String SIGNATURE_HEADER = "Sim-Signature";

    private final WebhookSignatureVerifier verifier;
    private final GatewayWebhookService service;

    public GatewayWebhookController(WebhookSignatureVerifier verifier, GatewayWebhookService service) {
        this.verifier = verifier;
        this.service = service;
    }

    /** byte[]: the signature is over the exact bytes received, so we must not let Jackson parse first. */
    @PostMapping("/webhooks/gateway")
    public Map<String, String> receive(@RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                       @RequestBody(required = false) byte[] body) {
        verifier.verify(signature, body == null ? new byte[0] : body);
        GatewayEventOutcome outcome = service.handle(body);
        return Map.of("outcome", outcome.name());
    }

    @ExceptionHandler(WebhookSignatureVerifier.InvalidSignatureException.class)
    ProblemDetail invalidSignature(WebhookSignatureVerifier.InvalidSignatureException e) {
        log.warn("rejected webhook: {}", e.getMessage());
        // same answer for every reason: don't tell a forger which check failed
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid webhook signature");
        problem.setTitle("Unauthorized");
        return problem;
    }

    @ExceptionHandler(GatewayEventNotReadyException.class)
    ProblemDetail notReady(GatewayEventNotReadyException e) {
        log.info(e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Retry later");
        return problem;
    }
}
