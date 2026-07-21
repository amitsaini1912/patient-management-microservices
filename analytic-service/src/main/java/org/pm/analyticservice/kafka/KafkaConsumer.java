package org.pm.analyticservice.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import org.pm.analyticservice.model.Patient;
import org.pm.analyticservice.repository.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import patient.events.PatientEvent;
import java.util.UUID;

@Service
public class KafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);

    private final PatientRepository patientRepository;

    public KafkaConsumer(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @KafkaListener(topics = "patient", groupId = "analytic-service")
    public void consumeEvent(byte[] event) {
        try {
            PatientEvent patientEvent = PatientEvent.parseFrom(event);

            Patient patient = new Patient(
                    UUID.fromString(patientEvent.getPatientId()),
                    patientEvent.getName(),
                    patientEvent.getEmail());

            patientRepository.save(patient);   // idempotent upsert — the id IS the key

            log.info("Saved patient to analytics store: [PatientId={}, Name={}]",
                    patientEvent.getPatientId(), patientEvent.getName());
        } catch (InvalidProtocolBufferException e) {
            log.error("Error deserializing event {}", e.getMessage());
        }
    }
}