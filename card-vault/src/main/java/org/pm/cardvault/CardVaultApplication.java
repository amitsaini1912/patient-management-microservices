package org.pm.cardvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The only service that ever stores a card number (PAN). Everyone else holds a token (ADR 0013).
 *   POST /cards (TOKENIZE), GET /cards/{token} (READ_METADATA), POST /cards/{token}/detokenize (DETOKENIZE)
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CardVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardVaultApplication.class, args);
    }
}
