package com.chessplatform.game;

/** Lifecycle state. Persisted as its name in {@code games.status}. */
public enum GameStatus {

    ACTIVE,

    /** Played to a conclusion. Always has a result and a termination. */
    FINISHED,

    /**
     * Ended before it became a real game — nobody moved, or a player vanished before the
     * first move. Distinct from FINISHED because an aborted game has no result and must
     * not affect ratings. Collapsing the two would mean either rating a game nobody
     * played, or inventing a null result that every consumer has to special-case.
     */
    ABORTED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
