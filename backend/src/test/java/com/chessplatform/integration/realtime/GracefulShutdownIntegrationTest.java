package com.chessplatform.integration.realtime;

import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.internal.JwtService;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.realtime.internal.SocketDrain;
import com.chessplatform.realtime.protocol.ClientMessage;
import com.chessplatform.realtime.protocol.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a pod does with its sockets when it is told to stop (Phase 8.1, ADR-024).
 *
 * <p>Each test gets a fresh context: both end with the instance draining or closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Graceful shutdown")
class GracefulShutdownIntegrationTest {

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
        registry.add("chess.matchmaking.scheduler-enabled", () -> "false"); // keep the seek queued
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
    private SocketDrain drain;
    @Autowired
    private ApplicationAvailability availability;
    @Autowired
    private StringRedisTemplate valkey;
    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    @DisplayName("draining: readiness off, every socket closed 1001 with a reconnect hint, seeks kept, newcomers bounced")
    void drain() throws Exception {
        User player = register();
        TestWebSocketClient seeker = authenticated(player);
        seeker.send(ClientMessage.SEEK, new Payloads.Seek(300, 3));
        assertThat(seeker.payloadOf(seeker.await("SEEK_STATUS")).get("status")).isEqualTo("QUEUED");
        TestWebSocketClient idle = authenticated(register());

        drain.stop();

        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        for (TestWebSocketClient client : new TestWebSocketClient[] {seeker, idle}) {
            CloseStatus status = client.awaitCloseStatus(2_000);
            assertThat(status.getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
            assertThat(status.getReason()).contains("reconnect");
        }
        assertThat(valkey.hasKey("mm:seek:" + player.id()))
                .as("a deploy is not the player leaving: the seek survives for their reconnect").isTrue();

        TestWebSocketClient late = new TestWebSocketClient(json).connect(port);
        assertThat(late.awaitCloseStatus(2_000).getCode())
                .as("a connection that reaches a draining instance is sent elsewhere at once")
                .isEqualTo(CloseStatus.GOING_AWAY.getCode());
    }

    /**
     * The drain must be wired into shutdown itself, ahead of Tomcat's own: when the web server
     * stops it closes what is left, without our reason and after the application has started
     * tearing down underneath in-flight work. (Before SocketDrain existed: 1006.)
     *
     * <p>{@code stop()} rather than {@code close()}: both run the same phase-ordered lifecycle
     * stop — web server last — but Spring 7's test-context cache restarts a stopped context
     * after the test, and cannot restart a closed one.
     */
    @Test
    @DisplayName("closing the application drains the sockets before the web server stops")
    void shutdownRunsTheDrain() throws Exception {
        TestWebSocketClient client = authenticated(register());

        context.stop();

        CloseStatus status = client.awaitCloseStatus(5_000);
        assertThat(status.getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        assertThat(status.getReason()).contains("reconnect");
    }

    private User register() {
        return registrar.register("gs" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
    }

    private TestWebSocketClient authenticated(User user) throws Exception {
        TestWebSocketClient client = new TestWebSocketClient(json).connect(port);
        client.send(ClientMessage.AUTH, new Payloads.Auth(jwt.issueAccessToken(user.id(), user.username())));
        client.await("AUTH_OK");
        return client;
    }
}
