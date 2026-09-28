package com.chessplatform.game;

import com.chessplatform.chess.Side;

import java.time.Instant;
import java.util.List;

/**
 * Everything needed to render a game from cold, read as one consistent snapshot.
 *
 * <p>The single source for both the REST {@code GET /api/games/{id}} and the WebSocket
 * {@code GAME_SNAPSHOT}, so the two agree by construction. Built by
 * {@link GameFacade#state} inside one {@code REPEATABLE READ} transaction: the game row,
 * the move log and {@code asOf} all describe the same instant. Two separate reads under
 * READ COMMITTED could straddle a move and return a board and a move list that disagree.
 *
 * @param moves      the whole log, in ply order
 * @param legalMoves empty once the game is over
 * @param asOf       the database time the snapshot was taken (ADR-006)
 */
public record GameState(GameView game, List<MoveView> moves, List<String> legalMoves,
                        Instant asOf) {

    public GameState {
        moves = List.copyOf(moves);
        legalMoves = List.copyOf(legalMoves);
    }

    /** Remaining time as of the snapshot, net of the current player's think so far. */
    public long remainingMs(Side side) {
        return game.remainingMs(side, asOf);
    }

    public record MoveView(int ply, String uci, String san, Instant playedAt) {
    }
}
