package org.pm.billingservice.risk;

import java.time.Duration;
import org.pm.billingservice.payment.Payment;

/** One call to the risk engine for one payment. Throws {@link RiskEngineUnavailableException}, never hangs past {@code timeout}. */
public interface RiskEngineClient {

    RiskVerdict assess(Payment payment, Duration timeout);
}
