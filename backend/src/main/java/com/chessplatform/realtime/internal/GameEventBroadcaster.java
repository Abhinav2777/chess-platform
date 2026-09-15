package com.chessplatform.realtime.internal;

import com.chessplatform.game.GameEvents;
import com.chessplatform.realtime.GameEventPublisher;
import com.chessplatform.realtime.protocol.Envelope;
import com.chessplatform.realtime.protocol.Payloads;
import com.chessplatform.realtime.protocol.ServerMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed domain events into protocol frames.
 *
 * <h2>{@code AFTER_COMMIT}, and why it is not a detail</h2>
 *
 * <p>A plain {@code @EventListener} runs synchronously inside the publishing transaction.
 * Broadcast a move from there and a rollback leaves every connected client showing a move
 * that never happened — and because the clients agree with one another, nothing looks
 * wrong until someone reloads and the move vanishes. Worse, the rollback path is the
 * optimistic-lock conflict, which is exactly the case this system is built to handle.
 *
 * <p>{@code AFTER_COMMIT} means a frame is only ever sent for a move that is durable.
 *
 * <p>The converse risk is accepted deliberately: the transaction can commit and the
 * broadcast then fail — process death, a network blip, Valkey unavailable. That produces
 * a client with a stale board, which reconnection and {@code GAME_SNAPSHOT} repair
 * (ADR-007). **Committed-but-not-broadcast is recoverable; broadcast-but-not-committed is
 * not.** That asymmetry is the whole argument for this annotation.
 */
@Component
public class GameEventBroadcaster {

    private final GameEventPublisher publisher;
    private final Counter movesBroadcast;
    private final Counter gamesFinished;

    public GameEventBroadcaster(GameEventPublisher publisher, MeterRegistry metrics) {
        this.publisher = publisher;
        this.movesBroadcast = Counter.builder("chess.ws.moves.broadcast")
                .description("Move events fanned out to subscribers")
                .register(metrics);
        this.gamesFinished = Counter.builder("chess.ws.games.finished")
                .description("Game-finished events fanned out")
                .register(metrics);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMovePlayed(GameEvents.MovePlayed event) {
        publisher.publish(event.gameId(), Envelope.of(ServerMessage.MOVE_MADE,
                new Payloads.MoveMade(event.gameId(), event.ply(), event.uci(),
                        event.san(), event.fenAfter(), event.sideToMove(),
                        event.legalMoves())));
        movesBroadcast.increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGameEnded(GameEvents.GameEnded event) {
        publisher.publish(event.gameId(), Envelope.of(ServerMessage.GAME_FINISHED,
                new Payloads.GameFinished(event.gameId(),
                        event.result().name(), event.termination().name())));
        gamesFinished.increment();
    }
}
