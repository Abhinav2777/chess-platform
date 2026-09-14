package com.chessplatform.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that Flyway actually ran, as its own named test.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Because it silently did not, for an entire phase, and the symptom pointed somewhere
 * else entirely.
 *
 * <p>Spring Boot 4 moved auto-configuration into per-technology modules. Depending on raw
 * {@code org.flywaydb:flyway-core} rather than {@code spring-boot-starter-flyway} puts
 * Flyway on the classpath with no {@code FlywayAutoConfiguration} behind it. The
 * application then starts cleanly, accepts every {@code spring.flyway.*} property, and
 * migrates nothing. The failure eventually surfaced as Hibernate reporting
 * {@code Schema validation: missing table [users]} — an error that points at the entity
 * mapping, which was fine, and says nothing about the dependency, which was not.
 *
 * <p>Every other integration test depends on migrations having run but asserts it only
 * implicitly, so when this breaks, twelve tests fail for a reason none of them names.
 * This one names it.
 *
 * <p>The general lesson is about <em>silent</em> failure. A missing migration tool is far
 * more dangerous than a broken one: a broken one fails loudly at startup, while a missing
 * one hands you an empty database and a green health check. Anything whose absence is
 * indistinguishable from success deserves an explicit assertion.
 */
@DisplayName("Schema migrations")
class SchemaMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway has applied every migration successfully")
    void migrationsApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(versions)
                .as("flyway_schema_history should contain the baseline; "
                    + "if this is empty, Flyway is not auto-configured — check that the build "
                    + "depends on spring-boot-starter-flyway and not org.flywaydb:flyway-core")
                .contains("1");
    }

    @Test
    @DisplayName("no migration is left in a failed state")
    void noFailedMigrations() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

        assertThat(failed).isZero();
    }

    @Test
    @DisplayName("creates the tables the domain expects")
    void createsExpectedTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("users", "games", "moves");
    }
}
