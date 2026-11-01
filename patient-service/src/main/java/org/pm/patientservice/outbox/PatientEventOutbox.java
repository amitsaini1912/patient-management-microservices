package org.pm.patientservice.outbox;

import java.util.UUID;
import org.pm.patientservice.model.Patient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import patient.events.PatientEvent;

/**
 * Writes a patient event into the outbox table (ADR 0030). MANDATORY: only inside the transaction that changes the
 * patient, so the patient and its event are committed together or not at all.
 */
@Component
public class PatientEventOutbox {

    public static final String PATIENT_CREATED = "PATIENT_CREATED";

    private final JdbcTemplate jdbc;

    public PatientEventOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID patientCreated(Patient patient) {
        PatientEvent event = PatientEvent.newBuilder()
                .setPatientId(patient.getId().toString())
                .setName(patient.getName())
                .setEmail(patient.getEmail())
                .setEventType(PATIENT_CREATED)
                .build();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_event_outbox (id, patient_id, event_type, payload) VALUES (?, ?, ?, ?)",
                id, patient.getId(), PATIENT_CREATED, event.toByteArray());
        return id;
    }
}
