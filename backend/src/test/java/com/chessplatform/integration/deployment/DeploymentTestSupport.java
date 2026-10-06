package com.chessplatform.integration.deployment;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

/**
 * A real Tomcat on a random port, with the {@code aws} profile. Not MockMvc: what these
 * tests exercise — Tomcat's RemoteIpValve, Spring's WebSocket origin check, static resource
 * serving and the welcome-page forward — happens in the servlet container, which MockMvc
 * skips entirely.
 */
abstract class DeploymentTestSupport {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        VALKEY.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("chess.clock.sweeper-enabled", () -> "false");
        registry.add("chess.matchmaking.scheduler-enabled", () -> "false");
        // The aws profile moves actuator to its own port (8081); random here, like the server.
        registry.add("management.server.port", () -> "0");
        // The aws profile has no development fallback for the signing key (10.1); in AWS it comes
        // from Secrets Manager.
        registry.add("chess.auth.jwt-secret", () -> "deployment-test-signing-key-at-least-32-bytes");
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    StringRedisTemplate valkey;

    @AfterEach
    void clearRateLimitBuckets() {
        Set<String> keys = valkey.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            valkey.delete(keys);
        }
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postJson(String path, String json, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A login that fails on the password — it still spends a per-IP token, which is the point. */
    int failedLogin(String username, String forwardedFor) throws Exception {
        return postJson("/api/auth/login",
                "{\"username\":\"%s\",\"password\":\"wrong-password-123\"}".formatted(username),
                forwardedFor).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
