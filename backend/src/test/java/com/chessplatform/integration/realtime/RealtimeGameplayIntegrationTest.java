package com.chessplatform.integration.realtime;

import com.chessplatform.chess.Side;
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
import com.chessplatform.realtime.protocol.Envelope;
import com.chessplatform.realtime.protocol.Payloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two real WebSocket clients playing a real game against a real database.
 *
 * <p>Mocking the transport here would test nothing worth testing. The failure modes that
 * matter are protocol-level — a frame that never arrives, an unauthenticated socket
 * reaching a command, a reconnect that restores the wrong position — and none of them are
 * visible from a unit test of the handler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Realtime gameplay")
class RealtimeGameplayIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Needed from Milestone 2.2 onward: subscribing writes a presence key. The fanout is
     * still {@code local} here — this class tests the protocol, not cross-instance
     * delivery, which {@code ValkeyFanoutIntegrationTest} covers.
     */
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
        // Shortened so the timeout test takes a second rather than five.
        registry.add("chess.realtime.auth-timeout", () -> "1s");
        // A slow move bucket (5 per 10 s) so the flood test is deterministic: at the real
        // 20/s a token refills every 50 ms, and how many frames get through then depends on
        // how fast the server processes them — on a cold JVM, all 25 did. No other test in
        // this class sends more than a handful of moves per player.
        registry.add("chess.ratelimit.policies.move.capacity", () -> "5");
        registry.add("chess.ratelimit.policies.move.period", () -> "10s");
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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate valkey;

    private User white;
    private User black;
    private String whiteToken;
    private String blackToken;
    private Game game;

    @BeforeEach
    void setUp() {
        white = registrar.register("white" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        black = registrar.register("black" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        whiteToken = jwt.issueAccessToken(white.id(), white.username());
        blackToken = jwt.issueAccessToken(black.id(), black.username());
        game = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);
    }

    @AfterEach
    void cleanUp() {
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    /** Plays a move through the service, bypassing the socket, to set up a position. */
    private void playDirectly(User player, int expectedPly, String from, String to) {
        gameService.submitMove(game.id(), player.id(),
                new com.chessplatform.game.SubmitMoveCommand(UUID.randomUUID(),
                        expectedPly, com.chessplatform.chess.MoveIntent.of(from, to)));
    }

    private TestWebSocketClient connectedAndSubscribed(String token) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient(json).connect(port);
        client.send(ClientMessage.AUTH, new Payloads.Auth(token));
        client.await("AUTH_OK");
        client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));
        client.await("GAME_SNAPSHOT");
        return client;
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("accepts a valid token in the first frame")
        void authenticates() throws Exception {
            try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
                client.send(ClientMessage.AUTH, new Payloads.Auth(whiteToken));

                Map<String, Object> payload = client.payloadOf(client.await("AUTH_OK"));
                assertThat(payload.get("username")).isEqualTo(white.username());
            }
        }

        @Test
        @DisplayName("rejects a forged token and closes the socket")
        void rejectsForgedToken() throws Exception {
            try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
                client.send(ClientMessage.AUTH, new Payloads.Auth(
                        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhdHRhY2tlciJ9.forged"));

                client.await("AUTH_FAILED");
                // Closing rather than allowing retries: a socket that can keep guessing
                // is an unrate-limited brute-force channel.
                client.assertClosedWithin(2_000);
            }
        }

        /**
         * The pre-auth window is unavoidable because the browser cannot set handshake
         * headers (ADR-009). Bounding it is what stops it being a socket-exhaustion vector.
         */
        @Test
        @DisplayName("closes a socket that never authenticates")
        void closesSilentSocket() throws Exception {
            try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
                assertThat(client.isOpen()).isTrue();
                client.assertClosedWithin(4_000);
            }
        }

        @Test
        @DisplayName("refuses every command before AUTH")
        void refusesCommandsBeforeAuth() throws Exception {
            try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
                client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));

                Map<String, Object> error = client.payloadOf(client.await("ERROR"));
                assertThat(error.get("code")).isEqualTo("UNAUTHENTICATED");
            }
        }

        @Test
        @DisplayName("refuses to subscribe to someone else's game")
        void refusesForeignGame() throws Exception {
            User stranger = registrar.register("stranger" + UUID.randomUUID().toString().substring(0, 8),
                    UUID.randomUUID() + "@example.com", "correct-horse-battery");
            String strangerToken = jwt.issueAccessToken(stranger.id(), stranger.username());

            try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
                client.send(ClientMessage.AUTH, new Payloads.Auth(strangerToken));
                client.await("AUTH_OK");
                client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));

                // A valid token proves who you are, not that you may watch this game.
                Map<String, Object> error = client.payloadOf(client.await("ERROR"));
                assertThat(error.get("code")).isEqualTo("NOT_A_PLAYER");
            }
        }
    }

    @Nested
    @DisplayName("gameplay")
    class Gameplay {

        @Test
        @DisplayName("delivers a snapshot on subscribe")
        void sendsSnapshot() throws Exception {
            try (TestWebSocketClient client = new TestWebSocketClient(json).connect(port)) {
                client.send(ClientMessage.AUTH, new Payloads.Auth(whiteToken));
                client.await("AUTH_OK");
                client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));

                Map<String, Object> snapshot = client.payloadOf(client.await("GAME_SNAPSHOT"));
                assertThat(snapshot.get("ply")).isEqualTo(0);
                assertThat(snapshot.get("sideToMove")).isEqualTo("WHITE");
                assertThat(snapshot.get("yourSide")).isEqualTo("WHITE");
                assertThat((java.util.List<?>) snapshot.get("legalMoves")).hasSize(20);
            }
        }

        /**
         * The property the whole phase exists for: a move committed by one client reaches
         * the other without either polling.
         */
        @Test
        @DisplayName("a move reaches both players")
        void broadcastsMoves() throws Exception {
            try (TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken);
                 TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {

                whiteClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 0, "e2", "e4", null));

                Map<String, Object> toWhite = whiteClient.payloadOf(whiteClient.await("MOVE_MADE"));
                Map<String, Object> toBlack = blackClient.payloadOf(blackClient.await("MOVE_MADE"));

                assertThat(toWhite.get("san")).isEqualTo("e4");
                assertThat(toWhite.get("ply")).isEqualTo(1);
                // The mover learns the outcome from the same broadcast as the opponent,
                // not a private reply — so both observe identical state by construction.
                assertThat(toBlack).isEqualTo(toWhite);
            }
        }

        /**
         * Guards a bug that shipped: the client cleared its legal moves on every
         * MOVE_MADE and nothing refilled them, so after one move a player's board accepted
         * no input at all. The server is the only party that can compute these — the
         * client has no rules engine — so they have to travel with the event.
         */
        @Test
        @DisplayName("a move event carries the legal moves for the new position")
        void moveCarriesNextLegalMoves() throws Exception {
            try (TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken);
                 TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {

                whiteClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 0, "e2", "e4", null));

                Map<String, Object> toBlack = blackClient.payloadOf(blackClient.await("MOVE_MADE"));

                // Typed, not a wildcard. `List<?>` gives AssertJ a capture type, so
                // contains(String) has nothing to match against — the cast has to name the
                // element type for the assertion to mean anything.
                @SuppressWarnings("unchecked")
                List<String> legalReplies = (List<String>) toBlack.get("legalMoves");

                assertThat(legalReplies)
                        .as("black must be able to reply without waiting for a snapshot")
                        .hasSize(20)
                        .contains("e7e5");
            }
        }

        @Test
        @DisplayName("a terminal move carries no legal moves")
        void terminalMoveCarriesNoLegalMoves() throws Exception {
            try (TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken)) {
                // Fool's mate: 1.f3 e5 2.g4 Qh4#
                for (String[] move : new String[][]{{"f2", "f3"}, {"e7", "e5"}, {"g2", "g4"}}) {
                    gameService.submitMove(game.id(),
                            move[0].equals("e7") ? black.id() : white.id(),
                            new com.chessplatform.game.SubmitMoveCommand(
                                    UUID.randomUUID(),
                                    games.findById(game.id()).orElseThrow().ply(),
                                    com.chessplatform.chess.MoveIntent.of(move[0], move[1])));
                }
                whiteClient.await("MOVE_MADE");

                gameService.submitMove(game.id(), black.id(),
                        new com.chessplatform.game.SubmitMoveCommand(
                                UUID.randomUUID(), 3,
                                com.chessplatform.chess.MoveIntent.of("d8", "h4")));

                Map<String, Object> mate = whiteClient.payloadOf(whiteClient.await("GAME_FINISHED"));
                assertThat(mate.get("termination")).isEqualTo("CHECKMATE");
                assertThat(mate.get("result")).isEqualTo("BLACK_WIN");
            }
        }

        @Test
        @DisplayName("rejects an illegal move without disturbing the game")
        void rejectsIllegalMove() throws Exception {
            try (TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken)) {
                whiteClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 0, "e2", "e5", null));

                Map<String, Object> error = whiteClient.payloadOf(whiteClient.await("ERROR"));
                assertThat(error.get("code")).isEqualTo("ILLEGAL_MOVE");
                assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).isEmpty();
            }
        }

        /**
         * The limiter runs before the move pipeline, so a flood is refused before it costs a
         * database transaction. This context's bucket is 5 per 10 s (see properties), so
         * refill during the burst is negligible and the split is exact.
         */
        @Test
        @DisplayName("a flood of moves is cut off with RATE_LIMITED")
        void rateLimitsMoves() throws Exception {
            try (TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {
                for (int i = 0; i < 25; i++) {
                    blackClient.send(ClientMessage.MOVE, new Payloads.Move(
                            game.id(), UUID.randomUUID(), 0, "e7", "e5", null));
                }
                java.util.List<Object> codes = new java.util.ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    codes.add(blackClient.payloadOf(blackClient.await("ERROR")).get("code"));
                }
                assertThat(codes.subList(0, 5)).containsOnly("NOT_YOUR_TURN");
                assertThat(codes.subList(5, 25))
                        .as("everything past the bucket is refused before the pipeline")
                        .containsOnly("RATE_LIMITED");
            }
        }

        @Test
        @DisplayName("rejects a move made out of turn")
        void rejectsOutOfTurn() throws Exception {
            try (TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {
                blackClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 0, "e7", "e5", null));

                assertThat(blackClient.payloadOf(blackClient.await("ERROR")).get("code"))
                        .isEqualTo("NOT_YOUR_TURN");
            }
        }

        @Test
        @DisplayName("announces the end of the game to both players")
        void broadcastsGameEnd() throws Exception {
            // Both players move first: since 3.2 a resignation before that is an abort.
            playDirectly(white, 0, "e2", "e4");
            playDirectly(black, 1, "e7", "e5");

            try (TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken);
                 TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {

                blackClient.send(ClientMessage.RESIGN, new Payloads.Resign(game.id()));

                Map<String, Object> toWhite = whiteClient.payloadOf(whiteClient.await("GAME_FINISHED"));
                assertThat(toWhite.get("status")).isEqualTo("FINISHED");
                assertThat(toWhite.get("result")).isEqualTo("WHITE_WIN");
                assertThat(toWhite.get("termination")).isEqualTo("RESIGNATION");
                assertThat(blackClient.payloadOf(blackClient.await("GAME_FINISHED")))
                        .isEqualTo(toWhite);
            }
        }

        /**
         * The only test in the suite that relies on the <em>scheduled</em> sweeper rather
         * than calling it: nobody sends anything, and the abort still reaches both players.
         * That is the whole claim of the design — a game nobody touches is still ended, by
         * a background job, and the ending is still pushed — so it is tested end to end.
         *
         * <p>Also guards the null result: the broadcaster used to call
         * {@code result().name()} unconditionally, which for an abort would have thrown in
         * the AFTER_COMMIT listener, after the abort had committed, with no client told.
         */
        @Test
        @DisplayName("a game nobody starts is aborted by the sweeper, and both players are told")
        void sweeperAbortReachesBothPlayers() throws Exception {
            try (TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken);
                 TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {

                // Push the game's start an hour into the past. The sweeper runs every
                // second, so the frame arrives well inside await()'s five-second budget.
                jdbc.update("""
                        UPDATE games
                           SET last_move_at  = now() - INTERVAL '1 hour',
                               turn_deadline = now() - INTERVAL '1 minute'
                         WHERE id = ?
                        """, game.id());

                Map<String, Object> toWhite = whiteClient.payloadOf(whiteClient.await("GAME_FINISHED"));
                assertThat(toWhite.get("status")).isEqualTo("ABORTED");
                assertThat(toWhite.get("result")).isNull();
                assertThat(toWhite.get("termination")).isEqualTo("ABANDONED");
                assertThat(blackClient.payloadOf(blackClient.await("GAME_FINISHED")))
                        .isEqualTo(toWhite);
            }
        }

        @Test
        @DisplayName("answers PING with PONG")
        void heartbeat() throws Exception {
            try (TestWebSocketClient client = connectedAndSubscribed(whiteToken)) {
                client.send(ClientMessage.PING, null);
                assertThat(client.await("PONG")).isNotNull();
            }
        }
    }

    /**
     * Matchmaking over the socket (ADR-016). The scheduled matchmaker is live in this
     * context, so a MATCH_FOUND here is the real path: seek -> Valkey queue -> scheduled
     * pairing -> game committed -> MatchFound event -> notifier -> socket.
     */
    @Nested
    @DisplayName("matchmaking")
    class Matchmaking {

        private User alice;
        private User bob;
        private String aliceToken;
        private String bobToken;

        @BeforeEach
        void freshPlayers() {
            // Not the outer white/black: they already have an active game, and one game at
            // a time is a rule.
            alice = registrar.register("alice" + UUID.randomUUID().toString().substring(0, 8),
                    UUID.randomUUID() + "@example.com", "correct-horse-battery");
            bob = registrar.register("bob" + UUID.randomUUID().toString().substring(0, 8),
                    UUID.randomUUID() + "@example.com", "correct-horse-battery");
            aliceToken = jwt.issueAccessToken(alice.id(), alice.username());
            bobToken = jwt.issueAccessToken(bob.id(), bob.username());
        }

        @AfterEach
        void clearQueue() {
            java.util.Set<String> keys = valkey.keys("mm:*");
            if (keys != null && !keys.isEmpty()) {
                valkey.delete(keys);
            }
        }

        private TestWebSocketClient authenticated(String token) throws Exception {
            TestWebSocketClient client = new TestWebSocketClient(json).connect(port);
            client.send(ClientMessage.AUTH, new Payloads.Auth(token));
            client.await("AUTH_OK");
            return client;
        }

        private Map<String, Object> seek(TestWebSocketClient client) {
            client.send(ClientMessage.SEEK, new Payloads.Seek(300, 3));
            return client.payloadOf(client.await("SEEK_STATUS"));
        }

        @Test
        @DisplayName("two seeking sockets are both told the same game, with opposite colours")
        void pairsOverTheSocket() throws Exception {
            try (TestWebSocketClient a = authenticated(aliceToken);
                 TestWebSocketClient b = authenticated(bobToken)) {
                assertThat(seek(a).get("status")).isEqualTo("QUEUED");
                assertThat(seek(b).get("status")).isEqualTo("QUEUED");

                Map<String, Object> toAlice = a.payloadOf(a.await("MATCH_FOUND"));
                Map<String, Object> toBob = b.payloadOf(b.await("MATCH_FOUND"));

                assertThat(toAlice.get("gameId")).isEqualTo(toBob.get("gameId"));
                assertThat(java.util.Set.of(toAlice.get("yourSide"), toBob.get("yourSide")))
                        .containsExactlyInAnyOrder("WHITE", "BLACK");
                assertThat(toAlice.get("initialSeconds")).isEqualTo(300);

                // And the game is real: subscribing gets a snapshot of it.
                a.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(
                        UUID.fromString((String) toAlice.get("gameId"))));
                assertThat(a.payloadOf(a.await("GAME_SNAPSHOT")).get("ply")).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("a repeated SEEK is a heartbeat; CANCEL_SEEK leaves; a second cancel has nothing to do")
        void seekAndCancel() throws Exception {
            try (TestWebSocketClient a = authenticated(aliceToken)) {
                assertThat(seek(a).get("status")).isEqualTo("QUEUED");
                assertThat(seek(a).get("status")).isEqualTo("QUEUED");

                a.send(ClientMessage.CANCEL_SEEK, null);
                assertThat(a.payloadOf(a.await("SEEK_STATUS")).get("status")).isEqualTo("CANCELLED");
                a.send(ClientMessage.CANCEL_SEEK, null);
                assertThat(a.payloadOf(a.await("SEEK_STATUS")).get("status")).isEqualTo("NOT_SEEKING");
            }
        }

        @Test
        @DisplayName("closing the seeking socket leaves the queue at once, not when the TTL lapses")
        void closeCancels() throws Exception {
            TestWebSocketClient a = authenticated(aliceToken);
            seek(a);
            assertThat(valkey.hasKey("mm:seek:" + alice.id())).isTrue();

            a.close();

            long deadline = System.currentTimeMillis() + 3_000;
            while (Boolean.TRUE.equals(valkey.hasKey("mm:seek:" + alice.id()))
                   && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(valkey.hasKey("mm:seek:" + alice.id()))
                    .as("the seek must be cancelled by the close, well inside its 45 s TTL")
                    .isFalse();
        }

        /**
         * The pull half of delivery. A socket that was not connected when MATCH_FOUND was
         * pushed is told on its next AUTH_OK — until it opens the game, which acknowledges
         * the match so that later connections are not sent back to it.
         */
        @Test
        @DisplayName("a missed match is re-sent after AUTH_OK, until the game is opened")
        void reconnectRecoversMatch() throws Exception {
            Object gameId;
            try (TestWebSocketClient a = authenticated(aliceToken);
                 TestWebSocketClient b = authenticated(bobToken)) {
                seek(a);
                seek(b);
                gameId = a.payloadOf(a.await("MATCH_FOUND")).get("gameId");
                b.await("MATCH_FOUND");
            }

            try (TestWebSocketClient again = authenticated(aliceToken)) {
                Map<String, Object> recovered = again.payloadOf(again.await("MATCH_FOUND"));
                assertThat(recovered.get("gameId")).isEqualTo(gameId);

                again.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(UUID.fromString((String) gameId)));
                again.await("GAME_SNAPSHOT");
            }

            try (TestWebSocketClient later = authenticated(aliceToken)) {
                assertThat(later.typesUntilPong())
                        .as("opening the game acknowledged it")
                        .doesNotContain("MATCH_FOUND");
            }
        }

        @Test
        @DisplayName("a time control without a queue is refused with the REST error vocabulary")
        void unsupportedTimeControl() throws Exception {
            try (TestWebSocketClient a = authenticated(aliceToken)) {
                a.send(ClientMessage.SEEK, new Payloads.Seek(420, 7));
                assertThat(a.payloadOf(a.await("ERROR")).get("code")).isEqualTo("UNSUPPORTED_TIME_CONTROL");

                a.send(ClientMessage.SEEK, new Payloads.Seek(null, 3));
                assertThat(a.payloadOf(a.await("ERROR")).get("code")).isEqualTo("VALIDATION_FAILED");
            }
        }
    }

    @Nested
    @DisplayName("reconnection")
    class Reconnection {

        /**
         * The payoff for keeping zero game state in memory.
         *
         * <p>A client drops mid-game, misses moves entirely, reconnects — and is correct
         * again from one snapshot, with no replay buffer and no per-client cursor
         * (ADR-007). In Milestone 2.2 the reconnect will be able to land on a different
         * instance and this test will be unchanged, because nothing it relies on is local.
         */
        @Test
        @DisplayName("a reconnecting client is restored from a snapshot, including moves it missed")
        void restoresAfterReconnect() throws Exception {
            TestWebSocketClient whiteClient = connectedAndSubscribed(whiteToken);
            try (TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {

                whiteClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 0, "e2", "e4", null));
                blackClient.await("MOVE_MADE");

                // White vanishes, and misses black's reply entirely.
                whiteClient.close();
                blackClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 1, "e7", "e5", null));
                blackClient.await("MOVE_MADE");

                try (TestWebSocketClient reconnected = connectedAndSubscribedFresh(whiteToken)) {
                    Map<String, Object> snapshot = reconnected.payloadOf(reconnected.lastSnapshot());
                    assertThat(snapshot.get("ply"))
                            .as("the snapshot includes the move made while disconnected")
                            .isEqualTo(2);
                    assertThat(snapshot.get("sideToMove")).isEqualTo("WHITE");
                    assertThat(snapshot.get("lastMoveUci")).isEqualTo("e7e5");
                    assertThat(snapshot.get("moves"))
                            .as("the move list is restored too, not only the board")
                            .isEqualTo(List.of("e4", "e5"));

                    // And it can carry straight on playing.
                    reconnected.send(ClientMessage.MOVE, new Payloads.Move(
                            game.id(), UUID.randomUUID(), 2, "g1", "f3", null));
                    assertThat(reconnected.payloadOf(reconnected.await("MOVE_MADE")).get("san"))
                            .isEqualTo("Nf3");
                }
            }
        }

        /**
         * The server half of the client's conflict handling: a move against a stale ply is
         * refused with CONFLICT, and re-subscribing on the SAME socket — no reconnect —
         * returns a snapshot the client can adopt. The client relies on both.
         */
        @Test
        @DisplayName("a stale move is refused with CONFLICT, and re-subscribing resyncs the same socket")
        void resyncsAfterConflict() throws Exception {
            try (TestWebSocketClient blackClient = connectedAndSubscribed(blackToken)) {
                playDirectly(white, 0, "e2", "e4");
                blackClient.await("MOVE_MADE");

                // Black's client still believes the game is at ply 0.
                blackClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 0, "e7", "e5", null));
                assertThat(blackClient.payloadOf(blackClient.await("ERROR")).get("code"))
                        .isEqualTo("CONFLICT");

                blackClient.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));
                Map<String, Object> snapshot = blackClient.payloadOf(blackClient.await("GAME_SNAPSHOT"));
                assertThat(snapshot.get("ply")).isEqualTo(1);
                assertThat(snapshot.get("moves")).isEqualTo(List.of("e4"));

                // And the resynced client plays on normally.
                blackClient.send(ClientMessage.MOVE, new Payloads.Move(
                        game.id(), UUID.randomUUID(), 1, "e7", "e5", null));
                assertThat(blackClient.payloadOf(blackClient.await("MOVE_MADE")).get("san"))
                        .isEqualTo("e5");
            }
        }

        private TestWebSocketClient connectedAndSubscribedFresh(String token) throws Exception {
            TestWebSocketClient client = new TestWebSocketClient(json).connect(port);
            client.send(ClientMessage.AUTH, new Payloads.Auth(token));
            client.await("AUTH_OK");
            client.send(ClientMessage.SUBSCRIBE, new Payloads.Subscribe(game.id()));
            client.captureSnapshot();
            return client;
        }
    }
}
