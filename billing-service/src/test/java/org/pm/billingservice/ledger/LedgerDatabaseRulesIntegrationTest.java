package org.pm.billingservice.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same double-entry rules, enforced by Postgres alone. Everything here is raw SQL,
 * so JournalEntry.post() is bypassed completely - like a buggy service, a manual psql
 * session or a bad data migration would.
 */
@SpringBootTest(properties = "grpc.server.port=0") // same config as the other ITs -> Spring reuses one context
@Import(TestcontainersConfiguration.class)
class LedgerDatabaseRulesIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private BillingAccountService billingAccountService;

    private UUID receivableId;
    private UUID revenueId;

    @BeforeEach
    void freshAccounts() {
        UUID billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
        receivableId = ledgerService.patientReceivable(billingAccountId).getId();
        revenueId = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR).getId();
    }

    @Test
    void balancedRawInsertCommits() {
        UUID entryId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            insertEntry(entryId, null);
            insertLine(entryId, 1, receivableId, "DEBIT", 100_00);
            insertLine(entryId, 2, revenueId, "CREDIT", 100_00);
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_line WHERE journal_entry_id = ?",
                Integer.class, entryId)).isEqualTo(2);
    }

    @Test
    void unbalancedRawInsertIsRejectedAtCommit() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            insertEntry(entryId, null);
            insertLine(entryId, 1, receivableId, "DEBIT", 100_00);
            insertLine(entryId, 2, revenueId, "CREDIT", 99_99);
            // the INSERTs above all succeeded: the check is DEFERRED until COMMIT
        })).rootCause().hasMessageContaining("is unbalanced in INR");

        assertThat(entryExists(entryId)).isFalse(); // whole transaction rolled back
    }

    @Test
    void entryWithoutLinesIsRejectedAtCommit() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> insertEntry(entryId, null)))
                .rootCause().hasMessageContaining("at least 2 are required");

        assertThat(entryExists(entryId)).isFalse();
    }

    @Test
    void zeroOrNegativeLineAmountIsRejected() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            insertEntry(entryId, null);
            insertLine(entryId, 1, receivableId, "DEBIT", -100);
        })).rootCause().hasMessageContaining("entry_line_amount_minor_check");
    }

    @Test
    void lineCurrencyMustMatchAccountCurrency() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            insertEntry(entryId, null);
            jdbc.update("""
                    INSERT INTO entry_line (id, journal_entry_id, line_no, ledger_account_id, direction, amount_minor, currency)
                    VALUES (?, ?, 1, ?, 'DEBIT', 100, 'USD')""", UUID.randomUUID(), entryId, receivableId);
        })).rootCause().hasMessageContaining("entry_line_account_currency_fk");
    }

    @Test
    void postedLineCannotBeUpdated() {
        UUID entryId = postBalancedEntry();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE entry_line SET amount_minor = 1 WHERE journal_entry_id = ?", entryId))
                .rootCause().hasMessageContaining("entry_line is append-only: UPDATE is not allowed");
    }

    @Test
    void postedLineCannotBeDeleted() {
        UUID entryId = postBalancedEntry();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM entry_line WHERE journal_entry_id = ?", entryId))
                .rootCause().hasMessageContaining("entry_line is append-only: DELETE is not allowed");
    }

    @Test
    void postedEntryCannotBeUpdatedOrDeleted() {
        UUID entryId = postBalancedEntry();

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entry SET description = 'edited' WHERE id = ?", entryId))
                .rootCause().hasMessageContaining("journal_entry is append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entry WHERE id = ?", entryId))
                .rootCause().hasMessageContaining("journal_entry is append-only: DELETE is not allowed");
    }

    @Test
    void ledgerCannotBeTruncated() {
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE entry_line"))
                .rootCause().hasMessageContaining("entry_line is append-only: TRUNCATE is not allowed");
    }

    @Test
    void entryCanBeReversedOnlyOnceEvenWithRawSql() {
        UUID originalId = postBalancedEntry();
        postReversal(originalId);

        // two concurrent reversals would both pass the service's "already reversed?" check; this UNIQUE stops the second
        assertThatThrownBy(() -> postReversal(originalId))
                .rootCause().hasMessageContaining("journal_entry_reversal_of_key");
    }

    private UUID postBalancedEntry() {
        UUID entryId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            insertEntry(entryId, null);
            insertLine(entryId, 1, receivableId, "DEBIT", 100_00);
            insertLine(entryId, 2, revenueId, "CREDIT", 100_00);
        });
        return entryId;
    }

    private void postReversal(UUID originalId) {
        UUID entryId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            insertEntry(entryId, originalId);
            insertLine(entryId, 1, receivableId, "CREDIT", 100_00);
            insertLine(entryId, 2, revenueId, "DEBIT", 100_00);
        });
    }

    private void insertEntry(UUID entryId, UUID reversalOf) {
        jdbc.update("INSERT INTO journal_entry (id, description, reversal_of) VALUES (?, 'raw sql', ?)",
                entryId, reversalOf);
    }

    private void insertLine(UUID entryId, int lineNo, UUID accountId, String direction, long amountMinor) {
        jdbc.update("""
                INSERT INTO entry_line (id, journal_entry_id, line_no, ledger_account_id, direction, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?, ?, 'INR')""",
                UUID.randomUUID(), entryId, lineNo, accountId, direction, amountMinor);
    }

    private boolean entryExists(UUID entryId) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_entry WHERE id = ?", Integer.class, entryId) > 0;
    }
}
