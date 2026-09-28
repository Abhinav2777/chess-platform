package com.chessplatform.game.internal;

import com.chessplatform.game.GameEvents;
import com.chessplatform.game.GameFinished;
import com.chessplatform.game.GameStatus;
import com.chessplatform.messaging.Outbox;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code GAME_FINISHED} to the outbox for every rated ending.
 *
 * <h2>One listener, every ending</h2>
 *
 * <p>Games end in four places: a mating or drawing move, a resignation, a flag caught on
 * the move path, and a flag caught by the sweeper. All of them already publish
 * {@link GameEvents.GameEnded} inside their transaction. Listening to that — rather than
 * adding an outbox call to each — means a fifth ending added later is covered as long as it
 * announces itself, which it must anyway for its players to be told.
 *
 * <h2>Synchronous, inside the transaction — not {@code AFTER_COMMIT}</h2>
 *
 * <p>The opposite choice from {@code GameEventBroadcaster}, and for the same reason turned
 * around. A broadcast must not happen for a change that might roll back, so it waits for
 * the commit. The outbox row must commit <em>with</em> the change, so it is written before.
 * {@code MANDATORY} makes a publisher without a transaction fail loudly instead of writing
 * an event for a game that was never saved.
 */
@Component
public class GameFinishedOutboxWriter {

    private final Outbox outbox;

    public GameFinishedOutboxWriter(Outbox outbox) {
        this.outbox = outbox;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onGameEnded(GameEvents.GameEnded ended) {
        // Aborts are not results (ADR-014): no event, so nothing downstream can rate them.
        if (ended.status() != GameStatus.FINISHED || ended.result() == null) {
            return;
        }
        outbox.append(GameFinished.TYPE, ended.gameId(), new GameFinished(
                GameFinished.SCHEMA_VERSION, ended.gameId(),
                ended.whitePlayerId(), ended.blackPlayerId(),
                ended.result(), ended.termination()));
    }
}
