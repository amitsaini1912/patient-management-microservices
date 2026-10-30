package org.pm.simulator.issuer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * simulator.issuer.* - the simulated bank behind every card.
 *
 * @param billingCurrency    the currency of every cardholder account (all our test cards are Indian cards)
 * @param defaultBalanceMinor balance of an account opened on a card's first authorization
 * @param slowResponse       how long the slow test card takes to answer AFTER deciding (longer than the network's
 *                           issuer timeout, so its approval comes back too late)
 */
@ConfigurationProperties("simulator.issuer")
public record IssuerProperties(
        @DefaultValue("INR") String billingCurrency,
        @DefaultValue("100000000") long defaultBalanceMinor,
        @DefaultValue("3s") Duration slowResponse) {
}
