package org.pm.analyticservice.dto;

import java.math.BigDecimal;

public interface DepartmentRank {
    String getDepartment();
    BigDecimal getTotalClaimed();
    Long getRevenueRank();
}
