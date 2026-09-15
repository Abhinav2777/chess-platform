package com.chessplatform.realtime.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks which local sockets are watching which games.
 *
 * <h2>Local, and deliberately so</h2>
 *
 * <p>This holds only the sockets attached to <em>this</em> instance. It is not a view of
 * the cluster and must never become one: no game state lives here, only connections, so
 * losing the whole registry when a pod dies costs nothing beyond the sockets that died
 * with it. Clients reconnect and receive a snapshot.
 *
 * <p>That is what makes rolling deployments and reconnection-to-any-instance work. The
 * moment this class starts caching positions or clocks, a pod restart becomes a data-loss
 * event rather than a blip.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Sockets arrive and leave on container threads while events fan out on others, so both
 * maps are concurrent and the per-game set uses {@code newKeySet()}. Iteration during
 * concurrent modification is safe and weakly consistent — a subscriber that arrives
 * mid-broadcast may miss that one event, which reconnection and snapshot repair make
 * harmless.
 */
@Component
public class GameSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(GameSessionRegistry.class);

    private final Map<UUID, Set<WebSocketSession>> watchers = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), new SessionState());
    }

    public SessionState stateOf(WebSocketSession session) {
        return sessions.get(session.getId());
    }

    /**
     * @return true when this is the first local subscriber for the game — the signal that
     *         a cross-instance implementation should subscribe to the game's channel.
     *         Subscribing per-instance rather than per-socket keeps the number of
     *         upstream subscriptions proportional to games in play, not to connections.
     */
    public boolean subscribe(UUID gameId, WebSocketSession session) {
        SessionState state = sessions.get(session.getId());
        if (state != null) {
            state.subscribedGame = gameId;
        }
        boolean[] first = {false};
        watchers.compute(gameId, (id, existing) -> {
            if (existing == null) {
                first[0] = true;
                existing = ConcurrentHashMap.newKeySet();
            }
            existing.add(session);
            return existing;
        });
        return first[0];
    }

    /** @return true when the last local subscriber left — the cue to unsubscribe upstream. */
    public boolean unsubscribe(UUID gameId, WebSocketSession session) {
        boolean[] last = {false};
        watchers.computeIfPresent(gameId, (id, existing) -> {
            existing.remove(session);
            if (existing.isEmpty()) {
                last[0] = true;
                // Returning null removes the key. Without this the map grows by one entry
                // per game played, forever — a leak that is invisible in testing and
                // obvious after a month of uptime.
                return null;
            }
            return existing;
        });
        return last[0];
    }

    public void remove(WebSocketSession session) {
        SessionState state = sessions.remove(session.getId());
        if (state != null && state.subscribedGame != null) {
            unsubscribe(state.subscribedGame, session);
        }
    }

    public void forEachWatcher(UUID gameId, Consumer<WebSocketSession> action) {
        Set<WebSocketSession> local = watchers.get(gameId);
        if (local == null) {
            return;
        }
        for (WebSocketSession session : local) {
            try {
                action.accept(session);
            } catch (RuntimeException failed) {
                // One broken socket must not stop delivery to the others. The peer will
                // reconnect and receive a snapshot.
                log.debug("Delivery to session {} failed: {}", session.getId(), failed.toString());
            }
        }
    }

    public int localConnectionCount() {
        return sessions.size();
    }

    public int unauthenticatedCount() {
        return (int) sessions.values().stream().filter(s -> s.userId == null).count();
    }

    public int watchedGameCount() {
        return watchers.size();
    }

    /** Mutable per-socket state. Confined to one socket, which is handled by one thread. */
    public static final class SessionState {
        volatile UUID userId;
        volatile String username;
        volatile UUID subscribedGame;

        public UUID userId() {
            return userId;
        }

        public String username() {
            return username;
        }

        public UUID subscribedGame() {
            return subscribedGame;
        }

        public boolean isAuthenticated() {
            return userId != null;
        }

        void authenticate(UUID userId, String username) {
            this.userId = userId;
            this.username = username;
        }
    }
}
