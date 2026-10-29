package org.pm.billingservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Amount is a decimal STRING ("1000.00"), never a JSON number: a number would be parsed as a
 * double by many clients and lose precision (ADR 0001).
 */
public record CreatePaymentRequest(
        @NotNull UUID billingAccountId,
        @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string like \"1000.00\"") String amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like \"INR\"") String currency,
        @NotBlank String paymentMethodToken) {
}
