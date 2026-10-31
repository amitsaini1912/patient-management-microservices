package org.pm.billingservice.risk;

/**
 * The risk engine gave no usable answer. {@code retryable}: worth one more attempt if time is left (connection
 * refused, 502/503); not for a timeout (the budget is spent) or 401/409/422 (the same request fails again).
 */
public class RiskEngineUnavailableException extends RuntimeException {

    private final boolean retryable;
    private final String reason;

    public RiskEngineUnavailableException(String reason, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Short machine-readable cause, used as the fallback reason and a metric tag: timeout, unreachable, http_503... */
    public String reason() {
        return reason;
    }
}
