package org.pm.cardvault.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pm.cardvault.card.Luhn;

/**
 * Replaces anything that looks like a card number with {@code [PAN ****1234]}: 13-19 digits, optionally separated by
 * single spaces or dashes, that pass Luhn. Luhn keeps false positives down (order ids, timestamps, phone numbers
 * mostly fail it), so only real-looking PANs are hidden.
 */
public final class PanMasker {

    // a digit, then 12-18 more digits each optionally preceded by one space/dash; not glued to other digits
    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");

    private PanMasker() {}

    public static String mask(String text) {
        if (text == null || text.length() < 13) {
            return text;
        }
        Matcher m = CANDIDATE.matcher(text);
        StringBuilder out = null;
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (Luhn.isValid(digits)) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                m.appendReplacement(out, "[PAN ****" + digits.substring(digits.length() - 4) + "]");
            }
        }
        if (out == null) {
            return text;
        }
        m.appendTail(out);
        return out.toString();
    }
}
