package com.chessplatform.realtime.protocol;

import java.time.Instant;

/**
 * The wire format, both directions.
 *
 * <pre>
 * { "v": 1, "type": "MOVE_MADE", "ts": "2026-09-14T10:12:03.221Z", "payload": { ... } }
 * </pre>
 *
 * <h2>Versioned from the first frame</h2>
 *
 * <p>{@code v} costs two bytes now and is impossible to add later: once clients are
 * deployed, a message with no version field is indistinguishable from version 1, so any
 * future change has to be backwards-compatible forever or break every old client at once.
 *
 * <h2>Why there is no sequence number</h2>
 *
 * <p>{@code ARCHITECTURE.md} originally specified a per-game {@code seq}. Implementation
 * showed it to be redundant and it was dropped.
 *
 * <p>Ordering within a connection is already guaranteed by TCP, so {@code seq} only helps
 * a client detect that it <em>missed</em> something — and {@code ply} already does that,
 * monotonically and meaningfully: a client at ply 3 receiving ply 5 knows it lost ply 4
 * and can ask for a snapshot. A separate counter would also need to be shared across
 * instances once fanout moves to Valkey, which means a distributed counter on the hot path
 * to duplicate information the payload already carries.
 *
 * @param v       protocol version, currently 1
 * @param type    a {@link ClientMessage} or {@link ServerMessage} name
 * @param ts      server timestamp; advisory only. The clock that decides games takes its
 *                time from PostgreSQL (ADR-006), never from a message field.
 * @param payload type-specific body; null for {@code PING} and {@code PONG}
 */
public record Envelope(int v, String type, Instant ts, Object payload) {

    public static final int VERSION = 1;

    public static Envelope of(ServerMessage type, Object payload) {
        return new Envelope(VERSION, type.name(), Instant.now(), payload);
    }

    public static Envelope of(ServerMessage type) {
        return new Envelope(VERSION, type.name(), Instant.now(), null);
    }
}
