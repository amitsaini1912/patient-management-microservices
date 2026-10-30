package org.pm.simulator.acquirer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The acquirer API billing-service's SimulatorPaymentProcessor calls. Amounts in minor units, snake_case JSON.
 * Errors: {"error": {"code": "...", "message": "..."}} with 4xx; billing maps any non-2xx to a processor refusal.
 */
@RestController
@RequestMapping("/v1/authorizations")
public class AcquirerController {

    private static final String KEY = "Idempotency-Key";

    private final AcquirerService acquirer;

    public AcquirerController(AcquirerService acquirer) {
        this.acquirer = acquirer;
    }

    public record AuthorizeRequest(@NotBlank String merchantReference,
                                   @Positive long amountMinor,
                                   @Pattern(regexp = "[A-Z]{3}") String currency,
                                   @NotBlank String paymentMethodToken) {}

    public record AmountRequest(@Positive long amountMinor, @Pattern(regexp = "[A-Z]{3}") String currency,
                                String merchantRefundReference) {}

    /** 200 also for a decline: the authorization exists, its status says DECLINED (like billing's own API). */
    @PostMapping
    public AuthorizationState authorize(@RequestHeader(value = KEY, required = false) String key,
                                        @Valid @RequestBody AuthorizeRequest request) {
        return acquirer.authorize(key, request.merchantReference(), request.amountMinor(), request.currency(),
                request.paymentMethodToken());
    }

    @GetMapping("/{id}")
    public AuthorizationState get(@PathVariable String id) {
        return acquirer.get(id);
    }

    @PostMapping("/{id}/capture")
    public AuthorizationState capture(@RequestHeader(value = KEY, required = false) String key, @PathVariable String id,
                                      @Valid @RequestBody AmountRequest request) {
        return acquirer.capture(key, id, request.amountMinor(), request.currency());
    }

    @PostMapping("/{id}/void")
    public AuthorizationState voidAuthorization(@RequestHeader(value = KEY, required = false) String key,
                                                @PathVariable String id) {
        return acquirer.voidAuthorization(key, id);
    }

    @PostMapping("/{id}/refunds")
    public AcquirerService.RefundResult refund(@RequestHeader(value = KEY, required = false) String key,
                                               @PathVariable String id, @Valid @RequestBody AmountRequest request) {
        return acquirer.refund(key, id, request.amountMinor(), request.currency(), request.merchantRefundReference());
    }

    @ExceptionHandler(AcquirerException.class)
    ResponseEntity<Map<String, Object>> refused(AcquirerException e) {
        return ResponseEntity.status(e.status()).body(error(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("invalid_request", e.getBindingResult().toString()));
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
