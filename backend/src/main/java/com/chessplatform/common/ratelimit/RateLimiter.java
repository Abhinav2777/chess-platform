package com.chessplatform.common.ratelimit;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.common.resilience.ValkeyGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

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
 * availability was chosen — bcrypt at cost 12 still makes each guess cost
 * ~250 ms of server time.
 *
 * <p>Failing open alone is not enough: every call would still wait out the Valkey timeout
 * first. Calls go through {@link ValkeyGuard}, the instance-wide circuit — after one failure
 * nothing on this instance asks Valkey for five seconds. The limiter began with a private
 * circuit (4.2); it moved to the shared guard once tracing showed the fanout publisher and
 * presence paying the same timeout on the same request.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    private static final String KEY_PREFIX = "rl:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = tokenBucket();

    private final StringRedisTemplate valkey;
    private final RateLimitProperties properties;
    private final ValkeyGuard guard;
    private final Map<RateLimit, Counter> rejected = new EnumMap<>(RateLimit.class);

    public RateLimiter(StringRedisTemplate valkey, RateLimitProperties properties,
                       ValkeyGuard guard, MeterRegistry metrics) {
        this.valkey = valkey;
        this.properties = properties;
        this.guard = guard;
        for (RateLimit limit : RateLimit.values()) {
            rejected.put(limit, Counter.builder("chess.ratelimit.rejected")
                    .tag("limit", limit.key())
                    .description("Requests refused by a rate limit")
                    .register(metrics));
        }
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
        if (!properties.enabled()) {
            return;
        }
        RateLimitProperties.Policy policy = properties.policyFor(limit);
        // Fail open: null means Valkey was unreachable or the circuit is open.
        List<?> result = guard.call(
                () -> valkey.execute(TOKEN_BUCKET, List.of(KEY_PREFIX + limit.key() + ":" + subject),
                        Integer.toString(policy.capacity()), Double.toString(policy.refillPerMs())),
                () -> null);
        if (result == null) {
            return;
        }
        if (result.size() != 2) {
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
