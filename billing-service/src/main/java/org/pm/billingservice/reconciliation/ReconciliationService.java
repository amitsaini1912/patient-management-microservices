package org.pm.billingservice.reconciliation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.pm.billingservice.reconciliation.ReconRun.ReconItem;
import org.pm.billingservice.reconciliation.ReconciliationRepository.LedgerRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compares one UTC day of our LEDGER (captures and refunds, as posted to the processor-receivable account) with the
 * acquirer's settlement report for that day (ADR 0011).
 *
 * Matching keys:  capture -> payment id (= the acquirer's merchant_reference)
 *                 refund  -> the acquirer's refund id (= our refund.processor_reference)
 *
 * Cut-off: days are UTC [00:00Z, 24:00Z) on both sides. A capture we post at 23:59:59.900 that the acquirer books at
 * 00:00:00.100 lands on different days. So we look for the counterpart in a 3-day window (D-1, D, D+1): if it's there,
 * the transaction is matched (just booked across midnight) instead of showing up as "missing" on both days.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final SettlementSource settlementSource;
    private final ReconciliationRepository repository;
    private final Clock clock;

    public ReconciliationService(SettlementSource settlementSource, ReconciliationRepository repository, Clock clock) {
        this.settlementSource = settlementSource;
        this.repository = repository;
        this.clock = clock;
    }

    /** One side's view of one transaction (amounts summed if a side has several rows for one key). */
    private record Side(String type, String reference, long amountMinor, String currency, Instant at, List<String> ids) {
        Side plus(long amount, String id) {
            List<String> all = new ArrayList<>(ids);
            if (id != null) {
                all.add(id);
            }
            return new Side(type, reference, amountMinor + amount, currency, at, all);
        }
    }

    public ReconRun run(LocalDate date) {
        Instant started = clock.instant();
        List<SettlementRow> settlement = new ArrayList<>();
        try {
            for (LocalDate day = date.minusDays(1); !day.isAfter(date.plusDays(1)); day = day.plusDays(1)) {
                settlement.addAll(settlementSource.settlementFor(day));
            }
        } catch (RuntimeException e) {
            log.error("reconciliation {} failed: no settlement report", date, e);
            ReconRun failed = new ReconRun(UUID.randomUUID(), date, "FAILED", 0, 0, 0, 0,
                    truncate("settlement report unavailable: " + e.getMessage()), started, clock.instant(), List.of());
            repository.save(failed);
            return failed;
        }
        List<LedgerRow> ledger = repository.ledgerRows(startOf(date.minusDays(1)), startOf(date.plusDays(2)));

        Map<String, Side> ours = new LinkedHashMap<>();
        for (LedgerRow row : ledger) {
            String reference = row.sourceType().equals("CAPTURE") ? row.sourceId().toString()
                    : row.acquirerRefundId() != null ? row.acquirerRefundId() : "ours:" + row.sourceId();
            ours.merge(row.sourceType() + ":" + reference,
                    new Side(row.sourceType(), reference, row.amountMinor(), row.currency(), row.postedAt(), List.of()),
                    (a, b) -> a.plus(b.amountMinor(), null));
        }
        Map<String, Side> theirs = new LinkedHashMap<>();
        for (SettlementRow row : settlement) {
            String reference = row.type().equals("CAPTURE") ? row.merchantReference() : row.id();
            theirs.merge(row.type() + ":" + reference,
                    new Side(row.type(), reference, row.amountMinor(), row.currency(), row.occurredAt(), List.of(row.id())),
                    (a, b) -> a.plus(b.amountMinor(), row.id()));
        }

        // in scope: everything either side dated on D; counterparts may come from the whole window
        Set<String> keys = new TreeSet<>();
        ours.forEach((k, side) -> { if (onDay(side.at(), date)) keys.add(k); });
        theirs.forEach((k, side) -> { if (onDay(side.at(), date)) keys.add(k); });

        List<ReconItem> items = new ArrayList<>();
        int matched = 0;
        for (String key : keys) {
            Side our = ours.get(key);
            Side their = theirs.get(key);
            Classification problem = our == null ? Classification.MISSING_IN_LEDGER
                    : their == null ? Classification.MISSING_IN_SETTLEMENT
                    : !our.currency().equals(their.currency()) ? Classification.CURRENCY_MISMATCH
                    : our.amountMinor() != their.amountMinor() ? Classification.AMOUNT_MISMATCH
                    : null;
            if (problem == null) {
                matched++;
                continue;
            }
            Side any = our != null ? our : their;
            items.add(new ReconItem(problem, any.type(), any.reference(),
                    our == null ? null : our.amountMinor(), our == null ? null : our.currency(),
                    their == null ? null : their.amountMinor(), their == null ? null : their.currency(),
                    their == null ? null : String.join(",", their.ids())));
        }

        int ledgerRows = (int) ours.values().stream().filter(s -> onDay(s.at(), date)).count();
        int settlementRows = (int) theirs.values().stream().filter(s -> onDay(s.at(), date)).count();
        ReconRun run = new ReconRun(UUID.randomUUID(), date, "COMPLETED", ledgerRows, settlementRows, matched,
                items.size(), null, started, clock.instant(), items);
        repository.save(run);
        if (items.isEmpty()) {
            log.info("reconciliation {}: {} matched, no differences", date, matched);
        } else {
            log.warn("reconciliation {}: {} matched, {} DIFFERENCES - see GET /reconciliation/runs/{}", date, matched,
                    items.size(), date);
        }
        return run;
    }

    public Optional<ReconRun> latest(LocalDate date) {
        return repository.latest(date);
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static boolean onDay(Instant at, LocalDate day) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
