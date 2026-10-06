package com.chessplatform.common.error;

import java.sql.SQLException;

/**
 * Whether a failed write was a <em>unique</em> violation (SQLState 23505) — the one integrity
 * failure that means "this already exists" (Phase 10.3).
 *
 * <p>Spring reports every integrity failure as {@code DataIntegrityViolationException}: a
 * duplicate key, but also a value too long for its column, a NOT NULL or CHECK violation, a
 * missing foreign key. Catching that type to mean "duplicate" turned a too-long column value into
 * "That move was already submitted. Retry" — a bug disguised as an expected outcome, logged at
 * INFO, and a retry that could never succeed. Callers translate only what this recognises and
 * rethrow the rest, which then surfaces as the error it is.
 */
public final class UniqueViolation {

    static final String SQLSTATE = "23505";

    private UniqueViolation() {
    }

    public static boolean isCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && SQLSTATE.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
