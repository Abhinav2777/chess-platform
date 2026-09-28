package com.chessplatform.realtime.internal;

import com.chessplatform.realtime.protocol.Envelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** In-JVM delivery. Correct for exactly one instance, like {@code LocalGameEventPublisher}. */
@Component
@ConditionalOnProperty(name = "chess.realtime.fanout", havingValue = "local", matchIfMissing = true)
public class LocalUserNotifier implements UserNotifier {

    private final GameSessionRegistry registry;
    private final WebSocketSender sender;

    public LocalUserNotifier(GameSessionRegistry registry, WebSocketSender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    @Override
    public void notify(UUID userId, Envelope message) {
        registry.forEachSessionOf(userId, session -> sender.send(session, message));
    }
}
