package org.pm.billingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "billing_account")
public class BillingAccount {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false, unique = true)
    private UUID patientId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingAccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BillingAccount() {}   // JPA needs a no-arg constructor

    public BillingAccount(UUID patientId, String name, String email, Currency currency) {
        this.id = UUID.randomUUID();
        this.patientId = patientId;
        this.name = name;
        this.email = email;
        this.currency = currency;
        this.status = BillingAccountStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Currency getCurrency() { return currency; }
    public BillingAccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
