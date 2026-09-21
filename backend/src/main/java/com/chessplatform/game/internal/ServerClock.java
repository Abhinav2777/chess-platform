package com.chessplatform.game.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * The single time authority for anything that decides a game.
 *
 * <h2>Why not {@code Instant.now()}</h2>
 *
 * <p>Two instances on different hosts disagree by tens of milliseconds even under NTP. If
 * move 1 is timestamped by instance A and move 2 by instance B, the elapsed time for move
 * 2 is measured against a different clock than the one that recorded its start — and the
 * error accumulates across a game. It can also go <em>negative</em>, which without the
 * clamp in {@link com.chessplatform.game.ClockCalculator} would credit a player time they
 * never had.
 *
 * <p>Reading the time from PostgreSQL makes one machine the sole authority for every game
 * in the system, at any number of instances. It costs one round trip per move and removes
 * an entire class of bug that is close to impossible to reproduce.
 *
 * <p>{@code ClockConfig}'s {@code Clock} bean is still used for {@code created_at}, token
 * expiry and metrics — anywhere millisecond skew between instances is irrelevant. The
 * distinction is deliberate: <strong>this one is for values that decide outcomes.</strong>
 *
 * <h2>{@code now()} rather than {@code clock_timestamp()}</h2>
 *
 * <p>PostgreSQL's {@code now()} is the <em>transaction</em> start time, so every call
 * inside one transaction returns the same instant. That is the property we want: the
 * player is charged from their previous move until the server began processing this one,
 * and not for our own processing time. {@code clock_timestamp()} would advance mid-
 * transaction and bill the player for our database writes.
 */
@Component
public class ServerClock {

    private final JdbcTemplate jdbc;

    public ServerClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        // Explicit RowMapper rather than queryForObject(sql, Instant.class): the driver
        // maps timestamptz to OffsetDateTime directly, and relying on a framework
        // conversion for the value that decides games is not worth the ambiguity.
        Instant now = jdbc.queryForObject("SELECT now()",
                (rs, rowNumber) -> rs.getObject(1, OffsetDateTime.class).toInstant());
        if (now == null) {
            throw new IllegalStateException("SELECT now() returned nothing");
        }
        return now;
    }
}
