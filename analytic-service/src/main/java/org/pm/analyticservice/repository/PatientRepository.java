package org.pm.analyticservice.repository;

import org.pm.analyticservice.model.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {
}
