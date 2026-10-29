package org.pm.billingservice.repository;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.model.BillingAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest // JPA slice only; Flyway runs our real V1 migration (no ddl-auto create-drop here)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep the real Postgres, don't swap in H2
@Import(TestcontainersConfiguration.class)
class BillingAccountRepositoryIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");

    @Autowired
    private BillingAccountRepository billingAccountRepository;

    @Test
    void savesAndFindsByPatientId() {
        UUID patientId = UUID.randomUUID();
        billingAccountRepository.saveAndFlush(new BillingAccount(patientId, "John Doe", "john@test.com", INR));

        BillingAccount found = billingAccountRepository.findByPatientId(patientId).orElseThrow();

        assertThat(found.getCurrency()).isEqualTo(INR);
        assertThat(found.getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void databaseRejectsSecondAccountForSamePatient() {
        UUID patientId = UUID.randomUUID();
        billingAccountRepository.saveAndFlush(new BillingAccount(patientId, "John Doe", "john@test.com", INR));

        // The UNIQUE constraint is the real guarantee - code checks can race, the DB can't.
        assertThatThrownBy(() -> billingAccountRepository.saveAndFlush(
                new BillingAccount(patientId, "John Again", "john2@test.com", INR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
