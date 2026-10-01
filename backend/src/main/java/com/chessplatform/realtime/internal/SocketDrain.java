package com.chessplatform.realtime.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Hands every WebSocket back to the client before this instance stops (Phase 8.1, ADR-024).
 *
 * <h2>Why it is needed</h2>
 *
 * <p>Spring Boot's graceful shutdown waits for in-flight HTTP requests. An upgraded WebSocket is
 * not one: without this, sockets were cut when Tomcat stopped — after the application had begun
 * tearing down — and every client saw 1006, an abnormal close indistinguishable from a crash
 * ({@code GracefulShutdownIntegrationTest}, run against a no-op stub).
 *
 * <h2>What it does, in order</h2>
 *
 * <ol>
 *   <li>Readiness → {@code REFUSING_TRAFFIC}: load balancers that probe it stop sending new
 *       connections here.</li>
 *   <li>Every open socket is closed with <b>1001 GOING_AWAY</b> and a reason saying to reconnect.
 *       The client's reconnect already uses full jitter (0–500 ms first, GameSocket.ts), so the
 *       stampede onto the remaining instances is spread, and its snapshot resync (ADR-007)
 *       restores the game.</li>
 *   <li>From then on, a new connection that still reaches this instance is closed the same way
 *       at once ({@code ChessWebSocketHandler}), and closing does <em>not</em> cancel the
 *       player's seek — a deploy is not the player leaving.</li>
 * </ol>
 *
 * <h2>When</h2>
 *
 * <p>Lifecycle beans stop from the highest phase down. Boot's web-server graceful shutdown runs
 * at {@code DEFAULT_PHASE - 1024} and the server stops at {@code - 2048}; this runs at
 * {@code - 512}, first — while Tomcat, the connection pool and Valkey are all still up, so a
 * move being processed as the drain starts still commits.
 */
@Component
public class SocketDrain implements SmartLifecycle {

    public static final CloseStatus RESTARTING =
            CloseStatus.GOING_AWAY.withReason("Server restarting, please reconnect");

    private static final Logger log = LoggerFactory.getLogger(SocketDrain.class);

    private final GameSessionRegistry registry;
    private final ApplicationEventPublisher events;
    private volatile boolean running;
    private volatile boolean draining;

    public SocketDrain(GameSessionRegistry registry, ApplicationEventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    /** True from the moment shutdown starts: new sockets are refused, seeks are kept. */
    public boolean isDraining() {
        return draining;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        draining = true;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);

        var sockets = registry.openSessions();
        log.info("Draining {} WebSocket connection(s): closing with 1001 GOING_AWAY", sockets.size());
        for (WebSocketSession session : sockets) {
            try {
                session.close(RESTARTING);
            } catch (Exception alreadyGone) {
                // The peer left on its own; nothing to hand back.
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 512;
    }
}
