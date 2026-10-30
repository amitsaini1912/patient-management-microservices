package org.pm.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Everything outside our company, simulated in one place (decision D2):
 *   acquirer/  the PSP billing-service talks to: authorize, capture, void, refund + signed webhooks (Day 7)
 *   network/   card network + issuer, ISO 8583-style (Day 11)
 * State is in memory on purpose (decision D7): restart = a fresh acquirer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentNetworkSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentNetworkSimulatorApplication.class, args);
    }
}
