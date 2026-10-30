package org.pm.simulator.network;

import java.time.Instant;
import java.time.YearMonth;

/**
 * Simplified ISO 8583 messages: Java records with the field numbers in comments, not a binary bitmap parser.
 * The parts that matter for the story are real: the MTI pairs (0100/0110 authorization, 0400/0410 reversal),
 * STAN + RRN to match a response or a reversal to its request, field 39 response code, and field 6 for the
 * amount in the cardholder's billing currency.
 *
 * Only the 0100 carries the PAN (field 2). Its toString() masks it, so logging a message never leaks a card number.
 */
public final class Iso8583 {

    private Iso8583() {}

    /** MTI 0100, acquirer -> network -> issuer: "may this card pay this much?" */
    public record AuthorizationRequest(
            String pan,                 // 2
            long amountMinor,           // 4  amount, transaction currency
            long billingAmountMinor,    // 6  amount, cardholder billing currency (set by the network)
            Instant transmittedAt,      // 7
            String stan,                // 11 system trace audit number, unique per acquirer per day
            YearMonth expiry,           // 14
            String rrn,                 // 37 retrieval reference number, identifies the transaction end to end
            String currency,            // 49 transaction currency
            String billingCurrency) {   // 51 cardholder billing currency

        public static final String MTI = "0100";

        public AuthorizationRequest withBilling(long billingAmountMinor, String billingCurrency) {
            return new AuthorizationRequest(pan, amountMinor, billingAmountMinor, transmittedAt, stan, expiry, rrn,
                    currency, billingCurrency);
        }

        @Override
        public String toString() {
            return MTI + "{pan=" + maskPan(pan) + ", amount=" + amountMinor + " " + currency + ", billing="
                    + billingAmountMinor + " " + billingCurrency + ", stan=" + stan + ", rrn=" + rrn + "}";
        }
    }

    /** MTI 0110, issuer -> network -> acquirer. */
    public record AuthorizationResponse(
            String stan,                // 11 echoed
            String rrn,                 // 37 echoed
            String authorizationCode,   // 38 the issuer's approval code; null unless approved
            ResponseCode responseCode) {// 39

        public static final String MTI = "0110";
    }

    /** Why the authorization is being reversed (field 25-ish "message reason"). */
    public enum ReversalReason {
        /** the merchant cancelled before capture (void, or our auth expiry) */
        VOID,
        /** nobody got the 0110 in time; the issuer may have approved, so undo it just in case */
        TIMEOUT
    }

    /**
     * MTI 0400: "release the hold of the original authorization". Points at it by RRN + original STAN
     * (field 90, original data elements), so no PAN is needed.
     */
    public record ReversalRequest(
            String stan,                // 11 a new STAN for this message
            String originalRrn,         // 37
            String originalStan,        // 90
            ReversalReason reason) {

        public static final String MTI = "0400";
    }

    /** MTI 0410. Issuers answer 00 to every reversal, also a duplicate or one they can't match yet. */
    public record ReversalResponse(String stan, String originalRrn, ResponseCode responseCode) {

        public static final String MTI = "0410";
    }

    /** PCI DSS allows showing the last 4 digits. Nothing else of the PAN ever leaves this method. */
    public static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****" + pan.substring(pan.length() - 4);
    }
}
