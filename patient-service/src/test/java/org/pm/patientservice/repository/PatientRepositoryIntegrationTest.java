package org.pm.patientservice.repository;

import org.junit.jupiter.api.Test;
import org.pm.patientservice.model.Patient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest  // load only the JPA slice (entities, repositories, Hibernate). No web layer. Each test rolls back in a transaction → isolation for free.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) //critical. By default @DataJpaTest swaps your DB for in-memory H2. Replace.NONE says "don't — use the datasource I provide" (our container). Delete this line and your test silently runs on H2, defeating the whole point.
@Testcontainers  // activates the plugin that starts/stops @Container fields around tests.
// Against a REAL Postgres, Spring's default ddl-auto is "none", so no table is created.
// Tell Hibernate to build the schema from our @Entity for this fresh throwaway container.
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PatientRepositoryIntegrationTest {

    @Container // manage this container's lifecycle.
    @ServiceConnection  //reads the container's host/port/user/password and auto-points Spring's datasource at it — zero manual JDBC config.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16"); //a real Postgres 16 container. static = one shared container for all tests in the class (faster). "postgres:16" = the Docker Hub image, version-pinned for reproducibility.

    @Autowired
    private PatientRepository patientRepository;

    private Patient persistPatient(String email) {
        Patient patient = new Patient();
        patient.setName("John Doe");
        patient.setEmail(email);
        patient.setAddress("123 Main St");
        patient.setDateOfBirth(LocalDate.parse("1990-01-01"));
        patient.setRegisteredDate(LocalDate.parse("2024-01-01"));
        return patientRepository.save(patient);
    }

    @Test
    void existsByEmailShouldReflectRealDatabaseState() {
        persistPatient("john@test.com");

        assertThat(patientRepository.existsByEmail("john@test.com")).isTrue();
        assertThat(patientRepository.existsByEmail("missing@test.com")).isFalse();
    }

    @Test
    void existsByEmailAndIdNotShouldIgnoreThePatientsOwnRow() {
        Patient saved = persistPatient("john@test.com");

        // Same email, but it's THIS patient's own row -> not a conflict
        assertThat(patientRepository.existsByEmailAndIdNot("john@test.com", saved.getId()))
                .isFalse();

        // Same email, but a DIFFERENT id -> a real duplicate conflict
        assertThat(patientRepository.existsByEmailAndIdNot("john@test.com", UUID.randomUUID()))
                .isTrue();
    }
}