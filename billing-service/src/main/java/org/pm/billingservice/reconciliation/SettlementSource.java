package org.pm.billingservice.reconciliation;

import java.time.LocalDate;
import java.util.List;

/** Where the acquirer's settlement report comes from. Throws if it can't be fetched (the run is then FAILED). */
public interface SettlementSource {

    /** All captures/refunds the acquirer booked on {@code date} (UTC day). */
    List<SettlementRow> settlementFor(LocalDate date);
}
