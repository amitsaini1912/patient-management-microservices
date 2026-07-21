package org.pm.analyticservice.repository;

import org.pm.analyticservice.dto.DepartmentDenialRate;
import org.pm.analyticservice.dto.DepartmentRank;
import org.pm.analyticservice.dto.DepartmentRevenue;
import org.pm.analyticservice.dto.MonthlyTrend;
import org.pm.analyticservice.model.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

import org.pm.analyticservice.model.Claim;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    @Query(value = """                                                                                                                             
          SELECT v.department                      AS department,                                                                                    
                 SUM(c.claim_amount)               AS totalClaimed,                                                                                  
                 SUM(c.paid_amount)                AS totalPaid,                                                                                     
                 SUM(c.claim_amount - c.paid_amount) AS unrecovered                                                                                  
          FROM claim c                                                                                                                               
          JOIN visit v ON c.visit_id = v.id                                                                                                          
          GROUP BY v.department                                                                                                                      
          ORDER BY unrecovered DESC                                                                                                                  
          """, nativeQuery = true)
    List<DepartmentRevenue> getRevenueByDepartment();

    @Query(value = """                                                                                                                             
          SELECT v.department AS department,                                                                                                         
                 COUNT(*)     AS totalClaims,                                                                                                        
                 SUM(CASE WHEN c.status = 'DENIED' THEN 1 ELSE 0 END) AS deniedClaims,                                                               
                 ROUND(100.0 * SUM(CASE WHEN c.status = 'DENIED' THEN 1 ELSE 0 END) / COUNT(*), 1) AS denialRatePct                                  
          FROM claim c                                                                                                                               
          JOIN visit v ON c.visit_id = v.id                                                                                                          
          GROUP BY v.department                                                                                                                      
          ORDER BY denialRatePct DESC                                                                                                                
          """, nativeQuery = true)
    List<DepartmentDenialRate> getDenialRateByDepartment();

    @Query(value = """                                                                                                                                 
      SELECT v.department                                   AS department,                                                                           
             SUM(c.claim_amount)                            AS totalClaimed,                                                                         
             RANK() OVER (ORDER BY SUM(c.claim_amount) DESC) AS revenueRank                                                                          
      FROM claim c                                                                                                                                   
      JOIN visit v ON c.visit_id = v.id                                                                                                              
      GROUP BY v.department                                                                                                                          
      ORDER BY revenueRank                                                                                                                           
      """, nativeQuery = true)
    List<DepartmentRank> getDepartmentRevenueRanking();

    @Query(value = """
      WITH monthly AS (
          SELECT DATE_TRUNC('month', v.visit_date)::date AS month,
                 SUM(c.claim_amount)                     AS monthly_total
          FROM claim c
          JOIN visit v ON c.visit_id = v.id
          GROUP BY DATE_TRUNC('month', v.visit_date)
      )
      SELECT month                                          AS month,
             monthly_total                                  AS monthlyTotal,
             SUM(monthly_total) OVER (ORDER BY month)       AS runningTotal,
             LAG(monthly_total) OVER (ORDER BY month)       AS prevMonthTotal
      FROM monthly
      ORDER BY month
      """, nativeQuery = true)
    List<MonthlyTrend> getMonthlyTrend();

    // NAIVE: loads claims only. Touching each claim's visit later → N+1.
    List<Claim> findByStatus(String status);

    // FIXED: JOIN FETCH grabs claims AND their visits in ONE query.
    @Query("SELECT c FROM Claim c JOIN FETCH c.visit WHERE c.status = :status")
    List<Claim> findByStatusWithVisit(@Param("status") String status);
}