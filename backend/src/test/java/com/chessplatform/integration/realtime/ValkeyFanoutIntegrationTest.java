package com.chessplatform.integration.realtime;

import com.chessplatform.ChessPlatformApplication;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.JwtService;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.realtime.protocol.ClientMessage;
import com.chessplatform.realtime.protocol.Payloads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two application instances, one game, one player on each.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>ADR-003 rejects Spring's simple STOMP broker on one concrete ground: an in-JVM broker
 * does not fan out across instances, so with two servers behind a load balancer a move
 * committed on one never reaches a player connected to the other. Everything up to
 * Milestone 2.1 asserted that. This proves it, by actually running two instances.
 *
 * <p>The failure mode it guards against is the nastiest kind: nothing throws, nothing
 * logs, and one player's board silently stops updating. Swap {@code chess.realtime.fanout}
 * back to {@code local} and this test fails while every other test in the suite still
 * passes — which is precisely why it is worth the cost of a second Spring context.
 *
 * <h2>Why the second instance is started by hand</h2>
 *
 * <p>Spring's test framework gives one context per configuration. A genuine second
 * instance — its own Tomcat, its own session registry, its own Valkey subscriptions,
 * sharing only the database and Valkey — is what a load-balanced deployment looks like,
 * and nothing less would exercise the code path that matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "chess.realtime.fanout=valkey")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Cross-instance fanout")
class ValkeyFanoutIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        VALKEY.start();
    }

    /** The second instance. Same database, same Valkey, separate everything else. */
    private static ConfigurableApplicationContext secondInstance;
    private static int secondPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    /**
     * Configuration is passed as command-line arguments, <strong>not</strong> via
     * {@code SpringApplicationBuilder.properties(...)}.
     *
     * <p>That method maps to {@code setDefaultProperties}, which is the <em>lowest</em>
     * precedence property source — below {@code application.yml}. The first version used
     * it, so {@code chess.realtime.fanout} stayed {@code local} on this instance and the
     * whole test silently exercised in-JVM fanout on two unconnected instances. The
     * datasource and Redis settings appeared to work only because {@code application.yml}
     * does not define them.
     *
     * <p>Command-line arguments are the highest-precedence source, which is what "override
     * whatever the application ships with" actually requires.
     */
    @BeforeAll
    static void startSecondInstance() {
        secondInstance = new SpringApplicationBuilder(ChessPlatformApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(),
                "--spring.data.redis.port=" + VALKEY.getMappedPort(6379),
                "--chess.realtime.fanout=valkey");

        secondPort = secondInstance.getEnvironment()
                .getRequiredProperty("local.server.port", Integer.class);

        // Assert the override actually took. Without this the test would pass with both
        // instances on in-JVM fanout the moment someone changes how they are configured —
        // a green test proving nothing, which is worse than a red one.
        assertThat(secondInstance.getEnvironment().getProperty("chess.realtime.fanout"))
                .as("instance two must use Valkey fanout or this test proves nothing")
                .isEqualTo("valkey");
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
        }
    }

    @LocalServerPort
    private int firstPort;

    @Autowired
    private JsonMapper json;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private JwtService jwt;
    @Autowired
    private GameService gameService;
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;
    @Autowired
    private UserRepository users;

    private User white;
    private User black;
    private String whiteToken;
    private String blackToken;
    private Game game;

    @BeforeEach
    void setUp() {
        white = registrar.register("w" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        black = registrar.register("b" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        // Tokens are signed on instance one and verified on instance two. They work
        // because JWT verification is stateless — the same property that removes any need
        // for sticky sessions (ADR-009).
        whiteToken = jwt.issueAccessToken(white.id(), white.username());
        blackToken = jwt.issueAccessToken(black.id(), black.username());
        game = gameService.createGame(white.id(), black.id());
    }

    @AfterEach
    void cleanUp() {
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    private TestWebSocketClient subscribedOn(int port, String token) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient(json).connect(port);
        client.send(ClientMessage.AUTH, new Payloads.Auth(token));
        client.await("AUTH_OK");
        client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));
        client.captureSnapshot();
        return client;
    }

    @Test
    @Order(1)
    @DisplayName("a move on one instance reaches a player connected to the other")
    void moveCrossesInstances() throws Exception {
        try (TestWebSocketClient onFirst = subscribedOn(firstPort, whiteToken);
             TestWebSocketClient onSecond = subscribedOn(secondPort, blackToken)) {

            onFirst.send(ClientMessage.MOVE, new Payloads.Move(
                    game.id(), UUID.randomUUID(), 0, "e2", "e4", null));

            Map<String, Object> toBlack = onSecond.payloadOf(onSecond.await("MOVE_MADE"));

            assertThat(toBlack.get("san")).isEqualTo("e4");
            assertThat(toBlack.get("ply")).isEqualTo(1);
            assertThat(toBlack.get("sideToMove")).isEqualTo("BLACK");
        }
    }

    @Test
    @Order(2)
    @DisplayName("a full exchange works in both directions")
    void movesFlowBothWays() throws Exception {
        try (TestWebSocketClient onFirst = subscribedOn(firstPort, whiteToken);
             TestWebSocketClient onSecond = subscribedOn(secondPort, blackToken)) {

            onFirst.send(ClientMessage.MOVE, new Payloads.Move(
                    game.id(), UUID.randomUUID(), 0, "e2", "e4", null));
            onSecond.await("MOVE_MADE");
            onFirst.await("MOVE_MADE");

            // Now the other way: the reply originates on instance two.
            onSecond.send(ClientMessage.MOVE, new Payloads.Move(
                    game.id(), UUID.randomUUID(), 1, "e7", "e5", null));

            assertThat(onFirst.payloadOf(onFirst.await("MOVE_MADE")).get("san")).isEqualTo("e5");
            assertThat(games.findById(game.id()).orElseThrow().ply()).isEqualTo(2);
        }
    }

    @Test
    @Order(3)
    @DisplayName("game end reaches the opposite instance")
    void gameEndCrossesInstances() throws Exception {
        try (TestWebSocketClient onFirst = subscribedOn(firstPort, whiteToken);
             TestWebSocketClient onSecond = subscribedOn(secondPort, blackToken)) {

            onSecond.send(ClientMessage.RESIGN, new Payloads.Resign(game.id()));

            Map<String, Object> finished = onFirst.payloadOf(onFirst.await("GAME_FINISHED"));
            assertThat(finished.get("result")).isEqualTo("WHITE_WIN");
            assertThat(finished.get("termination")).isEqualTo("RESIGNATION");
        }
    }

    @Test
    @Order(4)
    @DisplayName("presence crosses instances and is reflected in a later snapshot")
    void presenceCrossesInstances() throws Exception {
        try (TestWebSocketClient onFirst = subscribedOn(firstPort, whiteToken)) {
            // White subscribed before black existed, so its snapshot saw an absent opponent.
            assertThat(onFirst.payloadOf(onFirst.lastSnapshot()).get("opponentOnline"))
                    .isEqualTo(false);

            try (TestWebSocketClient onSecond = subscribedOn(secondPort, blackToken)) {
                // Filtered by user id, because white also receives its OWN announcement:
                // pub/sub delivers to every subscriber including the publishing instance,
                // so a client sees its own presence echo. Real clients ignore frames about
                // themselves the same way.
                Map<String, Object> presence = onFirst.payloadOf(
                        onFirst.awaitPresenceFor(black.id()));
                assertThat(presence.get("online")).isEqualTo(true);

                // And black's own snapshot already shows white as present.
                assertThat(onSecond.payloadOf(onSecond.lastSnapshot()).get("opponentOnline"))
                        .isEqualTo(true);
            }

            // Black disconnects; white is told, from the other instance.
            Map<String, Object> gone = onFirst.payloadOf(onFirst.awaitPresenceFor(black.id()));
            assertThat(gone.get("online")).isEqualTo(false);
        }
    }

    /**
     * The reconnect-overlap case, which is what a real client does every time a network
     * blip drops its socket — and what React's StrictMode does on every page load in
     * development.
     *
     * <p>A second connection for the same user must not announce anything, and closing the
     * first must not announce offline while the second is still open. Presence is a fact
     * about a user derived from their connection count, not a flag written by whichever
     * socket event was processed last.
     */
    @Test
    @Order(5)
    @DisplayName("an overlapping reconnect never marks a connected player offline")
    void overlappingReconnectDoesNotFlapPresence() throws Exception {
        try (TestWebSocketClient watcher = subscribedOn(firstPort, whiteToken)) {
            TestWebSocketClient blackFirst = subscribedOn(secondPort, blackToken);
            assertThat(watcher.payloadOf(watcher.awaitPresenceFor(black.id())).get("online"))
                    .isEqualTo(true);

            // Black reconnects: the new socket is established BEFORE the old one closes,
            // exactly as a client that re-dials on a blip would.
            try (TestWebSocketClient blackSecond = subscribedOn(secondPort, blackToken)) {
                blackFirst.close();
                Thread.sleep(500);

                // The definitive check: whatever frames flew about, black is still online.
                // Reading it from a fresh snapshot rather than from the event stream means
                // the assertion is about persisted state, not about message ordering.
                try (TestWebSocketClient probe = subscribedOn(firstPort, whiteToken)) {
                    assertThat(probe.payloadOf(probe.lastSnapshot()).get("opponentOnline"))
                            .as("closing a superseded socket must not mark a connected player offline")
                            .isEqualTo(true);
                }
            }
        }
    }

    /**
     * Graceful degradation, per ARCHITECTURE.md §13.
     *
     * <p>Valkey is a cache and a transport, never the source of truth (ADR-004). Losing it
     * must cost real-time delivery and presence — not the ability to play chess. A move
     * that failed because a cache was down would be the wrong trade in every direction.
     *
     * <p>Ordered last and left stopped: this test deliberately destroys the shared
     * container, so nothing may run after it.
     */
    @Test
    @Order(99)
    @DisplayName("moves still commit when Valkey is unavailable")
    void survivesValkeyOutage() throws Exception {
        try (TestWebSocketClient onFirst = subscribedOn(firstPort, whiteToken)) {
            VALKEY.stop();

            onFirst.send(ClientMessage.MOVE, new Payloads.Move(
                    game.id(), UUID.randomUUID(), 0, "e2", "e4", null));

            // No frame arrives — fanout is down. But the move is durable, which is the
            // property that matters. The client recovers by polling GET /api/games/{id}
            // or by reconnecting, both of which read from PostgreSQL.
            Thread.sleep(1_000);

            Game reloaded = games.findById(game.id()).orElseThrow();
            assertThat(reloaded.ply())
                    .as("the move must commit even with no fanout")
                    .isEqualTo(1);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).hasSize(1);
        }
    }
}
