package com.chessplatform.common.ratelimit;

/**
 * The limits this system enforces. Each has its own bucket per subject, configured under
 * {@code chess.ratelimit} (ARCHITECTURE.md §11).
 */
public enum RateLimit {

    /** Login attempts per client IP: one source spraying many accounts. */
    LOGIN_IP("login-ip"),
    /**
     * Login attempts per username: many sources guessing one account. Keyed separately from
     * the IP limit because a botnet defeats a per-IP limit and a single attacker defeats a
     * per-account one; each covers the other's blind spot.
     */
    LOGIN_USER("login-user"),
    /** Account creation per client IP. */
    REGISTER_IP("register-ip"),
    /** Moves per user — one bucket shared by REST and WebSocket, so switching transport buys nothing. */
    MOVE("move"),
    /** Seeks per user, including the client's 15 s re-seek heartbeat. */
    SEEK("seek");

    private final String key;

    RateLimit(String key) {
        this.key = key;
    }

    /** The name in configuration and in the Valkey key. */
    public String key() {
        return key;
    }
}
