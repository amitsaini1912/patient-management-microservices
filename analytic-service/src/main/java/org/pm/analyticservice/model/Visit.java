package org.pm.analyticservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDate;
import java.util.UUID;

@Entity
public class Visit {
    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(nullable = false)
    private String department;

    protected Visit() {}   // JPA needs this

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public LocalDate getVisitDate() { return visitDate; }
    public String getDepartment() { return department; }
}
