package com.chessplatform.integration.tracing;

import com.chessplatform.chess.MoveIntent;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.SubmitMoveCommand;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import com.chessplatform.messaging.internal.OutboxRelay;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One trace from the move that ends a game to the rating update it causes (Phase 9, ADR-026) —
 * across two asynchronous boundaries: the transactional outbox (sent later, by a scheduled relay
 * in its own trace) and SQS.
 *
 * <p>Real spans, recorded by OpenTelemetry's in-memory exporter, not a mock tracer.
 */
@Import(TracePropagationIntegrationTest.SpanCapture.class)
@DisplayName("Trace propagation")
class TracePropagationIntegrationTest extends IntegrationTestBase {

    static final GenericContainer<?> ELASTICMQ =
            new GenericContainer<>("softwaremill/elasticmq-native:1.7.1").withExposedPorts(9324);

    static {
        ELASTICMQ.start();
    }

    @DynamicPropertySource
    static void consumer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324));
        registry.add("spring.cloud.aws.credentials.access-key", () -> "local");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "local");
        registry.add("chess.messaging.create-queues", () -> "true");
        registry.add("chess.rating.consumer-enabled", () -> "true");
        registry.add("management.tracing.sampling.probability", () -> "1.0");
    }

    /** Boot hands every SpanExporter bean to the tracer provider — this one keeps spans in memory. */
    @TestConfiguration
    static class SpanCapture {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired private InMemorySpanExporter spans;
    @Autowired private SdkTracerProvider tracerProvider;
    @Autowired private Tracer tracer;
    @Autowired private GameService gameService;
    @Autowired private OutboxRelay relay;
    @Autowired private UserRegistrar registrar;
    @Autowired private UserRepository users;
    @Autowired private GameRepository games;
    @Autowired private MoveRepository moves;

    @AfterEach
    void cleanUp() {
        jdbc().update("DELETE FROM processed_events");
        jdbc().update("DELETE FROM outbox");
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
        spans.reset();
    }

    @Test
    @DisplayName("the move that ends a game and the rating it causes share one trace — through the outbox and SQS")
    void traceSpansOutboxAndQueue() throws Exception {
        User white = player("tw"), black = player("tb");
        Game game = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);

        // Stands in for the WebSocket observation around the final MOVE frame.
        Span root = tracer.nextSpan().name("test: final move").start();
        try (Tracer.SpanInScope _ = tracer.withSpan(root)) {   // only its scope matters
            String[][] foolsMate = {{"f2", "f3"}, {"e7", "e5"}, {"g2", "g4"}, {"d8", "h4"}};
            for (int ply = 0; ply < foolsMate.length; ply++) {
                gameService.submitMove(game.id(), (ply % 2 == 0 ? white : black).id(),
                        new SubmitMoveCommand(UUID.randomUUID(), ply, MoveIntent.of(foolsMate[ply][0], foolsMate[ply][1])));
            }
        } finally {
            root.end();
        }
        String traceId = root.context().traceId();

        String stored = jdbc().queryForObject("SELECT trace_parent FROM outbox WHERE aggregate_id = ?",
                String.class, game.id());
        assertThat(stored).as("the outbox row carries the game-ending request's trace")
                .startsWith("00-" + traceId + "-");

        assertThat(relay.relayOnce()).isEqualTo(1);   // a scheduled job's work: its own trace
        await("the game to be rated", () -> jdbc().queryForObject(
                "SELECT count(*) FROM rating_history WHERE game_id = ?", Integer.class, game.id()) == 2);
        await("the consumer's span to be exported", () -> {
            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
            return inTrace(traceId).stream().anyMatch(span -> span.getKind() == SpanKind.CONSUMER);
        });

        List<SpanData> trace = inTrace(traceId);
        assertThat(trace).as("the SQS listener's span joined the move's trace")
                .anyMatch(span -> span.getKind() == SpanKind.CONSUMER);
        assertThat(trace).as("…and so did the rating update's database work, after the queue")
                .anyMatch(span -> span.getKind() == SpanKind.CLIENT
                        && span.getStartEpochNanos() > consumerStart(trace));
    }

    private List<SpanData> inTrace(String traceId) {
        return spans.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    private static long consumerStart(List<SpanData> trace) {
        return trace.stream().filter(span -> span.getKind() == SpanKind.CONSUMER)
                .mapToLong(SpanData::getStartEpochNanos).min().orElse(Long.MAX_VALUE);
    }

    private User player(String prefix) {
        return registrar.register(prefix + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(200);
        }
    }
}
