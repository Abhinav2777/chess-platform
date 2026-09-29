package com.chessplatform.integration.ratelimit;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.ratelimit.RateLimit;
import com.chessplatform.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Rate limits against a real Valkey, with the production-shaped limits from
 * {@code application.yml} (the {@code local} profile's generous ones are not active here).
 */
@SpringBootTest
@DisplayName("Rate limiting")
class RateLimitIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        VALKEY.start();
    }

    /**
     * Its own context rather than IntegrationTestBase: that one has no web context (MockMvc
     * needs one) and switches the limiter off. The scheduled jobs are off here too, since
     * nothing in this class needs them.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("chess.clock.sweeper-enabled", () -> "false");
        registry.add("chess.matchmaking.scheduler-enabled", () -> "false");
    }

    @Autowired
    private RateLimiter limiter;
    @Autowired
    private StringRedisTemplate valkey;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @AfterEach
    void clearBuckets() {
        Set<String> keys = valkey.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            valkey.delete(keys);
        }
    }

    @Test
    @DisplayName("a burst up to capacity is allowed, the next is refused with how long to wait")
    void burstThenRefuse() {
        for (int i = 0; i < 5; i++) {
            limiter.enforce(RateLimit.LOGIN_USER, "magnus");
        }

        assertThatThrownBy(() -> limiter.enforce(RateLimit.LOGIN_USER, "magnus"))
                .isInstanceOfSatisfying(DomainException.RateLimited.class, refused ->
                        // 5 per minute refills one token every 12 s.
                        assertThat(refused.retryAfter().toMillis()).isBetween(1L, 12_000L));
    }

    @Test
    @DisplayName("subjects and limits have separate buckets")
    void bucketsAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.enforce(RateLimit.LOGIN_USER, "magnus");
        }

        assertThatCode(() -> limiter.enforce(RateLimit.LOGIN_USER, "hikaru")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.enforce(RateLimit.LOGIN_IP, "magnus")).doesNotThrowAnyException();
    }

    /** 20 per second refills one token every 50 ms; the wait is real but short. */
    @Test
    @DisplayName("an empty bucket refills over time")
    void refills() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            limiter.enforce(RateLimit.MOVE, "player");
        }
        assertThatThrownBy(() -> limiter.enforce(RateLimit.MOVE, "player"))
                .isInstanceOf(DomainException.RateLimited.class);

        Thread.sleep(120);

        assertThatCode(() -> limiter.enforce(RateLimit.MOVE, "player")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an idle bucket expires rather than living in Valkey forever")
    void bucketsExpire() {
        limiter.enforce(RateLimit.MOVE, "player");

        Long ttl = valkey.getExpire("rl:move:player");
        assertThat(ttl).as("TTL in seconds — full refill (1 s) plus a second").isBetween(1L, 3L);
    }

    /**
     * Over HTTP: the sixth login for one username inside a minute is a 429 with a
     * Retry-After, whatever the password — wrong-password attempts are exactly what the
     * limit exists to slow down, so they are counted like any other.
     */
    @Test
    @DisplayName("login returns 429 with Retry-After once the per-username limit is spent")
    void loginReturns429() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain).build();

        for (int i = 0; i < 5; i++) {
            assertThat(login(mvc).getResponse().getStatus()).as("attempt %d", i + 1).isEqualTo(401);
        }
        MvcResult refused = login(mvc);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotNull()
                .satisfies(seconds -> assertThat(Long.parseLong(seconds)).isBetween(1L, 12L));
        assertThat(refused.getResponse().getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    }

    private MvcResult login(MockMvc mvc) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-here\",\"password\":\"wrong-password-123\"}"))
                .andReturn();
    }
}
