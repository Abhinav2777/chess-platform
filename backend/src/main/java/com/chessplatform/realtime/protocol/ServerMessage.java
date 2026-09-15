package com.chessplatform.realtime.protocol;

/** Message types the server may send. */
public enum ServerMessage {

    AUTH_OK,
    AUTH_FAILED,

    /**
     * Complete game state. Sent on every subscribe, including after a reconnect.
     *
     * <p>The client discards whatever it had and adopts this unconditionally. That is what
     * lets the transport be unreliable: a dropped message causes a temporary display gap,
     * never divergence, so no replay buffer or per-client cursor is needed (ADR-007). A
     * chess position is a FEN string and two integers — roughly 120 bytes — so replaying
     * deltas would be more machinery than simply resending the state.
     */
    GAME_SNAPSHOT,

    MOVE_MADE,
    GAME_FINISHED,

    /**
     * A player's connection state changed.
     *
     * <p>Advisory. The clock does not pause, the game does not pause, and a disconnected
     * player's position is unaffected — this only tells the opponent why the board has
     * gone quiet.
     */
    PLAYER_PRESENCE,

    PONG,

    /** A command was rejected. Carries the same {@code code} vocabulary as the REST API. */
    ERROR
}
