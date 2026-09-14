package com.chessplatform.chess;

/**
 * The piece a pawn promotes to.
 *
 * <p>An explicit enum rather than inferring from the destination square. The client states
 * its intent; the server never guesses. Defaulting to queen would be wrong roughly one
 * game in a thousand — underpromotion to a knight is a real tactic — and a system that is
 * silently wrong occasionally is worse than one that rejects the ambiguity.
 */
public enum Promotion {
    QUEEN("q"),
    ROOK("r"),
    BISHOP("b"),
    KNIGHT("n");

    private final String uciSuffix;

    Promotion(String uciSuffix) {
        this.uciSuffix = uciSuffix;
    }

    public String uciSuffix() {
        return uciSuffix;
    }
}
