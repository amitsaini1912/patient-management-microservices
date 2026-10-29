package org.pm.billingservice.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

/** Empty body or no amount = capture the full authorized amount. */
public record CapturePaymentRequest(
        @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string like \"600.00\"") String amount,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like \"INR\"") String currency) {

    @AssertTrue(message = "amount and currency must be given together")
    boolean isAmountAndCurrencyTogether() {
        return (amount == null) == (currency == null);
    }
}
