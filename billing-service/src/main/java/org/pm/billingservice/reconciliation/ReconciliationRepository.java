package org.pm.billingservice.reconciliation;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One posting as the LEDGER sees it: the amount on the processor-receivable account = what the acquirer owes/gets. */
    record LedgerRow(String sourceType, UUID sourceId, String acquirerRefundId, long amountMinor, String currency,
                     Instant postedAt) {}

    List<LedgerRow> ledgerRows(Instant from, Instant to) {
        return jdbc.query("""
                        SELECT je.source_type, je.source_id, r.processor_reference, el.amount_minor, el.currency, je.posted_at
                        FROM journal_entry je
                        JOIN entry_line el ON el.journal_entry_id = je.id
                        JOIN ledger_account la ON la.id = el.ledger_account_id AND la.purpose = 'PROCESSOR_RECEIVABLE'
                        LEFT JOIN refund r ON je.source_type = 'REFUND' AND r.id = je.source_id
                        WHERE je.source_type IN ('CAPTURE', 'REFUND') AND je.posted_at >= ? AND je.posted_at < ?""",
                (rs, i) -> new LedgerRow(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()),
                Timestamp.from(from), Timestamp.from(to));
    }

    /** Run + its items in one transaction: a run is never half-written. */
    @Transactional
    void save(ReconRun run) {
        jdbc.update("""
                INSERT INTO recon_run (id, run_date, status, ledger_rows, settlement_rows, matched, mismatched, error,
                                       started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                run.id(), Date.valueOf(run.runDate()), run.status(), run.ledgerRows(), run.settlementRows(), run.matched(),
                run.mismatched(), run.error(), Timestamp.from(run.startedAt()), Timestamp.from(run.finishedAt()));
        for (ReconRun.ReconItem item : run.items()) {
            jdbc.update("""
                    INSERT INTO recon_item (id, run_id, classification, transaction_type, reference, ledger_amount_minor,
                                            ledger_currency, settlement_amount_minor, settlement_currency, settlement_transaction_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), run.id(), item.classification().name(), item.transactionType(), item.reference(),
                    item.ledgerAmountMinor(), item.ledgerCurrency(), item.settlementAmountMinor(), item.settlementCurrency(),
                    item.settlementTransactionId());
        }
    }

    /** The latest run for that day (a day can be re-run, e.g. after fixing something). */
    Optional<ReconRun> latest(LocalDate date) {
        return jdbc.query("""
                        SELECT id, run_date, status, ledger_rows, settlement_rows, matched, mismatched, error, started_at, finished_at
                        FROM recon_run WHERE run_date = ? ORDER BY started_at DESC LIMIT 1""",
                (rs, i) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    return new ReconRun(id, rs.getDate(2).toLocalDate(), rs.getString(3), rs.getInt(4), rs.getInt(5),
                            rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getTimestamp(9).toInstant(),
                            rs.getTimestamp(10).toInstant(), items(id));
                },
                Date.valueOf(date)).stream().findFirst();
    }

    private List<ReconRun.ReconItem> items(UUID runId) {
        return jdbc.query("""
                        SELECT classification, transaction_type, reference, ledger_amount_minor, ledger_currency,
                               settlement_amount_minor, settlement_currency, settlement_transaction_id
                        FROM recon_item WHERE run_id = ? ORDER BY classification, reference""",
                (rs, i) -> new ReconRun.ReconItem(Classification.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3),
                        (Long) rs.getObject(4), rs.getString(5), (Long) rs.getObject(6), rs.getString(7), rs.getString(8)),
                runId);
    }
}
