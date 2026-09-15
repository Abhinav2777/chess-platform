package com.chessplatform.realtime.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Who is currently connected to a game.
 *
 * <h2>A set of connections, not a boolean</h2>
 *
 * <p>The first version stored a single flag per (game, user) and wrote it from per-socket
 * events. That is wrong, and the bug is one every real client hits: during a reconnect the
 * old socket and the new one overlap, and if the old one's close is processed last it
 * marks a connected player offline. React's StrictMode double-mount exposed it on every
 * page load, but the same sequence happens whenever a network blip causes a reconnect.
 *
 * <p>Presence is a fact about a <em>user</em>, derived from how many connections they have.
 * So the key holds a set of session ids: a user is online while the set is non-empty, and
 * only the transition to or from empty is worth announcing. That makes an overlapping
 * reconnect a no-op rather than a spurious offline.
 *
 * <h2>Why a Redis set rather than a local counter</h2>
 *
 * <p>A per-instance count would fix the reconnect case and reintroduce the same bug across
 * instances: a player with sockets on two instances would be announced offline when either
 * one closed. The set is shared, so the count is cluster-wide.
 *
 * <h2>Ephemeral by design</h2>
 *
 * <p>Presence is the clearest example of what Valkey is <em>for</em> here (ADR-004):
 * short-lived, high-churn, worthless once stale. Nothing depends on it for correctness — a
 * player shown offline still has a running clock and a valid position. The TTL covers the
 * case explicit cleanup cannot: an instance dying without running any of it.
 *
 * <p>Every method swallows connection errors and reports "no change". If Valkey is
 * unavailable, presence is simply unknown — games continue, moves commit, clocks run. A
 * cache outage that stopped people playing chess would be a far worse bug than a missing
 * "opponent is offline" badge.
 */
@Component
public class PresenceTracker {

    private static final Logger log = LoggerFactory.getLogger(PresenceTracker.class);
    private static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate valkey;

    public PresenceTracker(StringRedisTemplate valkey) {
        this.valkey = valkey;
    }

    /**
     * Records a connection.
     *
     * @return true only if the user was previously offline — i.e. this is worth announcing.
     *         A second socket for the same user returns false, which is what makes an
     *         overlapping reconnect silent.
     */
    public boolean connected(UUID gameId, UUID userId, String sessionId) {
        try {
            String key = key(gameId, userId);
            valkey.opsForSet().add(key, sessionId);
            // Refreshed on every connection and heartbeat, so a live session never expires.
            valkey.expire(key, TTL);
            return size(key) == 1;
        } catch (RuntimeException unavailable) {
            log.debug("Presence unavailable: {}", unavailable.toString());
            return false;
        }
    }

    /**
     * Records a disconnection.
     *
     * @return true only if that was the user's last connection.
     */
    public boolean disconnected(UUID gameId, UUID userId, String sessionId) {
        try {
            String key = key(gameId, userId);
            valkey.opsForSet().remove(key, sessionId);
            return size(key) == 0;
        } catch (RuntimeException unavailable) {
            log.debug("Presence unavailable: {}", unavailable.toString());
            return false;
        }
    }

    /** Called on PING so a connected but quiet player does not expire mid-game. */
    public void refresh(UUID gameId, UUID userId, String sessionId) {
        connected(gameId, userId, sessionId);
    }

    /**
     * @return true if known to be connected. **Unknown reads as offline** — if Valkey is
     *         down every player appears offline rather than every player appearing online.
     *         Failing toward "we don't know" is less misleading than asserting a
     *         connection that may not exist.
     */
    public boolean isOnline(UUID gameId, UUID userId) {
        try {
            return size(key(gameId, userId)) > 0;
        } catch (RuntimeException unavailable) {
            log.debug("Presence unavailable: {}", unavailable.toString());
            return false;
        }
    }

    private long size(String key) {
        Long size = valkey.opsForSet().size(key);
        return size == null ? 0 : size;
    }

    private static String key(UUID gameId, UUID userId) {
        return "presence:" + gameId + ":" + userId;
    }
}
