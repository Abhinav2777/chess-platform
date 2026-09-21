package com.chessplatform.realtime.internal;

import com.chessplatform.chess.ChessRules;
import com.chessplatform.chess.MoveIntent;
import com.chessplatform.chess.Position;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.game.GameFacade;
import com.chessplatform.game.internal.ServerClock;
import com.chessplatform.game.GameView;
import com.chessplatform.game.domain.MoveRecord;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.SubmitMoveCommand;
import com.chessplatform.identity.internal.JwtService;
import com.chessplatform.realtime.protocol.ClientMessage;
import com.chessplatform.realtime.GameEventPublisher;
import com.chessplatform.realtime.protocol.Envelope;
import com.chessplatform.realtime.protocol.Payloads;
import com.chessplatform.realtime.protocol.PresencePayloads;
import com.chessplatform.realtime.protocol.ServerMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The WebSocket endpoint.
 *
 * <h2>Connection lifecycle</h2>
 *
 * <pre>
 *   open (unauthenticated, timer armed)
 *     -> AUTH {token}        -> AUTH_OK       (timer cancelled)
 *     -> SUBSCRIBE {gameId}  -> GAME_SNAPSHOT
 *     -> MOVE / RESIGN / PING
 *   close -> deregister
 * </pre>
 *
 * <h2>Authentication in the first frame</h2>
 *
 * <p>The browser WebSocket API cannot set an {@code Authorization} header on the
 * handshake. The common alternatives are a token in the query string — which lands in ALB
 * access logs, proxy logs and browser history — or a cookie, which reintroduces CSRF on
 * the socket. First-frame auth leaks neither, at the cost of a bounded pre-auth window
 * that {@link RealtimeProperties} closes with a timer and a connection cap (ADR-009).
 *
 * <h2>No game state here</h2>
 *
 * <p>This class holds sockets, not positions. Every command is answered from the database
 * through {@code GameService}, which is why a player can disconnect and reconnect to a
 * different instance mid-game and see a correct board — the state was never in any pod's
 * memory to lose.
 *
 * <h2>Threading</h2>
 *
 * <p>Container threads deliver frames; event fanout writes from others. A
 * {@code WebSocketSession} is not safe for concurrent writes, so sessions are wrapped in
 * {@code ConcurrentWebSocketSessionDecorator} at registration — see
 * {@code WebSocketConfig}.
 */
