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

    /** Where the player stands after {@code SEEK} / {@code CANCEL_SEEK}. */
    SEEK_STATUS,

    /**
     * A game has been created for this player. The one matchmaking message a client acts
     * on, whichever way it arrives: pushed when the pairing happens, returned in reply to a
     * seek that finds an existing match, or re-sent after {@code AUTH_OK} to a socket that
     * missed the push (ADR-016).
     */
    MATCH_FOUND,

    /** A command was rejected. Carries the same {@code code} vocabulary as the REST API. */
    ERROR
}
