package org.pm.analyticservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class Patient {
    @Id
    private UUID id;
    private String name;
    private String email;

    protected Patient() {}   // JPA needs a no-arg constructor

    public Patient(UUID id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}
