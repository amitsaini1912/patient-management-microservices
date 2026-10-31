package org.pm.billingservice.db;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.io.EOFException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseErrorsTest {

    @Test
    void connectionProblemsAreUnavailableWhereverTheyAreInTheCauseChain() {
        assertThat(DatabaseErrors.isUnavailable(new DataAccessResourceFailureException("I/O error"))).isTrue();
        assertThat(DatabaseErrors.isUnavailable(new CannotCreateTransactionException("no connection"))).isTrue();
        // Hikari: "Connection is not available, request timed out after 2000ms"
        assertThat(DatabaseErrors.isUnavailable(new CannotCreateTransactionException("x",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))).isTrue();
        // the Toxiproxy case: the rollback failed on the closed connection and hid the original error
        assertThat(DatabaseErrors.isUnavailable(new JpaSystemException(new RuntimeException("Unable to rollback",
                new SQLException("Connection is closed"))))).isTrue();
        assertThat(DatabaseErrors.isUnavailable(new TransactionSystemException("commit failed",
                new SQLException("An I/O error occurred while sending to the backend.", "08006", new EOFException()))))
                .isTrue();
    }

    @Test
    void postgresShuttingDownOrStartingIsUnavailable() {
        // seen live (Day 23, docker stop billing-db under load): GenericJDBCException -> JpaSystemException
        for (String state : new String[] {"57P01", "57P02", "57P03"}) {
            assertThat(DatabaseErrors.isUnavailable(new JpaSystemException(new RuntimeException("JDBC exception",
                    new SQLException("FATAL: terminating connection due to administrator command", state)))))
                    .as(state).isTrue();
        }
    }

    @Test
    void realErrorsAreNot() {
        assertThat(DatabaseErrors.isUnavailable(new DataIntegrityViolationException("duplicate key",
                new SQLException("duplicate key value violates unique constraint", "23505")))).isFalse();
        assertThat(DatabaseErrors.isUnavailable(new JpaSystemException(new RuntimeException("bug")))).isFalse();
        assertThat(DatabaseErrors.isUnavailable(new IllegalStateException("bug"))).isFalse();
    }
}
