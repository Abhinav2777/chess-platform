package com.chessplatform.chess;

/**
 * The outcome of applying a legal move.
 *
 * @param uci         the move in UCI form, e.g. {@code e2e4} or {@code e7e8q}
 * @param san         the move in Standard Algebraic Notation, e.g. {@code Nf3}, {@code exd8=Q+}
 * @param positionAfter the resulting position
 * @param sideToMove  whose turn it is now — after the move, not before
 * @param outcome     whether the game ended, and how
 */
public record MoveResult(String uci,
                         String san,
                         Position positionAfter,
                         Side sideToMove,
                         GameOutcome outcome) {

    /**
     * The same move with a different outcome. Exists for the one ending the rules engine
     * cannot see from a single position — repetition, which needs the game's history.
     */
    public MoveResult withOutcome(GameOutcome newOutcome) {
        return new MoveResult(uci, san, positionAfter, sideToMove, newOutcome);
    }
}
