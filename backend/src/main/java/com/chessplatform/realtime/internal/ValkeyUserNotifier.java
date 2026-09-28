package com.chessplatform.realtime.internal;

import com.chessplatform.common.resilience.ValkeyGuard;
import com.chessplatform.realtime.protocol.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Cross-instance delivery to a user, over Valkey Pub/Sub.
 *
 * <h2>Every instance hears every user message — deliberately, for now</h2>
 *
 * <p>Messages go to {@code user:{id}}; every instance holds one pattern subscription to
 * {@code user:*} and delivers to whichever of its sockets belong to that user. That is a
 * broadcast, which {@code ValkeyGameEventPublisher} rejects for moves. The difference is
 * volume: a move stream is continuous, while a user message today is one
 * {@code MATCH_FOUND} per player per game started — orders of magnitude rarer. At that
 * rate one static subscription is cheaper than subscribing and unsubscribing per
 * connected user.
 *
 * <p>The channel name already carries the user, so moving to per-user subscriptions
 * (on a user's first local socket, like games) changes only which topics are subscribed —
 * not the publish side, and not the message format. The trigger would be instance count ×
 * match rate making the broadcast visible in Valkey's network or CPU.
 */
@Component
@ConditionalOnProperty(name = "chess.realtime.fanout", havingValue = "valkey")
public final class ValkeyUserNotifier implements UserNotifier, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ValkeyUserNotifier.class);
    private static final String CHANNEL_PREFIX = "user:";

    private final StringRedisTemplate valkey;
    private final ValkeyGuard guard;
    private final GameSessionRegistry registry;
    private final WebSocketSender sender;

    public ValkeyUserNotifier(StringRedisTemplate valkey, RedisMessageListenerContainer container,
                              GameSessionRegistry registry, WebSocketSender sender,
                              ValkeyGuard guard) {
        this.valkey = valkey;
        this.guard = guard;
        this.registry = registry;
        this.sender = sender;
        container.addMessageListener(this, new PatternTopic(CHANNEL_PREFIX + "*"));
    }

    @Override
    public void notify(UUID userId, Envelope message) {
        // Never rethrow: the game already exists. The player finds it on reconnect (pull
        // path) or in their game list.
        guard.run(() -> valkey.convertAndSend(CHANNEL_PREFIX + userId, sender.serialise(message)));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            UUID userId = UUID.fromString(channel.substring(CHANNEL_PREFIX.length()));
            Envelope event = sender.parseEnvelope(new String(message.getBody(), StandardCharsets.UTF_8));
            registry.forEachSessionOf(userId, session -> sender.send(session, event));
        } catch (RuntimeException malformed) {
            // Must not kill the listener thread (see GameChannelListener).
            log.warn("Discarding unparseable user message: {}", malformed.toString());
        }
    }
}
