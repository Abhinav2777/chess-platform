package com.chessplatform.integration.realtime;

import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.Termination;
import com.chessplatform.game.TimeControl;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4's "done when": Valkey goes away mid-game and no move is lost.
 *
 * <p>Runs with {@code fanout=valkey}, the multi-instance configuration — the only one in
 * which Valkey carries moves at all. Valkey is <em>paused</em>, not stopped: a paused
 * server holds connections open and answers nothing, so every call waits for its timeout
 * (the harsher failure), and unpausing restores it on the same port, so recovery can be
 * tested too. A stopped Testcontainer would come back on a new random port.
 *
 * <p>During the outage the players keep sending moves over their sockets — sending needs
 * only the instance — and learn the opponent's moves by polling {@code GET /api/games/{id}},
 * exactly as the degraded client does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "chess.realtime.fanout=valkey")
@DisplayName("Valkey outage mid-game")
class ValkeyOutageIntegrationTest {

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
        registry.add("chess.matchmaking.scheduler-enabled", () -> "false");
        // Short circuit window so the recovery check is quick. The window is also the
        // longest recovery can lag after Valkey returns — see the recovery step below.
        registry.add("chess.valkey.circuit-open-for", () -> "1s");
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
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;
    @Autowired
    private UserRepository users;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void cleanUp() {
        unpause();
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("a whole game is played to checkmate with Valkey down, then live events resume")
    void fullGameThroughOutage() throws Exception {
        User white = registrar.register("ow" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        User black = registrar.register("ob" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        String whiteToken = jwt.issueAccessToken(white.id(), white.username());
        String blackToken = jwt.issueAccessToken(black.id(), black.username());
        Game game = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);

        try (TestWebSocketClient w = subscribed(whiteToken, game.id());
             TestWebSocketClient b = subscribed(blackToken, game.id())) {

            // Healthy: one move, delivered live to the opponent.
            w.send(ClientMessage.MOVE, move(game.id(), 0, "e2", "e4"));
            assertThat(b.payloadOf(b.await("MOVE_MADE")).get("san")).isEqualTo("e4");
            w.await("MOVE_MADE");   // the mover's own echo — the client's liveness signal

            pause();

            // Degraded: moves go in over the sockets; each side learns the other's move by
            // polling REST, as the degraded client does. Scholar's mate from 1.e4.
            String[][] rest = {{"e7", "e5"}, {"f1", "c4"}, {"b8", "c6"}, {"d1", "h5"},
                               {"g8", "f6"}, {"h5", "f7"}};
            long worstMs = 0;
            for (int i = 0; i < rest.length; i++) {
                int ply = i + 1;
                TestWebSocketClient mover = ply % 2 == 1 ? b : w;
                long sent = System.currentTimeMillis();
                mover.send(ClientMessage.MOVE, move(game.id(), ply, rest[i][0], rest[i][1]));
                Map<String, Object> seen = pollUntilPly(ply == 1 ? whiteToken : blackToken, game.id(), ply + 1);
                worstMs = Math.max(worstMs, System.currentTimeMillis() - sent);
                assertThat(((Map<?, ?>) seen.get("game")).get("ply")).isEqualTo(ply + 1);
            }
            System.out.printf("MEASURED degraded move -> visible to opponent via REST, worst %d ms%n", worstMs);

            Game finished = games.findById(game.id()).orElseThrow();
            assertThat(finished.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(finished.result()).isEqualTo(GameResult.WHITE_WIN);
            assertThat(finished.termination()).isEqualTo(Termination.CHECKMATE);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).as("no move lost").hasSize(7);

            // Matchmaking says so, rather than hanging.
            b.send(ClientMessage.SEEK, new Payloads.Seek(300, 3));
            assertThat(b.payloadOf(b.await("ERROR")).get("code")).isEqualTo("MATCHMAKING_UNAVAILABLE");
        }

        unpause();
        // The circuit trades per-call timeouts during an outage for up to one window of
        // continued degradation after it: until the window lapses and a call probes, this
        // instance does not know Valkey is back. Wait it out, then events must flow.
        Thread.sleep(1_200);

        // Recovery: a fresh game, and events flow live again on the same instance.
        Game second = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);
        try (TestWebSocketClient w = subscribed(whiteToken, second.id());
             TestWebSocketClient b = subscribed(blackToken, second.id())) {
            w.send(ClientMessage.MOVE, move(second.id(), 0, "d2", "d4"));
            assertThat(b.payloadOf(b.await("MOVE_MADE")).get("san"))
                    .as("fanout recovered after unpause").isEqualTo("d4");
        }
    }

    private Payloads.Move move(UUID gameId, int ply, String from, String to) {
        return new Payloads.Move(gameId, UUID.randomUUID(), ply, from, to, null);
    }

    private TestWebSocketClient subscribed(String token, UUID gameId) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient(json).connect(port);
        client.send(ClientMessage.AUTH, new Payloads.Auth(token));
        client.await("AUTH_OK");
        client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(gameId));
        client.await("GAME_SNAPSHOT");
        return client;
    }

    /** The degraded client's view: GET the game until it shows the expected ply. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilPly(String token, UUID gameId, int ply) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> body = Map.of();
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/games/" + gameId))
                    .header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            body = json.readValue(response.body(), Map.class);
            if (((Number) ((Map<String, Object>) body.get("game")).get("ply")).intValue() >= ply) {
                return body;
            }
            Thread.sleep(100);
        }
        return body;
    }

    private static void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(VALKEY.getContainerId()).exec();
    }

    private static void unpause() {
        var docker = DockerClientFactory.instance().client();
        Boolean paused = docker.inspectContainerCmd(VALKEY.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            docker.unpauseContainerCmd(VALKEY.getContainerId()).exec();
        }
    }
}
