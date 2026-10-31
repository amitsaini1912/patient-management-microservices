package org.pm.billingservice.db;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.io.EOFException;
import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

/**
 * Day 23 (chaos): is this error "the database is unreachable / the connection died" - temporary, so the client should
 * retry with the same Idempotency-Key - rather than a bug or a constraint violation?
 *
 * By CAUSE, not by exception type: when the connection dies mid-transaction, the rollback fails on the closed
 * connection too and Spring reports THAT as a generic JpaSystemException / TransactionSystemException
 * ("Connection is closed"), hiding the original DataAccessResourceFailureException. Found by the Toxiproxy test.
 */
public final class DatabaseErrors {

    private DatabaseErrors() {
    }

    public static boolean isUnavailable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof TransientDataAccessResourceException || t instanceof QueryTimeoutException
                    || t instanceof SQLTransientConnectionException || t instanceof SQLNonTransientConnectionException
                    || t instanceof EOFException || t instanceof SocketException) {
                return true;
            }
            if (t instanceof SQLException sql && (sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("57P"))
                    || "Connection is closed".equals(sql.getMessage()))) {
                // 08 = connection exception; 57P01/02/03 = Postgres shutting down / crashed / starting up (found
                // under load: "terminating connection due to administrator command"); Hikari's closed-proxy message
                return true;
            }
        }
        return false;
    }
}
