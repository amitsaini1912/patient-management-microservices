package org.pm.billingservice.reconciliation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 *   POST /reconciliation/runs?date=YYYY-MM-DD   run now (re-runs are fine; the latest run counts)
 *   GET  /reconciliation/runs/{date}            the latest run for that UTC day, with its differences
 */
@RestController
@RequestMapping("/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService service;
    private final Clock clock;

    public ReconciliationController(ReconciliationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<ReconRun> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date.isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new IllegalArgumentException("can't reconcile " + date + ": that day hasn't happened yet (UTC)");
        }
        ReconRun run = service.run(date);
        // FAILED = we couldn't get the acquirer's report: an upstream problem, like other processor failures
        return ResponseEntity.status("FAILED".equals(run.status()) ? HttpStatus.BAD_GATEWAY : HttpStatus.CREATED).body(run);
    }

    @GetMapping("/{date}")
    public ResponseEntity<ReconRun> latest(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.latest(date).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
