package org.pm.analyticservice.controller;

import org.pm.analyticservice.dto.*;
import org.pm.analyticservice.repository.ClaimRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/monthly-trend")
    public List<MonthlyTrend> monthlyTrend() {
        return claimRepository.getMonthlyTrend();
    }

    @GetMapping("/denial-rate-by-department")
    public List<DepartmentDenialRate> denialRateByDepartment() {
        return claimRepository.getDenialRateByDepartment();
    }

    @GetMapping("/revenue-ranking")
    public List<DepartmentRank> revenueRanking() {
        return claimRepository.getDepartmentRevenueRanking();
    }


    @GetMapping("/claims-naive/{status}")
    public List<ClaimView> claimsNaive(@PathVariable String status) {
        return claimRepository.findByStatus(status).stream()
                .map(c -> new ClaimView(c.getId().toString(),
                        c.getClaimAmount(),
                        c.getVisit().getDepartment()))   // ← triggers a query PER claim
                .toList();
    }

    @GetMapping("/claims-fetch/{status}")
    public List<ClaimView> claimsFetch(@PathVariable String status) {
        return claimRepository.findByStatusWithVisit(status).stream()
                .map(c -> new ClaimView(c.getId().toString(),
                        c.getClaimAmount(),
                        c.getVisit().getDepartment()))   // ← already loaded, no extra query
                .toList();
    }
}
