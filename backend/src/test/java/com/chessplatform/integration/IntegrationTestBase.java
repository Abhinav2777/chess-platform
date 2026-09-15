package com.chessplatform.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared infrastructure for integration tests.
 *
 * <h2>Why a real PostgreSQL and not H2</h2>
 *
 * <p>Because the things being tested are PostgreSQL behaviours. Unique constraint
 * violation semantics, {@code TIMESTAMPTZ} handling, partial indexes, and later
 * {@code FOR UPDATE SKIP LOCKED} either behave differently on H2 or do not exist. A test
 * suite that passes on H2 and fails in production is worse than no suite, because it
 * produces confidence rather than information.
 *
 * <p>It also means the Flyway migrations are exercised on every run. A migration that
 * only executes in production is an untested deployment step.
 *
 * <h2>Singleton container, started once</h2>
 *
 * <p>The container is a static field started in a static initialiser rather than managed
 * by {@code @Testcontainers}/{@code @Container}, which would start and stop it per test
 * class. One container for the whole JVM turns tens of seconds of startup into one.
 * Testcontainers' Ryuk sidecar reaps it when the JVM exits, so nothing leaks.
 *
 * <p>The cost is shared state across classes: tests must clean up after themselves.
 * That is the right trade — an isolated-but-slow suite stops being run.
 *
 * <h2>Why {@code @DynamicPropertySource} and not {@code @ServiceConnection}</h2>
 *
 * <p>{@code @ServiceConnection} is the newer, terser idiom, but it lives in the separate
 * {@code spring-boot-testcontainers} module whose coordinates we have not verified
 * against Boot 4. {@code @DynamicPropertySource} needs nothing beyond the Testcontainers
 * core we already have and behaves identically. Worth revisiting once the dependency is
 * confirmed.
 *
 * <h2>Still no Valkey container, deliberately</h2>
 *
 * <p>Nothing reached from this base class touches Valkey: identity and gameplay run
 * entirely through PostgreSQL, and presence is only written when a WebSocket subscribes.
 * Lettuce connects lazily, so the context starts without it.
 *
 * <p>The realtime tests do start one, because they do subscribe. Starting a container
 * here as well would add seconds to every run to serve no assertion — and short
 * {@code spring.data.redis} timeouts mean that even if something did reach for Valkey, it
 * would degrade in milliseconds rather than stall.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
