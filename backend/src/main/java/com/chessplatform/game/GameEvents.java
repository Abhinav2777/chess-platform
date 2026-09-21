package com.chessplatform.game;

import com.chessplatform.chess.Side;

import java.util.UUID;

/**
 * Domain events published by the game module.
 *
 * <h2>Published inside the transaction, delivered after commit</h2>
 *
 * <p>{@code GameService} calls {@code publishEvent} while the transaction is still open;
 * listeners use {@code @TransactionalEventListener(AFTER_COMMIT)}, so nothing is delivered
 * until the write is durable.
 *
 * <p>That ordering is not a detail. Broadcasting a move from inside the transaction means
 * a rollback leaves every connected client showing a move that never happened — and
 * because the clients agree with each other, nothing looks wrong until someone reloads.
 * This is the same class of bug as the refresh-token revocation being rolled back
 * (ADR-013): a side effect whose lifetime is not tied to the commit it describes.
 *
 * <h2>Why events rather than a direct call</h2>
 *
 * <p>The game module must not know that a realtime layer exists. The declared module
 * order is {@code game <- {realtime, matchmaking, rating}}, and a direct call would invert
 * it and create a cycle ArchUnit would reject. Phase 5's rating consumer will listen to
 * {@link GameEnded} without the game module changing at all — which is the concrete
 * payoff of the boundary.
 */
public final class GameEvents {

    private GameEvents() {
    }

    /**
     * A move was committed.
     *
     * @param legalMoves every legal move in the resulting position, in UCI form.
     *
     *                   <p>Carried with the move rather than fetched afterwards because
     *                   the client has no rules engine and cannot derive them. Without
     *                   this a client would be left holding a board it cannot play on
     *                   until its next snapshot — which is exactly the bug this field was
     *                   added to fix.
     *
     *                   <p>Both clients receive the same list even though only the side to
     *                   move can use it. That keeps every subscriber's frame byte-identical
     *                   and costs a few hundred bytes per move.
     */
    public record MovePlayed(UUID gameId,
                             UUID playerId,
                             int ply,
                             String uci,
                             String san,
                             String fenAfter,
                             Side sideToMove,
                             boolean gameOver,
                             java.util.List<String> legalMoves,
                             long whiteMsLeft,
                             long blackMsLeft) {
    }

    /**
     * A game reached a terminal state, by any route — mate, draw, or resignation.
     *
     * <p>Separate from {@link MovePlayed} because a game can end without a move
     * (resignation, and later timeout and abandonment), and because the rating module
     * cares about this and nothing else.
     */
    public record GameEnded(UUID gameId,
                            UUID whitePlayerId,
                            UUID blackPlayerId,
                            GameResult result,
                            Termination termination) {
    }
}
