package org.pm.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Everything outside our company, simulated in one place (decision D2):
 *   acquirer/  the PSP billing-service talks to: authorize, capture, void, refund + signed webhooks (Day 7)
 *   network/   card network (ISO 8583-style 0100/0110, 0400/0410), issuer/ the bank behind the cards (Day 11)
 *   card/      card-vault client: the acquirer detokenizes to build the 0100
 * State is in memory on purpose (decision D7): restart = a fresh acquirer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentNetworkSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentNetworkSimulatorApplication.class, args);
    }
}
