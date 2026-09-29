package com.chessplatform.integration.rating;

import com.chessplatform.chess.MoveIntent;
import com.chessplatform.game.GameFinished;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.SubmitMoveCommand;
import com.chessplatform.game.Termination;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import com.chessplatform.messaging.internal.OutboxRelay;
import com.chessplatform.rating.internal.RatingService;
import com.chessplatform.messaging.internal.SqsQueues;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rating consumer against a real SQS-compatible queue and the real listener container.
 * Phase 5's done-when, one test each: duplicate delivery rates once, a poison message reaches
 * the DLQ after three receives, a crash mid-transaction is applied exactly once on
 * redelivery — plus the concurrency case the row locks exist for.
 */
@DisplayName("Rating consumer")
class RatingConsumerIntegrationTest extends IntegrationTestBase {

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
        // Retries after 1 s instead of 30, so a failure reaches its third receive in seconds.
        registry.add("chess.messaging.visibility-timeout", () -> "1s");
    }

    @Autowired
    private GameService gameService;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private RatingService ratingService;
    @Autowired
    private SqsTemplate template;
    @Autowired
    private SqsAsyncClient sqs;
    @Autowired
    private SqsQueues queues;
    @Autowired
    private JsonMapper json;
    @Autowired
    private MeterRegistry metrics;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private UserRepository users;
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;

    private User alice;
    private User bob;

    @BeforeEach
    void players() {
        alice = player("alice");
        bob = player("bob");
    }

    @AfterEach
    void cleanUp() {
        jdbc().update("DROP TRIGGER IF EXISTS fail_once ON rating_history");
        jdbc().update("DROP FUNCTION IF EXISTS fail_once()");
        jdbc().update("DROP SEQUENCE IF EXISTS fail_once_seq");
        jdbc().update("DROP TRIGGER IF EXISTS slow_history ON rating_history");
        jdbc().update("DROP FUNCTION IF EXISTS slow_history()");
        jdbc().update("DELETE FROM processed_events");
        jdbc().update("DELETE FROM outbox");
        moves.deleteAll();
        games.deleteAll();   // rating_history cascades
        users.deleteAll();
    }

    private User player(String prefix) {
        return registrar.register(prefix + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
    }

    private int ratingOf(User user) {
        return jdbc().queryForObject("SELECT rating FROM users WHERE id = ?", Integer.class, user.id());
    }

    private int historyRows(UUID gameId) {
        return jdbc().queryForObject("SELECT count(*) FROM rating_history WHERE game_id = ?", Integer.class, gameId);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    /** An active game row to hang history on; the event itself says who won. */
    private UUID game(User white, User black) {
        return gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3).id();
    }

    private String envelope(UUID eventId, GameFinished payload) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", GameFinished.TYPE);
        envelope.put("aggregateId", payload.gameId().toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.set("payload", json.valueToTree(payload));
        return json.writeValueAsString(envelope);
    }

    private void send(String body) {
        template.send(queues.queueUrl(), body);
    }

    private GameFinished whiteWins(UUID gameId, User white, User black) {
        return new GameFinished(1, gameId, white.id(), black.id(), GameResult.WHITE_WIN, Termination.RESIGNATION);
    }

    @Test
    @DisplayName("a finished game travels outbox → relay → SQS → consumer and moves both ratings")
    void endToEnd() throws Exception {
        Game game = gameService.createGame(alice.id(), bob.id(), TimeControl.BLITZ_5_3);
        String[][] foolsMate = {{"f2", "f3"}, {"e7", "e5"}, {"g2", "g4"}, {"d8", "h4"}};
        for (int ply = 0; ply < foolsMate.length; ply++) {
            gameService.submitMove(game.id(), (ply % 2 == 0 ? alice : bob).id(),
                    new SubmitMoveCommand(UUID.randomUUID(), ply, MoveIntent.of(foolsMate[ply][0], foolsMate[ply][1])));
        }

        assertThat(relay.relayOnce()).isEqualTo(1);
        await("the game to be rated", () -> historyRows(game.id()) == 2);

        assertThat(ratingOf(alice)).as("White was mated: equal players, -K/2").isEqualTo(1184);
        assertThat(ratingOf(bob)).isEqualTo(1216);
    }

    @Test
    @DisplayName("the same event delivered twice moves the ratings once")
    void duplicateDelivery() throws Exception {
        UUID gameId = game(alice, bob);
        String body = envelope(UUID.randomUUID(), whiteWins(gameId, alice, bob));
        double before = metrics.counter("chess.rating.duplicates").count();

        send(body);
        send(body);   // what a relay crash between send and mark, or SQS itself, produces
        await("both deliveries to be handled",
                () -> metrics.counter("chess.rating.duplicates").count() >= before + 1);

        assertThat(ratingOf(alice)).isEqualTo(1216);
        assertThat(ratingOf(bob)).isEqualTo(1184);
        assertThat(historyRows(gameId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a message that always fails lands in the dead-letter queue after three receives")
    void poisonGoesToDlq() throws Exception {
        send("this is not an event");

        List<Message> dead = List.of();
        long deadline = System.currentTimeMillis() + 20_000;
        while (dead.isEmpty() && System.currentTimeMillis() < deadline) {
            dead = sqs.receiveMessage(r -> r.queueUrl(queues.deadLetterQueueUrl())
                    .waitTimeSeconds(1).messageSystemAttributeNamesWithStrings("ApproximateReceiveCount"))
                    .join().messages();
        }

        assertThat(dead).as("dead-lettered").singleElement()
                .satisfies(message -> assertThat(message.body()).isEqualTo("this is not an event"));
        assertThat(metrics.counter("chess.rating.failures").count()).isGreaterThanOrEqualTo(3);
    }

    /**
     * The worker dies mid-transaction: the ratings are already updated when the history insert
     * fails. Everything rolls back — including the dedupe claim — and the redelivery applies
     * the game exactly once. A sequence counts the attempts because it is not transactional: a
     * marker table would be rolled back with the failure and the trigger would fire forever.
     */
    @Test
    @DisplayName("a crash inside the rating transaction is applied exactly once on redelivery")
    void crashMidTransaction() throws Exception {
        jdbc().update("CREATE SEQUENCE fail_once_seq");
        jdbc().update("""
                CREATE FUNCTION fail_once() RETURNS trigger AS $$
                BEGIN
                  IF nextval('fail_once_seq') = 1 THEN
                    RAISE EXCEPTION 'injected crash after the ratings were written';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql
                """);
        jdbc().update("CREATE TRIGGER fail_once BEFORE INSERT ON rating_history FOR EACH ROW EXECUTE FUNCTION fail_once()");
        UUID gameId = game(alice, bob);

        send(envelope(UUID.randomUUID(), whiteWins(gameId, alice, bob)));
        await("the retry to succeed", () -> historyRows(gameId) == 2);

        assertThat(jdbc().queryForObject("SELECT last_value FROM fail_once_seq", Long.class))
                .as("the first attempt really did fail").isGreaterThan(1);
        assertThat(ratingOf(alice)).as("applied once, not twice").isEqualTo(1216);
        assertThat(ratingOf(bob)).isEqualTo(1184);
    }

    /**
     * Alice wins two games, rated at the same instant. Elo reads her rating first, so without
     * the row lock both transactions read 1200 and one win is lost (she ends on 1216). With
     * it, the second blocks on the lock until the first commits, and starts from 1216.
     *
     * <p>Made deterministic, because the natural race window — read to commit — is a few
     * milliseconds, and an earlier version of this test (two messages through the listener)
     * passed with the lock removed. Mutation-checked: two threads released together call the
     * service directly, and a trigger holds each transaction 300 ms between reading the
     * ratings and committing — exactly where a lost update happens.
     */
    @Test
    @DisplayName("two of one player's games rated concurrently: no lost update")
    void concurrentGamesOfOnePlayer() throws Exception {
        jdbc().update("""
                CREATE FUNCTION slow_history() RETURNS trigger AS $$
                BEGIN PERFORM pg_sleep(0.3); RETURN NEW; END $$ LANGUAGE plpgsql
                """);
        jdbc().update("CREATE TRIGGER slow_history BEFORE INSERT ON rating_history FOR EACH ROW EXECUTE FUNCTION slow_history()");
        User carol = player("carol");
        GameFinished first = whiteWins(game(alice, bob), alice, bob);
        GameFinished second = whiteWins(game(alice, carol), alice, carol);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> {
                start.await();
                return ratingService.apply(UUID.randomUUID(), first);
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                return ratingService.apply(UUID.randomUUID(), second);
            });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }

        List<Map<String, Object>> alicesHistory = jdbc().queryForList(
                "SELECT rating_before, rating_after FROM rating_history WHERE user_id = ? ORDER BY rating_before",
                alice.id());
        assertThat(alicesHistory.get(1).get("rating_before"))
                .as("the second rating started from the first's result")
                .isEqualTo(alicesHistory.get(0).get("rating_after"));
        assertThat(ratingOf(alice)).as("+16, then +15 from 1216 against a 1200 — not 1216").isEqualTo(1231);
    }
}
