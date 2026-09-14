package com.chessplatform.game;

import com.chessplatform.chess.Side;

/** Who won. Persisted as its name in {@code games.result}; null while the game is active. */
public enum GameResult {

    WHITE_WIN,
    BLACK_WIN,
    DRAW;

    public static GameResult winFor(Side side) {
        return side == Side.WHITE ? WHITE_WIN : BLACK_WIN;
    }

    /**
     * The score for White, in the conventional 1 / 0.5 / 0 form the Elo formula consumes
     * directly. Here rather than in the rating module so that the mapping from result to
     * score lives with the result itself — the rating module should not have to know how
     * this enum is spelled.
     */
    public double whiteScore() {
        return switch (this) {
            case WHITE_WIN -> 1.0;
            case BLACK_WIN -> 0.0;
            case DRAW -> 0.5;
        };
    }
}