@Component
public class ChessWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChessWebSocketHandler.class);

    private final GameSessionRegistry registry;
    private final WebSocketSender sender;
    private final JwtService jwt;
    private final GameService gameService;
    private final GameFacade gameFacade;
    private final MoveRepository moves;
    private final ChessRules rules;
    private final RealtimeProperties properties;
    private final GameEventPublisher publisher;
    private final PresenceTracker presence;
    private final ServerClock serverClock;

    /**
     * One thread for auth timeouts. These fire rarely and do almost nothing, so a pool
     * would be waste — but the task must not run on a container thread, or a slow
     * timeout blocks request handling.
     */
    private final ScheduledExecutorService authTimeouts =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ws-auth-timeout");
                thread.setDaemon(true);
                return thread;
            });

    public ChessWebSocketHandler(GameSessionRegistry registry, WebSocketSender sender,
                                 JwtService jwt, GameService gameService, GameFacade gameFacade,
                                 MoveRepository moves, ChessRules rules,
                                 RealtimeProperties properties, GameEventPublisher publisher,
                                 PresenceTracker presence, ServerClock serverClock,
                                 MeterRegistry metrics) {
        this.registry = registry;
        this.sender = sender;
        this.jwt = jwt;
        this.gameService = gameService;
        this.gameFacade = gameFacade;
        this.moves = moves;
        this.rules = rules;
        this.properties = properties;
        this.publisher = publisher;
        this.presence = presence;
        this.serverClock = serverClock;

        Gauge.builder("chess.ws.connections.active", registry,
                        GameSessionRegistry::localConnectionCount)
                .description("Open WebSocket connections on this instance")
                .register(metrics);
        Gauge.builder("chess.ws.games.watched", registry,
                        GameSessionRegistry::watchedGameCount)
                .description("Games with at least one local subscriber")
                .register(metrics);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (registry.unauthenticatedCount() >= properties.maxUnauthenticated()) {
            // Refusing here rather than after auth is the point: the attack is opening
            // sockets and never authenticating.
            log.warn("Rejecting connection: {} unauthenticated sockets already open",
                    registry.unauthenticatedCount());
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }

        registry.register(session);

        authTimeouts.schedule(() -> {
            GameSessionRegistry.SessionState state = registry.stateOf(session);
            if (state != null && !state.isAuthenticated() && session.isOpen()) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION
                        .withReason("Authentication timed out"));
            }
        }, properties.authTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Envelope envelope;
        try {
            envelope = sender.parseEnvelope(message.getPayload());
        } catch (Exception malformed) {
            // Untrusted input. Jackson 3 throws unchecked, so catching Exception is
            // deliberate rather than lazy — every failure mode means "bad frame".
            sender.sendError(session, "MALFORMED", "Could not parse that message.");
            return;
        }

        GameSessionRegistry.SessionState state = registry.stateOf(session);
        if (state == null) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }

        ClientMessage type;
        try {
            type = ClientMessage.valueOf(envelope.type());
        } catch (IllegalArgumentException unknown) {
            sender.sendError(session, "UNKNOWN_TYPE", "Unsupported message type.");
            return;
        }

        // AUTH is the only command permitted before authentication. Checked centrally so
        // a new command cannot accidentally be reachable by an anonymous socket.
        if (type != ClientMessage.AUTH && !state.isAuthenticated()) {
            sender.sendError(session, "UNAUTHENTICATED", "Send AUTH first.");
            return;
        }

        try {
            MDC.put("wsSessionId", session.getId());
            if (state.userId() != null) {
                MDC.put("userId", state.userId().toString());
            }
            dispatch(session, state, type, envelope);
        } catch (DomainException rejected) {
            // Expected outcomes travel over the socket with the same code vocabulary the
            // REST API uses, so a client has one error model rather than two.
            sender.sendError(session, rejected.code().name(), rejected.getMessage());
        } catch (Exception unexpected) {
            log.error("Unhandled error on session {}", session.getId(), unexpected);
            sender.sendError(session, "INTERNAL", "Something went wrong.");
        } finally {
            MDC.clear();
        }
    }

    private void dispatch(WebSocketSession session, GameSessionRegistry.SessionState state,
                          ClientMessage type, Envelope envelope) {
        switch (type) {
            case AUTH -> authenticate(session, state, envelope);
            case SUBSCRIBE -> subscribe(session, state, envelope);
            case UNSUBSCRIBE -> unsubscribe(session, state, envelope);
            case MOVE -> move(session, state, envelope);
            case RESIGN -> resign(session, state, envelope);
            case PING -> {
                // A heartbeat also refreshes presence, so a player who is connected but
                // quiet does not expire and appear to have vanished mid-game.
                if (state.subscribedGame() != null) {
                    presence.refresh(state.subscribedGame(), state.userId(), session.getId());
                }
                sender.send(session, Envelope.of(ServerMessage.PONG));
            }
        }
    }

    private void authenticate(WebSocketSession session, GameSessionRegistry.SessionState state,
                              Envelope envelope) {
        Payloads.Auth auth = sender.parsePayload(envelope.payload(), Payloads.Auth.class);
        Optional<JwtService.VerifiedToken> verified = jwt.verify(auth.token());

        if (verified.isEmpty()) {
            sender.send(session, Envelope.of(ServerMessage.AUTH_FAILED,
                    new Payloads.Failure("INVALID_CREDENTIALS", "Token rejected.")));
            // Close rather than allow retries: an unauthenticated socket that can keep
            // guessing is an offline brute-force channel with no rate limiting on it.
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("Authentication failed"));
            return;
        }

        JwtService.VerifiedToken token = verified.get();
        state.authenticate(token.userId(), token.username());
        sender.send(session, Envelope.of(ServerMessage.AUTH_OK,
                new Payloads.AuthOk(token.userId(), token.username())));
    }

    private void subscribe(WebSocketSession session, GameSessionRegistry.SessionState state,
                           Envelope envelope) {
        Payloads.Subscribe request = sender.parsePayload(envelope.payload(), Payloads.Subscribe.class);
        GameView game = gameFacade.findById(request.gameId())
                .orElseThrow(() -> new DomainException.NotFound(
                        com.chessplatform.common.error.ErrorCode.GAME_NOT_FOUND, "No such game."));

        // Authorisation is re-checked on every subscribe. A valid token proves who you
        // are, not that you may watch this particular game.
        boolean isPlayer = state.userId().equals(game.whitePlayerId())
                           || state.userId().equals(game.blackPlayerId());
        if (!isPlayer) {
            throw new DomainException.Rejected(
                    com.chessplatform.common.error.ErrorCode.NOT_A_PLAYER,
                    "You are not a player in this game.");
        }

        boolean firstLocalWatcher = registry.subscribe(request.gameId(), session);
        if (firstLocalWatcher) {
            // Subscribe upstream once per instance per game, not once per socket — see
            // GameEventPublisher.onFirstLocalSubscriber.
            publisher.onFirstLocalSubscriber(request.gameId());
        }

        // Announced only on the transition from offline — a second socket for the same
        // user is silent. Without that, an overlapping reconnect (old socket still open
        // while the new one subscribes) produces a spurious offline, and the old socket's
        // close can land last and leave a connected player marked away.
        boolean nowOnline = presence.connected(
                request.gameId(), state.userId(), session.getId());

        // Snapshot first, presence announcement second. The subscriber must have a board
        // before it is told anything about it, and the announcement goes through the
        // fanout so an opponent on another instance hears it too.
        sender.send(session, Envelope.of(ServerMessage.GAME_SNAPSHOT,
                snapshotOf(game, state.userId())));

        if (nowOnline) {
            announcePresence(request.gameId(), state.userId(), true);
        }
    }

    private void announcePresence(UUID gameId, UUID userId, boolean online) {
        publisher.publish(gameId, Envelope.of(ServerMessage.PLAYER_PRESENCE,
                new PresencePayloads(gameId, userId, online)));
    }

    private void unsubscribe(WebSocketSession session, GameSessionRegistry.SessionState state,
                             Envelope envelope) {
        Payloads.Subscribe request = sender.parsePayload(envelope.payload(), Payloads.Subscribe.class);
        releaseGame(request.gameId(), state.userId(), session);
    }

    /**
     * Detaches a socket from a game and, if it was the last one here, tears down the
     * upstream subscription. Shared by explicit UNSUBSCRIBE and by socket close, because
     * forgetting either path leaks a subscription per game this instance has ever seen.
     */
    private void releaseGame(UUID gameId, UUID userId, WebSocketSession session) {
        boolean lastLocalWatcher = registry.unsubscribe(gameId, session);
        if (userId != null) {
            // Only the user's LAST connection makes them offline. A reconnect that has
            // already established its new socket leaves the set non-empty, so closing the
            // old one announces nothing.
            boolean nowOffline = presence.disconnected(gameId, userId, session.getId());
            if (nowOffline) {
                // Announced BEFORE unsubscribing upstream, or this instance publishes to a
                // channel it has just stopped listening to and the opponent — who may be on
                // another instance — never hears it.
                announcePresence(gameId, userId, false);
            }
        }
        if (lastLocalWatcher) {
            publisher.onLastLocalSubscriber(gameId);
        }
    }

    private void move(WebSocketSession session, GameSessionRegistry.SessionState state,
                      Envelope envelope) {
        Payloads.Move request = sender.parsePayload(envelope.payload(), Payloads.Move.class);
        MDC.put("gameId", request.gameId().toString());

        // Exactly the same pipeline the REST endpoint uses. The transport does not get its
        // own validation, its own turn check, or its own idempotency handling — one set of
        // rules, or they drift.
        gameService.submitMove(request.gameId(), state.userId(),
                new SubmitMoveCommand(request.clientMoveId(), request.expectedPly(),
                        new MoveIntent(request.from(), request.to(), request.promotion())));

        // No response is sent here. The mover learns the outcome through the same
        // MOVE_MADE broadcast as the opponent, published after commit — so both clients
        // observe identical state rather than the mover trusting a private reply that the
        // opponent may never have received.
    }

    private void resign(WebSocketSession session, GameSessionRegistry.SessionState state,
                        Envelope envelope) {
        Payloads.Resign request = sender.parsePayload(envelope.payload(), Payloads.Resign.class);
        gameService.resign(request.gameId(), state.userId());
    }

    private Payloads.GameSnapshot snapshotOf(GameView game, UUID viewerId) {
        java.time.Instant now = serverClock.now();
        Side yourSide = viewerId.equals(game.whitePlayerId()) ? Side.WHITE
                : viewerId.equals(game.blackPlayerId()) ? Side.BLACK : null;

        List<MoveRecord> played = moves.findByGameIdOrderByPlyAsc(game.id());
        String lastMove = played.isEmpty() ? null : played.getLast().uci();

        List<String> legal = game.status().isTerminal()
                ? List.of()
                : rules.legalMoves(new Position(game.fen()));

        UUID opponentId = viewerId.equals(game.whitePlayerId())
                ? game.blackPlayerId() : game.whitePlayerId();

        return new Payloads.GameSnapshot(game.id(), game.fen(), game.ply(), game.sideToMove(),
                game.whitePlayerId(), game.blackPlayerId(), yourSide,
                presence.isOnline(game.id(), opponentId),
                game.status().name(),
                game.result() == null ? null : game.result().name(),
                game.termination() == null ? null : game.termination().name(),
                legal, lastMove,
                // Remaining time AS OF NOW, not the stored value: a subscriber joining
                // three minutes into someone's think must not be shown the clock as it
                // stood before they started thinking. Derived from the database clock, so
                // every instance answers identically (ADR-006).
                game.remainingMs(Side.WHITE, now), game.remainingMs(Side.BLACK, now),
                game.incrementMs());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanUp(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        log.debug("Transport error on session {}: {}", session.getId(), error.toString());
        cleanUp(session);
    }

    /**
     * State is read before removal, deliberately: the registry forgets which game a socket
     * was watching as part of removing it, so reading afterwards would leave presence set
     * and the upstream subscription attached — a leak per disconnect, and an opponent
     * permanently shown as online.
     */
    private void cleanUp(WebSocketSession session) {
        GameSessionRegistry.SessionState state = registry.stateOf(session);
        if (state != null && state.subscribedGame() != null) {
            releaseGame(state.subscribedGame(), state.userId(), session);
        }
        registry.remove(session);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // The peer is already gone. Nothing useful to do or report.
        }
    }
}
