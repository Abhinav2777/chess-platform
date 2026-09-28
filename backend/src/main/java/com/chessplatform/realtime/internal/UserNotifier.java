package com.chessplatform.realtime.internal;

import com.chessplatform.realtime.protocol.Envelope;

import java.util.UUID;

/**
 * Delivers a message to every socket a user has open, on whichever instance they are.
 *
 * <p>The person-addressed counterpart of {@code GameEventPublisher}, which is
 * game-addressed. Needed for {@code MATCH_FOUND}: at that moment neither player is
 * subscribed to anything, and the matchmaker that paired them may be on a third instance.
 *
 * <p>Same contract as the game publisher: safe from any thread, never throws. A lost
 * delivery is repaired by the pull path — the match stays readable for {@code match-ttl}
 * and is re-sent after the player's next {@code AUTH_OK} (ADR-016).
 */
public interface UserNotifier {

    void notify(UUID userId, Envelope message);
}
