package org.pm.billingservice.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReconRun(UUID id, LocalDate runDate, String status, int ledgerRows, int settlementRows, int matched,
                       int mismatched, String error, Instant startedAt, Instant finishedAt, List<ReconItem> items) {

    public record ReconItem(Classification classification, String transactionType, String reference,
                            Long ledgerAmountMinor, String ledgerCurrency, Long settlementAmountMinor,
                            String settlementCurrency, String settlementTransactionId) {}
}
