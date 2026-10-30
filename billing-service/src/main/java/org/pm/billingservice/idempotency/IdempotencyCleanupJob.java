package org.pm.billingservice.idempotency;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes keys past their TTL. One DELETE; running it on several instances at once is harmless. */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyStore store;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${billing.idempotency.cleanup-fixed-delay:PT1H}",
               initialDelayString = "${billing.idempotency.cleanup-fixed-delay:PT1H}")
    public void run() {
        deleteExpired();
    }

    public int deleteExpired() {
        int deleted = store.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("deleted {} expired idempotency key(s)", deleted);
        }
        return deleted;
    }
}
