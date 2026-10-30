package org.pm.simulator.settlement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Every capture and refund, by the UTC day it happened. The daily settlement report is the slice for one day,
 * like the file a real acquirer publishes after the day closes (and pays out T+1).
 */
@Component
public class SettlementBook {

    private final Map<String, SettlementRecord> records = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public void record(String id, SettlementRecord.Type type, String authorizationId, String merchantReference,
                       long amountMinor, String currency) {
        records.put(id, new SettlementRecord(id, type, authorizationId, merchantReference, amountMinor, currency,
                clock.instant()));
    }

    /** Cut-off is midnight UTC: [date 00:00Z, date+1 00:00Z). */
    public List<SettlementRecord> forDate(LocalDate date) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return records.values().stream()
                .filter(r -> !r.occurredAt().isBefore(from) && r.occurredAt().isBefore(to))
                .sorted(Comparator.comparing(SettlementRecord::occurredAt))
                .toList();
    }

    /** Test knob: make the acquirer's books disagree with the merchant's. */
    public Optional<SettlementRecord> adjust(String id, Long amountMinor, String currency) {
        return Optional.ofNullable(records.computeIfPresent(id, (k, r) -> r.withAmount(
                amountMinor != null ? amountMinor : r.amountMinor(),
                currency != null ? currency : r.currency())));
    }
}
