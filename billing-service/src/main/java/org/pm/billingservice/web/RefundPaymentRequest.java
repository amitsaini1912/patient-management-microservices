package org.pm.billingservice.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Empty body or no amount = refund everything that is still refundable. */
public record RefundPaymentRequest(
        @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string like \"300.00\"") String amount,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like \"INR\"") String currency,
        @Size(max = 255) String reason) {

    @AssertTrue(message = "amount and currency must be given together")
    boolean isAmountAndCurrencyTogether() {
        return (amount == null) == (currency == null);
    }
}
