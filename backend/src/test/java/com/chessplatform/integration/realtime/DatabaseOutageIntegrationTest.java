package com.chessplatform.integration.realtime;

import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.internal.JwtService;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.realtime.protocol.ClientMessage;
import com.chessplatform.realtime.protocol.Payloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL frozen mid-game (Phase 10.2) — the gray failure: nothing refuses, nothing answers.
 * The container is paused through the cgroup freezer, as the kind drill froze every PostgreSQL
 * process (loadtest/failure-drill.sh pg-hang).
 *
 * <p>Found by that drill, and held here: a request on a pooled connection waited for the whole
 * freeze (no socket timeout — pgjdbc's default is forever), and every refused move reached the
 * player as INTERNAL with an ERROR log. Now: bounded by the socket timeout, answered as a
 * retryable SERVICE_UNAVAILABLE (503 + Retry-After over REST), and the same socket plays on once
 * the database is back.
 *
 * <p>Its own PostgreSQL container: pausing a shared one would freeze every other test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Database outage")
class DatabaseOutageIntegrationTest {

    private static final int SOCKET_TIMEOUT_SECONDS = 2;

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        VALKEY.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout",
                () -> String.valueOf(SOCKET_TIMEOUT_SECONDS));
        // Nothing in the background touching the frozen database.
        registry.add("chess.clock.sweeper-enabled", () -> "false");
        registry.add("chess.matchmaking.scheduler-enabled", () -> "false");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JsonMapper json;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private JwtService jwt;
    @Autowired
    private GameService gameService;

    private String whiteToken;
    private Game game;

    @BeforeEach
    void setUp() {
        User white = registrar.register("ow" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        User black = registrar.register("ob" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        whiteToken = jwt.issueAccessToken(white.id(), white.username());
        game = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);
    }

    @AfterEach
    void thaw() {
        if (paused()) {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("frozen: a move is SERVICE_UNAVAILABLE within the timeouts and REST is 503 + Retry-After; thawed: the same socket plays on")
    void frozenThenThawed() throws Exception {
        try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
            client.send(ClientMessage.AUTH, new Payloads.Auth(whiteToken));
            client.await("AUTH_OK");
            client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));
            client.await("GAME_SNAPSHOT");

            POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();

            long started = System.nanoTime();
            client.send(ClientMessage.MOVE, new Payloads.Move(game.id(), UUID.randomUUID(), 0, "e2", "e4", null));
            Map<String, Object> refused = client.payloadOf(client.await("ERROR"));
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(refused.get("code")).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(took).as("bounded by the socket timeout, not by the freeze")
                    .isLessThan(Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS + 3));

            HttpResponse<String> rest = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/games/" + game.id()))
                            .header("Authorization", "Bearer " + whiteToken)
                            .timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(rest.statusCode()).isEqualTo(503);
            assertThat(rest.headers().firstValue("Retry-After")).hasValue("2");
            assertThat(rest.body()).contains("SERVICE_UNAVAILABLE");

            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();

            // Recovery: the pool replaces the connections it abandoned; nothing is restarted.
            client.send(ClientMessage.MOVE, new Payloads.Move(game.id(), UUID.randomUUID(), 0, "e2", "e4", null));
            assertThat(client.payloadOf(client.await("MOVE_MADE")).get("ply")).isEqualTo(1);
        }
    }

    private static boolean paused() {
        Boolean paused = POSTGRES.getDockerClient().inspectContainerCmd(POSTGRES.getContainerId())
                .exec().getState().getPaused();
        return Boolean.TRUE.equals(paused);
    }
}
