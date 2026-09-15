package com.chessplatform.realtime.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Realtime tuning.
 *
 * @param authTimeout        how long an unauthenticated socket may stay open. The pre-auth
 *                           window is unavoidable given the browser cannot set handshake
 *                           headers (ADR-009); bounding it is what stops it being a
 *                           socket-exhaustion vector.
 * @param maxUnauthenticated cap on concurrent unauthenticated sockets. Without it an
 *                           attacker opens connections and never authenticates, consuming
 *                           file descriptors for free.
 * @param sendBufferBytes    per-socket outbound buffer before the connection is closed. A
 *                           client that stops reading must not make the server buffer
 *                           without limit — that is how one phone on a train becomes an
 *                           out-of-memory error.
 * @param sendTimeout        how long a single write may block before the session is
 *                           abandoned.
 * @param fanout             {@code local} (in-JVM, single instance only) or {@code valkey}
 *                           (cross-instance pub/sub, Milestone 2.2). Explicit because
 *                           running {@code local} behind a load balancer silently
 *                           desynchronises games: the two players in a game frequently
 *                           land on different instances, and a move committed on one is
 *                           never delivered to the other.
 * @param allowedOrigins     origins permitted to open a socket. Configuration, not code:
 *                           the frontend origin differs between local, staging and
 *                           production, and a hardcoded list means a rebuild to deploy.
 *                           Note that a WebSocket handshake is not subject to the
 *                           same-origin policy the way XHR is, so this check is the only
 *                           thing constraining who may attempt a connection.
 */
@ConfigurationProperties(prefix = "chess.realtime")
public record RealtimeProperties(Duration authTimeout,
                                 int maxUnauthenticated,
                                 int sendBufferBytes,
                                 Duration sendTimeout,
                                 String fanout,
                                 java.util.List<String> allowedOrigins) {

    public RealtimeProperties {
        requirePositive(authTimeout, "chess.realtime.auth-timeout");
        requirePositive(sendTimeout, "chess.realtime.send-timeout");
        if (maxUnauthenticated <= 0) {
            throw new IllegalArgumentException("chess.realtime.max-unauthenticated must be positive");
        }
        if (sendBufferBytes <= 0) {
            throw new IllegalArgumentException("chess.realtime.send-buffer-bytes must be positive");
        }
        if (!"local".equals(fanout) && !"valkey".equals(fanout)) {
            throw new IllegalArgumentException(
                    "chess.realtime.fanout must be 'local' or 'valkey'; got " + fanout);
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("chess.realtime.allowed-origins must not be empty");
        }
    }

    // @Positive has no validator for Duration — it compiles and fails at startup with
    // HV000030. Same reason AuthProperties validates in its constructor.
    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration");
        }
    }
}
