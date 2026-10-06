package com.chessplatform.common.error;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

/**
 * Recognises "the database cannot be reached right now" — so it is answered as a retryable 503,
 * not as an internal error (Phase 10.2).
 *
 * <p>Found in the failure drills: with PostgreSQL crashed or frozen, every refused move reached
 * the player as {@code INTERNAL} and was logged at ERROR as unhandled — 127 lines in a 30-second
 * freeze. A client could not tell "try again in a moment" from "something is broken", and an alert
 * on ERROR would page for an outage the system was designed to survive.
 *
 * <p>Decided from JDBC's own signals in the cause chain, not from Spring's or Hibernate's wrapper
 * types — those differ by layer (a transaction that cannot open, a query that times out, a
 * rollback that fails after either), while the cause underneath is always one of these:
 * <ul>
 *   <li>SQLState class {@code 08} — connection exception (refused, broken, closed);</li>
 *   <li>{@link SQLTransientConnectionException} — the pool could not provide a connection in time;</li>
 *   <li>{@link SocketTimeoutException} — no answer within the driver's socket timeout;</li>
 *   <li>HikariCP's "Connection is closed" — see {@link #POOL_CLOSED_CONNECTION}.</li>
 * </ul>
 * A constraint violation or a syntax error is none of these, and stays a 500.
 */
public final class DatabaseUnavailable {

    /** Long enough for a restart or a pool to recover; short enough that a person retries. */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(2);
    public static final String MESSAGE = "The game server cannot reach its database. Try again in a moment.";

    /**
     * The one case matched by message. When a query inside a transaction loses its connection, the
     * pool evicts it, then the transaction manager's rollback fails on it — and Spring throws the
     * <em>rollback's</em> exception, logging the original ("Application exception overridden by
     * rollback exception"). The original, with its SQLState 08006, is no longer in the chain; what
     * is left is HikariCP's {@code new SQLException("Connection is closed")}, which has no SQLState.
     * Found by {@code DatabaseOutageIntegrationTest}, which pins it: a HikariCP upgrade that changes
     * the text fails that test. Cost of the match: a bug that uses a connection after closing it
     * would read as a 503, not a 500.
     */
    static final String POOL_CLOSED_CONNECTION = "Connection is closed";

    private DatabaseUnavailable() {
    }

    public static boolean isCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() == null
                    && POOL_CLOSED_CONNECTION.equals(sql.getMessage())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    public static DomainException.Unavailable exception() {
        return new DomainException.Unavailable(ErrorCode.SERVICE_UNAVAILABLE, MESSAGE, RETRY_AFTER);
    }
}
