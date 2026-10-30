package org.pm.billingservice.card;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.YearMonth;

/** What card-vault tells billing about a token (GET /cards/{token}). No card number, by design. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CardInfo(String token, String last4, String brand, String fingerprint, int expiryMonth, int expiryYear) {

    /** Valid through the last day of the expiry month. */
    public boolean isExpiredIn(YearMonth month) {
        return YearMonth.of(expiryYear, expiryMonth).isBefore(month);
    }
}
