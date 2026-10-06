package com.chessplatform.unit.common;

import com.chessplatform.common.error.UniqueViolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Only a unique violation means "already exists"; every other integrity failure is a bug (10.3). */
@DisplayName("Unique violation")
class UniqueViolationTest {

    @Test
    @DisplayName("a duplicate key (23505), however deeply wrapped")
    void duplicate() {
        assertThat(UniqueViolation.isCause(new DataIntegrityViolationException("x",
                new RuntimeException(new SQLException("duplicate key", "23505"))))).isTrue();
    }

    @Test
    @DisplayName("a value too long (22001), NOT NULL (23502), CHECK (23514), foreign key (23503): not duplicates")
    void otherIntegrityFailures() {
        for (String state : new String[] {"22001", "23502", "23514", "23503"}) {
            assertThat(UniqueViolation.isCause(new DataIntegrityViolationException("x",
                    new SQLException("failed", state)))).as(state).isFalse();
        }
    }
}
