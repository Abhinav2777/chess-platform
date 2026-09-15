package com.chessplatform.realtime.internal;

import com.chessplatform.realtime.protocol.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Receives events from Valkey and delivers them to this instance's sockets.
 *
 * <p>A single listener for every game channel, distinguishing them by the channel name on
 * the message rather than by holding one listener per game. One object instead of one per
 * active game, and {@code RedisMessageListenerContainer} handles the multiplexing.
 *
 * <p>Runs on the container's own thread, not a request thread — which is exactly why the
 * sessions it writes to are wrapped in {@code ConcurrentWebSocketSessionDecorator}
 * (see {@code WebSocketConfig}). Without that, this thread and a request thread could
 * interleave writes on the same socket and produce a corrupt frame.
 */
@Component
@ConditionalOnProperty(name = "chess.realtime.fanout", havingValue = "valkey")
public class GameChannelListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(GameChannelListener.class);
    private static final String CHANNEL_PREFIX = "game:";

    private final GameSessionRegistry registry;
    private final WebSocketSender sender;

    public GameChannelListener(GameSessionRegistry registry, WebSocketSender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            if (!channel.startsWith(CHANNEL_PREFIX)) {
                return;
            }
            UUID gameId = UUID.fromString(channel.substring(CHANNEL_PREFIX.length()));
            Envelope event = sender.parseEnvelope(
                    new String(message.getBody(), StandardCharsets.UTF_8));

            registry.forEachWatcher(gameId, session -> sender.send(session, event));
        } catch (RuntimeException malformed) {
            // A bad message must not kill the listener thread — that would silently stop
            // fanout for every game on this instance until a restart.
            log.warn("Discarding unparseable fanout message: {}", malformed.toString());
        }
    }
}
