package com.chessplatform.realtime;

import com.chessplatform.realtime.protocol.Envelope;

import java.util.UUID;

/**
 * Delivers an event to every client watching a game, wherever they are connected.
 *
 * <h2>Why this is a port</h2>
 *
 * <p>Milestone 2.1 implements it in-JVM, which works only while there is one instance —
 * exactly the limitation that rules out Spring's simple STOMP broker in ADR-003. Milestone
 * 2.2 replaces the implementation with Valkey Pub/Sub and nothing above this interface
 * changes.
 *
 * <p>Keeping it an interface is not speculative generality: the swap is planned, it is the
 * whole argument of ADR-003, and having the seam makes the difference between the two
 * demonstrable rather than described.
 *
 * <p>Implementations must be safe to call from any thread and must never throw — a
 * delivery failure is a display gap that reconnection repairs (ADR-007), not a reason to
 * fail the move that caused it.
 */
public interface GameEventPublisher {

    void publish(UUID gameId, Envelope event);

    /**
     * Called when this instance gains its first local subscriber for a game.
     *
     * <p>Default no-op, because an in-JVM implementation has nothing upstream to attach
     * to. A cross-instance implementation subscribes to the game's channel here.
     *
     * <p><strong>Per instance, not per socket.</strong> Both players in a game may be on
     * this instance, or fifty spectators later; either way one upstream subscription
     * serves them all. Subscribing per socket would make the number of upstream
     * subscriptions proportional to connections rather than to games in play, which is
     * the difference between a few thousand and a few hundred thousand at scale.
     */
    default void onFirstLocalSubscriber(UUID gameId) {
    }

    /**
     * Called when the last local subscriber for a game leaves.
     *
     * <p>Unsubscribing matters as much as subscribing: without it an instance accumulates
     * a subscription for every game it has ever seen, and keeps receiving traffic for
     * games it has no sockets for. That leak is invisible in testing and obvious after a
     * week of uptime.
     */
    default void onLastLocalSubscriber(UUID gameId) {
    }
}
