package com.chessplatform.realtime.protocol;

/**
 * Message types a client may send.
 *
 * <p>A closed enum, not a free string: an unknown type is rejected at parse time rather
 * than falling through a switch into undefined behaviour.
 */
public enum ClientMessage {

    /**
     * Must be the first frame. The browser WebSocket API cannot set an
     * {@code Authorization} header on the handshake, and the usual workarounds — a token
     * in the query string, or a cookie — either leak the credential into access logs or
     * reintroduce CSRF. See ADR-009.
     */
    AUTH,

    /** Subscribe to a game's events. Answered with a {@code GAME_SNAPSHOT}. */
    SUBSCRIBE,

    UNSUBSCRIBE,

    /** Play a move. Same validation path as the REST endpoint — one pipeline, two transports. */
    MOVE,

    RESIGN,

    /**
     * Application-level heartbeat.
     *
     * <p>Not redundant with TCP keepalive or WebSocket ping frames. An idle connection
     * through an AWS ALB is closed after 60 seconds regardless of TCP state, and a
     * half-open connection — the peer gone, no FIN delivered — looks perfectly healthy to
     * the socket layer. Only traffic the application itself generates proves the path is
     * alive end to end.
     */
    PING
}
