package com.chessplatform.matchmaking.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Matchmaking tuning. Validated at startup: a zero TTL or a negative window would not fail
 * loudly later, it would quietly produce a queue that pairs nobody or everybody.
 *
 * @param baseWindow        rating difference accepted immediately
 * @param windowGrowth      extra rating difference accepted per second waited
 * @param maxWindow         the cap, so a long wait never becomes "anyone at all"
 * @param seekTtl           how long a seek survives without being re-asserted. The client
 *                          re-seeks well inside this; an instance crash is detected within it
 * @param pendingTtl        how long a claimed pair may take to become a game before the
 *                          claim lapses and the players can be queued again
 * @param matchTtl          how long a created match is remembered for a player who has not
 *                          yet seen it (e.g. was reconnecting when it was announced)
 * @param scanLimit         longest waiters considered per pairing call
 * @param neighbours        candidates inspected on each side of a rating
 * @param pairsPerTick      upper bound on pairs created per time control per tick
 */
@ConfigurationProperties(prefix = "chess.matchmaking")
public record MatchmakingProperties(int baseWindow,
                                    double windowGrowth,
                                    int maxWindow,
                                    Duration seekTtl,
                                    Duration pendingTtl,
                                    Duration matchTtl,
                                    int scanLimit,
                                    int neighbours,
                                    int pairsPerTick) {

    public MatchmakingProperties {
        if (baseWindow < 0 || windowGrowth < 0 || maxWindow < baseWindow) {
            throw new IllegalArgumentException(
                    "chess.matchmaking windows must satisfy 0 <= base-window <= max-window "
                    + "and window-growth >= 0");
        }
        requireWholeSeconds(seekTtl, "seek-ttl");
        requireWholeSeconds(pendingTtl, "pending-ttl");
        requireWholeSeconds(matchTtl, "match-ttl");
        if (scanLimit <= 0 || neighbours <= 0 || pairsPerTick <= 0) {
            throw new IllegalArgumentException(
                    "chess.matchmaking scan-limit, neighbours and pairs-per-tick must be positive");
        }
    }

    /** Valkey's EX takes whole seconds; a sub-second TTL would silently round to zero. */
    private static void requireWholeSeconds(Duration value, String name) {
        if (value == null || value.toSeconds() < 1) {
            throw new IllegalArgumentException("chess.matchmaking." + name + " must be at least 1s");
        }
    }
}
