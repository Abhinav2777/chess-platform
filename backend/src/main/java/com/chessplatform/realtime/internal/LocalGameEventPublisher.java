package com.chessplatform.realtime.internal;

import com.chessplatform.realtime.GameEventPublisher;
import com.chessplatform.realtime.protocol.Envelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fans out to sockets on this instance only.
 *
 * <h2>This is the implementation ADR-003 rejects</h2>
 *
 * <p>It is exactly what Spring's simple STOMP broker provides, and it is correct for
 * precisely one deployment: a single instance. Run two, put them behind a load balancer,
 * and the two players in a game will frequently land on different ones — at which point a
 * move committed here is never delivered there, and the game silently desynchronises for
 * one player.
 *
 * <p>It exists so that Milestone 2.1 works end to end and is testable, and so that the
 * Valkey implementation in 2.2 is a demonstrable improvement rather than an asserted one.
 *
 * <p><strong>Do not ship this to a multi-instance deployment.</strong>
 *
 * <h2>Selected by property, not by {@code @ConditionalOnMissingBean}</h2>
 *
 * <p>The first version used {@code @ConditionalOnMissingBean} on this {@code @Component}
 * and produced no bean at all. That annotation is only reliable inside auto-configuration
 * classes: for component-scanned beans the condition is evaluated during scanning, in an
 * order that is undefined relative to other user beans, so it can run before the registry
 * is in the state you assumed. Spring Boot's own documentation restricts it to
 * auto-configuration for exactly this reason.
 *
 * <p>{@code @ConditionalOnProperty} has no such ordering subtlety — and it is the better
 * design regardless. Which fanout a deployment uses is a decision an operator makes
 * deliberately, visible in configuration, not an emergent property of what happens to be
 * on the classpath. A single-instance deployment that silently picked the wrong one would
 * be a correctness bug with no configuration to point at.
 */
@Component
@ConditionalOnProperty(name = "chess.realtime.fanout", havingValue = "local", matchIfMissing = true)
public class LocalGameEventPublisher implements GameEventPublisher {

    private final WebSocketSender sender;
    private final GameSessionRegistry registry;

    public LocalGameEventPublisher(WebSocketSender sender, GameSessionRegistry registry) {
        this.sender = sender;
        this.registry = registry;
    }

    @Override
    public void publish(UUID gameId, Envelope event) {
        registry.forEachWatcher(gameId, session -> sender.send(session, event));
    }
}
