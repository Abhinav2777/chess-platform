package com.chessplatform.unit.common;

import com.chessplatform.common.error.DatabaseUnavailable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which failures are "the database is unreachable" (503, retry) and which stay bugs (500). Decided
 * from JDBC's signals anywhere in the cause chain, whatever Spring or Hibernate wrapped them in.
 */
@DisplayName("Database unavailable")
class DatabaseUnavailableTest {

    @Test
    @DisplayName("the pool timing out, wrapped as a transaction that could not open (the drill's main case)")
    void poolTimeout() {
        assertThat(DatabaseUnavailable.isCause(new CannotCreateTransactionException("Could not open JPA EntityManager",
                new RuntimeException(new SQLTransientConnectionException("Connection is not available"))))).isTrue();
    }

    @Test
    @DisplayName("SQLState class 08 — connection refused, broken or closed")
    void connectionException() {
        assertThat(DatabaseUnavailable.isCause(new RuntimeException(new SQLException("I/O error", "08006")))).isTrue();
        assertThat(DatabaseUnavailable.isCause(new SQLException("refused", "08001"))).isTrue();
    }

    @Test
    @DisplayName("no answer within the socket timeout")
    void socketTimeout() {
        assertThat(DatabaseUnavailable.isCause(new RuntimeException(new SQLException("I/O error",
                new SocketTimeoutException("Read timed out"))))).isTrue();
    }

    @Test
    @DisplayName("a rollback that failed on the connection the pool just evicted (the original is gone from the chain)")
    void rollbackOnEvictedConnection() {
        assertThat(DatabaseUnavailable.isCause(new RuntimeException("Unable to rollback against JDBC Connection",
                new SQLException("Connection is closed")))).isTrue();
    }

    @Test
    @DisplayName("a constraint violation, a syntax error or a plain bug stays a 500")
    void notAnOutage() {
        assertThat(DatabaseUnavailable.isCause(new RuntimeException(new SQLException("duplicate key", "23505")))).isFalse();
        assertThat(DatabaseUnavailable.isCause(new SQLException("syntax", "42601"))).isFalse();
        assertThat(DatabaseUnavailable.isCause(new IllegalStateException("bug"))).isFalse();
        assertThat(DatabaseUnavailable.isCause(new SQLException("no state"))).isFalse();
    }
}
