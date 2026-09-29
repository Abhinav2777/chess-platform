package com.chessplatform.rating.internal;

/**
 * Elo, as pure arithmetic. No state, no I/O — tested without a database.
 *
 * <p>Elo rather than Glicko-2 (PROJECT_STATE §5): the same engineering — read two ratings,
 * compute, write both atomically, exactly once — with a fraction of the maths.
 */
public final class Elo {

    /**
     * One K for everyone. Real servers vary it (higher for new players, lower for masters);
     * that is a product decision this project does not need to make.
     */
    public static final int K = 32;

    private Elo() {
    }

    /** The rating change for both players. {@code black} is always {@code -white}. */
    public record Change(int white, int black) {
    }

    /**
     * @param whiteScore 1 for a White win, 0.5 for a draw, 0 for a Black win
     */
    public static Change change(int whiteRating, int blackRating, double whiteScore) {
        double expectedWhite = expectedScore(whiteRating, blackRating);
        int white = (int) Math.round(K * (whiteScore - expectedWhite));
        // Black's change is the negation, not computed separately: two independent roundings
        // could create or destroy a point per game, and the pool's total rating would drift.
        return new Change(white, -white);
    }

    /** The probability-like score White is expected to make against Black. */
    static double expectedScore(int rating, int opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }
}
