package org.pm.analyticservice.dto;

import java.math.BigDecimal;

public interface DepartmentRevenue {
    String getDepartment();
    BigDecimal getTotalClaimed();
    BigDecimal getTotalPaid();
    BigDecimal getUnrecovered();
}