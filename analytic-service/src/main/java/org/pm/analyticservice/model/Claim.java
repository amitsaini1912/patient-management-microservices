package org.pm.analyticservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
public class Claim {
    @Id
    private UUID id;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "claim_amount", nullable = false)
    private BigDecimal claimAmount;

    @Column(name = "paid_amount", nullable = false)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private String status;

    public UUID getId() { return id; }
    public UUID getVisitId() { return visitId; }
    public BigDecimal getClaimAmount() { return claimAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public String getStatus() { return status; }
}