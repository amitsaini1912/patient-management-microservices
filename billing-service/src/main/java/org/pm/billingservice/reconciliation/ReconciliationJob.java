package org.pm.billingservice.reconciliation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every day at 02:00 UTC, reconcile YESTERDAY (UTC). The acquirer's report for a day is only complete after that day
 * has ended; real acquirers publish it some hours later and pay out T+1 (or later), hence the gap.
 */
@Component
public class ReconciliationJob {

    private final ReconciliationService service;
    private final Clock clock;

    public ReconciliationJob(ReconciliationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${billing.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
    public void run() {
        service.run(LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1));
    }
}
