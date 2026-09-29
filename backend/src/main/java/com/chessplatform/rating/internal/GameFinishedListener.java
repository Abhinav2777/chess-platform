package com.chessplatform.rating.internal;

import com.chessplatform.game.GameFinished;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Consumes {@code GAME_FINISHED} from SQS (worker role only — no bean, no listener).
 *
 * <h2>Manual acknowledgement, on purpose</h2>
 *
 * <p>Acknowledging deletes the message. It happens here, explicitly, only after
 * {@link RatingService#apply} has <em>returned</em> — i.e. after its transaction committed.
 * Delete before commit and a crash in between loses the rating; commit before delete and a
 * crash in between merely redelivers it, which the dedupe table absorbs. The framework's
 * ON_SUCCESS mode would do the same today, but this ordering is the whole correctness
 * argument, so it is written where it can be read (ADR-020).
 *
 * <h2>What throwing means</h2>
 *
 * <p>No acknowledgement: the message becomes visible again after the visibility timeout and
 * is retried; after {@code max-receive-count} receives SQS moves it to the dead-letter queue.
 * So a transient failure (database down) retries, and a permanent one (malformed body, an
 * unknown schema version, the history backstop firing) ends up where a human looks.
 */
@Component
@ConditionalOnProperty(name = "chess.rating.consumer-enabled", havingValue = "true")
public class GameFinishedListener {

    private static final Logger log = LoggerFactory.getLogger(GameFinishedListener.class);

    private final RatingService ratings;
    private final JsonMapper json;
    private final Counter failures;
    private final Counter ignored;

    public GameFinishedListener(RatingService ratings, JsonMapper json, MeterRegistry metrics) {
        this.ratings = ratings;
        this.json = json;
        this.failures = Counter.builder("chess.rating.failures")
                .description("Messages that failed and will be retried (or dead-lettered)").register(metrics);
        this.ignored = Counter.builder("chess.rating.ignored")
                .description("Messages of a type this consumer does not handle").register(metrics);
    }

    // Poll size tied to concurrency: the container refuses to fetch more per poll than it can
    // process at once (fetched-but-waiting messages would sit out their visibility timeout).
    @SqsListener(value = "${chess.messaging.queue}",
            acknowledgementMode = SqsListenerAcknowledgementMode.MANUAL,
            maxConcurrentMessages = "${chess.rating.max-concurrent-messages:5}",
            maxMessagesPerPoll = "${chess.rating.max-concurrent-messages:5}")
    public void onMessage(String body, Acknowledgement acknowledgement) {
        try {
            JsonNode envelope = json.readTree(body);
            String type = envelope.path("eventType").asString("");
            if (!GameFinished.TYPE.equals(type)) {
                // Another producer's event on this queue: not ours to fail on. Acknowledged so
                // it does not cycle to the DLQ, counted so it is not invisible.
                ignored.increment();
                log.warn("Ignoring event of type '{}'", type);
                acknowledgement.acknowledge();
                return;
            }
            UUID eventId = UUID.fromString(envelope.required("eventId").asString());
            GameFinished game = json.treeToValue(envelope.required("payload"), GameFinished.class);
            if (game.schemaVersion() != GameFinished.SCHEMA_VERSION) {
                // A newer producer than this consumer. Guessing would be worse than waiting:
                // fail, and let the DLQ hold it until a consumer that understands it is deployed.
                throw new IllegalStateException("unsupported GAME_FINISHED schema " + game.schemaVersion());
            }

            ratings.apply(eventId, game);   // committed when this returns
            acknowledgement.acknowledge();
        } catch (RuntimeException failed) {
            failures.increment();
            log.warn("Rating event failed; it will be retried, then dead-lettered: {}", failed.toString());
            throw failed;
        }
    }
}
