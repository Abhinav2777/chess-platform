package com.chessplatform.game;

import com.chessplatform.chess.GameOutcome;

/**
 * How a game ended.
 *
 * <p>Separate from {@link GameResult} because "who won" and "how" are different
 * questions, and both matter. A game history that records only "black won" is a poorer
 * artifact than one that distinguishes checkmate from a resignation from a flagged clock
 * — and analytics, anti-abuse, and the player's own memory all want the second.
 */
public enum Termination {

    CHECKMATE,
    STALEMATE,
    RESIGNATION,
    DRAW_FIFTY_MOVE,
    DRAW_REPETITION,
    DRAW_INSUFFICIENT_MATERIAL,

    /** The side to move ran out of time. Their opponent wins. */
    TIMEOUT,

    /** Both players left without finishing. Set by the abandonment sweeper. */
    ABANDONED;

    public static Termination from(GameOutcome outcome) {
        return switch (outcome) {
            case CHECKMATE -> CHECKMATE;
            case STALEMATE -> STALEMATE;
            case DRAW_FIFTY_MOVE -> DRAW_FIFTY_MOVE;
            case DRAW_REPETITION -> DRAW_REPETITION;
            case DRAW_INSUFFICIENT_MATERIAL -> DRAW_INSUFFICIENT_MATERIAL;
            case IN_PROGRESS -> throw new IllegalArgumentException(
                    "IN_PROGRESS is not a termination");
            // TIMEOUT, RESIGNATION and ABANDONED are decided by the game module, not by
            // the position, so they never arrive through a GameOutcome.
        };
    }
}
