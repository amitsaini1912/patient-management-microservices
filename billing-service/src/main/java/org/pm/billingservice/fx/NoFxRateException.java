package org.pm.billingservice.fx;

import java.util.Currency;

/** We can't price this conversion: no rate on file. A client error for the request (422), not a crash. */
public class NoFxRateException extends RuntimeException {

    public NoFxRateException(Currency base, Currency quote) {
        super("no FX rate for " + base.getCurrencyCode() + "->" + quote.getCurrencyCode());
    }
}
