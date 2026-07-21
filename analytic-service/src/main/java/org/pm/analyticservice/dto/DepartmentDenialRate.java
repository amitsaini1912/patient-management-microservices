package org.pm.analyticservice.dto;

import java.math.BigDecimal;

public interface DepartmentDenialRate {
    String getDepartment();
    Long getTotalClaims();
    Long getDeniedClaims();
    BigDecimal getDenialRatePct();
}