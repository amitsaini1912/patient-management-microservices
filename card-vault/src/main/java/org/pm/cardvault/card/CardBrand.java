package org.pm.cardvault.card;

/**
 * Brand from the BIN (the first digits). A simplified table: enough for this project, not the full network
 * ranges (those change and come from BIN files). Checked in order: the first match wins.
 */
public enum CardBrand {
    AMEX, VISA, MASTERCARD, RUPAY, DISCOVER, UNKNOWN;

    public static CardBrand of(String pan) {
        int two = Integer.parseInt(pan.substring(0, 2));
        int three = Integer.parseInt(pan.substring(0, 3));
        int four = Integer.parseInt(pan.substring(0, 4));
        int six = Integer.parseInt(pan.substring(0, 6));
        if (two == 34 || two == 37) {
            return AMEX;
        }
        if (pan.charAt(0) == '4') {
            return VISA;
        }
        if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
            return MASTERCARD;
        }
        // RuPay (India): 60, 6521-6522, 81, 82, 508 - checked before Discover, which also uses 60/65
        if (two == 60 && four != 6011 || four == 6521 || four == 6522 || two == 81 || two == 82 || three == 508) {
            return RUPAY;
        }
        if (four == 6011 || (three >= 644 && three <= 649) || two == 65 || (six >= 622126 && six <= 622925)) {
            return DISCOVER;
        }
        return UNKNOWN;
    }
}
