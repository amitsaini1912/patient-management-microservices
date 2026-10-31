package org.pm.billingservice.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /payments/{id}/chargebacks, from the acquirer's dispute notice. No amount = everything still held.
 * reason: the category the risk engine receives (fraud, product_not_received, duplicate, credit_not_processed, other).
 */
public record ChargebackRequest(
        @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string like \"300.00\"") String amount,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like \"INR\"") String currency,
        @NotBlank @Pattern(regexp = "fraud|product_not_received|duplicate|credit_not_processed|other",
                message = "must be one of fraud, product_not_received, duplicate, credit_not_processed, other") String reason,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.:\\-]*", message = "letters, digits, _ . : - only") String caseReference) {

    @AssertTrue(message = "amount and currency must be given together")
    boolean isAmountAndCurrencyTogether() {
        return (amount == null) == (currency == null);
    }
}
