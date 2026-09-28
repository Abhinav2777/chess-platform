package com.chessplatform.common.ratelimit;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Token-bucket rate limiting in Valkey, shared by every instance.
 *
 * <h2>Why a Lua script and not Bucket4j</h2>
 *
 * <p>Evaluated for Milestone 4.2. Bucket4j's Lettuce integration is built against Lettuce
 * 6.1 while Boot 4.1 ships 7.5, and it wants a native connection outside Spring's managed
 * factory — a second connection lifecycle with its own timeouts, beside the fail-fast ones
 * this project depends on. The bucket itself is twenty lines of Lua on infrastructure the
 * matchmaker already uses. Callers see only {@link #enforce}, so swapping in Bucket4j later
 * touches this class alone.
 *
 * <h2>Failure policy: open, with a short circuit</h2>
 *
 * <p>If Valkey cannot be reached, requests are <strong>allowed</strong>. A rate limiter is a
 * guard, not a dependency: a cache outage must never stop a move (ADR-004), and for login
 * the project owner chose availability — bcrypt at cost 12 still makes each guess cost
 * ~250 ms of server time.
 *
 * <p>Failing open alone is not enough. Every call would still wait out the Valkey timeout
 * before failing open, so an outage would add up to a second to <em>every move</em>. After
 * one failure the limiter stops asking Valkey for {@code circuit-open-for} (5 s) and allows
 * everything; the next call after that probes again. An outage costs one slow request per
 * five seconds per instance, not one per request.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String KEY_PREFIX = "rl:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = tokenBucket();

    private final StringRedisTemplate valkey;
    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<RateLimit, Counter> rejected = new EnumMap<>(RateLimit.class);
    private final Counter unavailable;

    /** Epoch millis until which Valkey is not consulted. Racy by design: a few extra probes are harmless. */
    private volatile long circuitOpenUntil;

    public RateLimiter(StringRedisTemplate valkey, RateLimitProperties properties,
                       Clock clock, MeterRegistry metrics) {
        this.valkey = valkey;
        this.properties = properties;
        this.clock = clock;
        for (RateLimit limit : RateLimit.values()) {
            rejected.put(limit, Counter.builder("chess.ratelimit.rejected")
                    .tag("limit", limit.key())
                    .description("Requests refused by a rate limit")
                    .register(metrics));
        }
        this.unavailable = Counter.builder("chess.ratelimit.unavailable")
                .description("Checks skipped because Valkey was unreachable (failed open)")
                .register(metrics);
    }

    /**
     * Takes one token for {@code subject}, or throws.
     *
     * @param subject who is being limited — a user id, a client IP, a lower-cased username.
     *                Never trusted for anything but choosing a bucket.
     * @throws DomainException.RateLimited when the bucket is empty; carries how long until
     *                                     the next token, for {@code Retry-After}
     */
    public void enforce(RateLimit limit, String subject) {
        if (!properties.enabled() || clock.millis() < circuitOpenUntil) {
            return;
        }
        RateLimitProperties.Policy policy = properties.policyFor(limit);
        List<?> result;
        try {
            result = valkey.execute(TOKEN_BUCKET, List.of(KEY_PREFIX + limit.key() + ":" + subject),
                    Integer.toString(policy.capacity()), Double.toString(policy.refillPerMs()));
        } catch (DataAccessException valkeyUnavailable) {
            circuitOpenUntil = clock.millis() + properties.circuitOpenFor().toMillis();
            unavailable.increment();
            log.warn("Rate limiter cannot reach Valkey; allowing requests for {}: {}",
                    properties.circuitOpenFor(), valkeyUnavailable.toString());
            return;
        }
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("unexpected token-bucket result: " + result);
        }
        if (Long.parseLong(result.get(0).toString()) == 1) {
            return;
        }
        rejected.get(limit).increment();
        Duration retryAfter = Duration.ofMillis(Math.max(1, Long.parseLong(result.get(1).toString())));
        throw new DomainException.RateLimited("Too many requests. Try again shortly.", retryAfter);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> tokenBucket() {
        DefaultRedisScript script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("ratelimit/token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}
