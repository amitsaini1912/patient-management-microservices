package org.pm.cardvault.card;

/** The Luhn (mod 10) check digit: catches every single-digit typo and most swaps of neighbouring digits. */
public final class Luhn {

    private Luhn() {}

    /** True if {@code digits} (0-9 only) passes the check. */
    public static boolean isValid(CharSequence digits) {
        int sum = 0;
        boolean doubleIt = false; // from the right: the check digit itself is not doubled
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return false;
            }
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return digits.length() > 0 && sum % 10 == 0;
    }
}
