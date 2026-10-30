package org.pm.simulator.network;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * simulator.network.* - the card scheme between acquirer and issuer.
 *
 * @param issuerTimeout how long the network waits for a 0110 before answering 91 itself and reversing.
 *                      Must stay well below billing's read timeout (5s), so billing gets a clear decline, not a timeout.
 * @param schemeRates   1 unit of the key currency = this many units of the issuer's billing currency (field 6/10).
 *                      Fixed numbers: a real scheme publishes new rates every day.
 */
@ConfigurationProperties("simulator.network")
public record NetworkProperties(
        @DefaultValue("1s") Duration issuerTimeout,
        Map<String, BigDecimal> schemeRates) {

    public NetworkProperties {
        schemeRates = schemeRates == null ? Map.of() : Map.copyOf(schemeRates);
    }
}
