package com.chessplatform.realtime.protocol;

import java.util.UUID;

/**
 * Presence notifications.
 *
 * <p>Advisory only. A player being shown as offline changes nothing about the game — the
 * clock keeps running, because chess does not pause for network problems (ADR-006), and
 * their moves remain valid the moment they return. This exists so the opponent knows why
 * nothing is happening, not to alter any rule.
 */
public record PresencePayloads(UUID gameId, UUID userId, boolean online) {
}
