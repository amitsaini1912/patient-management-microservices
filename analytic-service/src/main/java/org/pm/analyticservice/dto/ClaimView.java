package org.pm.analyticservice.dto;

import java.math.BigDecimal;

public record ClaimView(String claimId, BigDecimal amount, String department) {}
