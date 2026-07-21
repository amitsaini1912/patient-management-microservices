package org.pm.analyticservice.repository;

import org.pm.analyticservice.dto.DepartmentDenialRate;
import org.pm.analyticservice.dto.DepartmentRevenue;
import org.pm.analyticservice.model.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

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
}