package org.pm.analyticservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
public class Claim {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "claim_amount", nullable = false)
    private BigDecimal claimAmount;

    @Column(name = "paid_amount", nullable = false)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private String status;

    protected Claim() {}

    public UUID getId() { return id; }
    public Visit getVisit() { return visit; }
    public BigDecimal getClaimAmount() { return claimAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public String getStatus() { return status; }
}