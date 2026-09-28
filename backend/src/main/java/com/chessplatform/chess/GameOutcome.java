package com.chessplatform.chess;

/**
 * The state of a game after a move.
 *
 * <p>Only outcomes the rules engine can determine from the board. Resignation, timeout
 * and abandonment are decided by the game module, not by the position.
 */
public enum GameOutcome {

    IN_PROGRESS,

    /** The side to move has no legal move and is in check. The mover won. */
    CHECKMATE,

    /** The side to move has no legal move and is not in check. Draw. */
    STALEMATE,

    /** Neither side has sufficient material to force mate. Draw. */
    DRAW_INSUFFICIENT_MATERIAL,

    /** 50 moves by each side with no capture and no pawn move. Draw. */
    DRAW_FIFTY_MOVE,

    /**
     * The same position has occurred three times.
     *
     * <p>Never produced by {@link ChessRules#apply}, which sees one position and no
     * history. The game module decides it after the move, from the persisted log, via
     * {@link ChessRules#isThreefoldRepetition} (ADR-015).
     */
    DRAW_REPETITION;

    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }

    public boolean isDraw() {
        return this == STALEMATE
               || this == DRAW_INSUFFICIENT_MATERIAL
               || this == DRAW_FIFTY_MOVE
               || this == DRAW_REPETITION;
    }
}
