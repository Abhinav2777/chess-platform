package com.chessplatform.integration.valkey;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two Valkey budgets that the first ECS deployment showed must not be one (7.5):
 *
 * <ul>
 *   <li>Connection initialisation — once per connection: TCP, TLS handshake, HELLO. On a
 *       0.25-vCPU Fargate task the first TLS handshake took over a second, and the task died
 *       with "Connection initialization timed out after 1 second(s)".</li>
 *   <li>A command — on every request. Stays at 1 s, the fail-fast bound ADR-004/ADR-018 rely on.</li>
 * </ul>
 *
 * Valkey is reached through {@link SlowTcpProxy}, which delays every connection's first bytes by
 * two seconds and can stall entirely.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Valkey timeouts")
class ValkeyTimeoutsIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);
    static final SlowTcpProxy PROXY;

    static {
        POSTGRES.start();
        VALKEY.start();
        try {
            PROXY = new SlowTcpProxy(VALKEY.getHost(), VALKEY.getMappedPort(6379), Duration.ofSeconds(2));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", PROXY::port);
        registry.add("chess.clock.sweeper-enabled", () -> "false");
        registry.add("chess.matchmaking.scheduler-enabled", () -> "false");
    }

    @AfterAll
    static void closeProxy() throws IOException {
        PROXY.close();
    }

    @Autowired
    private StringRedisTemplate valkey;

    @Test
    @Order(1)
    @DisplayName("a connection whose handshake takes 2 s is still made")
    void slowHandshakeConnects() {
        valkey.opsForValue().set("timeouts:probe", "ok");

        assertThat(valkey.opsForValue().get("timeouts:probe")).isEqualTo("ok");
    }

    @Test
    @Order(2)
    @DisplayName("once connected, a command against a silent server still fails in about a second")
    void commandsStillFailFast() {
        valkey.opsForValue().get("timeouts:probe");   // connection established and warm
        PROXY.stall();

        long started = System.nanoTime();
        assertThatThrownBy(() -> valkey.opsForValue().get("timeouts:probe"))
                .hasRootCauseInstanceOf(io.lettuce.core.RedisCommandTimeoutException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMs).as("command timeout, not the 10 s connection budget")
                .isBetween(800L, 2_500L);
    }
}
