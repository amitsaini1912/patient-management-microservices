package org.pm.billingservice.reconciliation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Chargeback;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Chargebacks are on the acquirer's dispute report, not its settlement report: settlement recon must not see them. */
@SpringBootTest(properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class ChargebackReconciliationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private ReconciliationRepository repository;

    @Test
    void settlementReconSkipsChargebackEntries() {
        UUID account = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Recon Patient", "recon-cb@test.com").getId();
        Payment payment = paymentService.authorize(UUID.randomUUID().toString(), account, Money.ofMinor(700_00, "INR"), "pm_card_visa");
        paymentService.capture(payment.getId(), null);
        Chargeback chargeback = paymentService.chargeBack(UUID.randomUUID().toString(), payment.getId(), null, "fraud", null);

        var rows = repository.ledgerRows(Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(rows).anyMatch(r -> r.sourceType().equals("CAPTURE") && r.sourceId().equals(payment.getId()));
        assertThat(rows).noneMatch(r -> r.sourceId().equals(chargeback.getId()));
    }
}
