package org.pm.analyticservice.controller;

import org.pm.analyticservice.dto.DepartmentDenialRate;
import org.pm.analyticservice.dto.DepartmentRevenue;
import org.pm.analyticservice.repository.ClaimRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final ClaimRepository claimRepository;

    public AnalyticsController(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @GetMapping("/revenue-by-department")
    public List<DepartmentRevenue> revenueByDepartment() {
        return claimRepository.getRevenueByDepartment();
    }

    @GetMapping("/denial-rate-by-department")
    public List<DepartmentDenialRate> denialRateByDepartment() {
        return claimRepository.getDenialRateByDepartment();
    }
}
