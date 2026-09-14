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
     * <p><strong>Not currently detected.</strong> Positions are reconstructed from FEN,
     * which carries no history, so the engine cannot know a position has occurred before.
     * The value exists because the outcome is real and the schema must accommodate it.
     *
     * <p>The fix is cheap when we want it, and does not require statefulness: every
     * move's {@code fen_after} is already persisted, so repetition is a count query over
     * {@code moves} for that game, keyed on the position fields of the FEN (see
     * {@link Position#repetitionKey()}). Recorded as technical debt rather than
     * half-implemented.
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
