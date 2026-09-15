package com.chessplatform.realtime.internal;

import com.chessplatform.realtime.GameEventPublisher;
import com.chessplatform.realtime.protocol.Envelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-instance fanout over Valkey Pub/Sub.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>With two instances behind a load balancer, the two players in a game frequently land
 * on different ones. Instance A commits White's move; Black's socket lives on instance B.
 * An in-JVM publisher delivers nothing to Black, and the game silently desynchronises for
 * one player — no error, no log, just a board that stops updating. That is the concrete
 * reason ADR-003 rejects Spring's simple STOMP broker, and this class is the alternative.
 *
 * <h2>One channel per game</h2>
 *
 * <p>An instance subscribes to {@code game:{id}} only while it holds at least one socket
 * for that game. The alternative — one firehose channel every instance listens to — means
 * every instance receives every move in the system and discards almost all of them, which
 * scales with total traffic rather than with local interest.
 *
 * <h2>The publisher receives its own message</h2>
 *
 * <p>Deliberately. Redis delivers a published message to every subscriber including the
 * publishing connection's instance, so the mover's own client is served through the same
 * path as the opponent's rather than a local shortcut. One delivery path means one set of
 * bugs, and it guarantees both players see byte-identical frames.
 *
 * <h2>Fire and forget</h2>
 *
 * <p>Pub/Sub has no delivery guarantee: a subscriber that is momentarily disconnected
 * simply misses the message. That is acceptable only because of ADR-007 — a client that
 * missed something reconnects and receives a full snapshot, so a gap is a display delay
 * rather than divergence. Redis Streams would guarantee delivery, at the cost of consumer
 * groups, acknowledgement and trimming, to solve a problem snapshot-on-reconnect already
 * solves. The two decisions have to be defended together.
 *
 * <h2>The listener container is Spring Boot's, not ours</h2>
 *
 * <p>An earlier version declared its own {@code RedisMessageListenerContainer} bean on the
 * belief that Boot does not auto-configure one. It does, and the result was two candidates
 * and a context that would not start. Using the auto-configured container means its
 * lifecycle, connection handling and shutdown are managed for us — which is what we wanted
 * anyway, and one fewer thing to get wrong.
 *
 * <p>Generalisable: before writing a bean because "the framework doesn't provide one",
 * check. {@code --debug} prints the condition evaluation report, which lists every
 * auto-configuration that applied and every one that did not, with the reason.
 */
@Component
@ConditionalOnProperty(name = "chess.realtime.fanout", havingValue = "valkey")
public class ValkeyGameEventPublisher implements GameEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ValkeyGameEventPublisher.class);
    private static final String CHANNEL_PREFIX = "game:";

    private final StringRedisTemplate valkey;
    private final RedisMessageListenerContainer container;
    private final GameChannelListener listener;
    private final WebSocketSender sender;
    private final Counter published;
    private final Counter publishFailures;

    /** Topics this instance currently listens to, so unsubscribe can find them again. */
    private final Map<UUID, ChannelTopic> subscribedTopics = new ConcurrentHashMap<>();

    public ValkeyGameEventPublisher(StringRedisTemplate valkey,
                                    RedisMessageListenerContainer container,
                                    GameChannelListener listener,
                                    WebSocketSender sender,
                                    MeterRegistry metrics) {
        this.valkey = valkey;
        this.container = container;
        this.listener = listener;
        this.sender = sender;
        this.published = Counter.builder("chess.ws.events.published")
                .description("Events published to Valkey for cross-instance fanout")
                .register(metrics);
        this.publishFailures = Counter.builder("chess.ws.events.publish_failures")
                .description("Publishes that failed, e.g. Valkey unavailable")
                .register(metrics);
    }

    @Override
    public void publish(UUID gameId, Envelope event) {
        try {
            valkey.convertAndSend(CHANNEL_PREFIX + gameId, sender.serialise(event));
            published.increment();
        } catch (RuntimeException valkeyUnavailable) {
            // Never rethrow. The move is already committed and durable; failing here would
            // turn a fanout outage into a move failure. Clients see a stale board and
            // recover by polling or reconnecting (ARCHITECTURE.md §13).
            publishFailures.increment();
            log.warn("Fanout failed for game {} — clients will fall back to polling: {}",
                    gameId, valkeyUnavailable.toString());
        }
    }

    @Override
    public void onFirstLocalSubscriber(UUID gameId) {
        ChannelTopic topic = new ChannelTopic(CHANNEL_PREFIX + gameId);
        try {
            container.addMessageListener(listener, topic);
            subscribedTopics.put(gameId, topic);
        } catch (RuntimeException valkeyUnavailable) {
            // Degraded: this instance will not receive the opponent's moves. The socket
            // still works for sending, and the client's snapshot on reconnect repairs it.
            log.warn("Could not subscribe to {}: {}", topic.getTopic(), valkeyUnavailable.toString());
        }
    }

    @Override
    public void onLastLocalSubscriber(UUID gameId) {
        ChannelTopic topic = subscribedTopics.remove(gameId);
        if (topic == null) {
            return;
        }
        try {
            container.removeMessageListener(listener, topic);
        } catch (RuntimeException ignored) {
            log.debug("Could not unsubscribe from {}", topic.getTopic());
        }
    }
}
