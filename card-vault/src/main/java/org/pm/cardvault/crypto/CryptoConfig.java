package org.pm.cardvault.crypto;

import java.time.Clock;
import org.pm.cardvault.security.VaultProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    @Bean
    KeyRing keyRing(VaultProperties properties) {
        return KeyRing.parse(properties.encryptionKeys(), properties.activeKeyVersion());
    }

    @Bean
    CardCipher cardCipher(KeyRing keyRing) {
        return new CardCipher(keyRing);
    }

    @Bean
    Fingerprinter fingerprinter(VaultProperties properties) {
        return new Fingerprinter(properties.fingerprintKey());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
