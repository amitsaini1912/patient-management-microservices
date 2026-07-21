package org.pm.analyticservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface MonthlyTrend {
    LocalDate getMonth();
    BigDecimal getMonthlyTotal();
    BigDecimal getRunningTotal();
    BigDecimal getPrevMonthTotal();
}