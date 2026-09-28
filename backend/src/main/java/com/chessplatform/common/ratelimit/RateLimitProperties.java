package com.chessplatform.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;

/**
 * @param enabled         off switch for the whole limiter
 * @param policies        a {@link Policy} for every {@link RateLimit}, keyed by
 *                        {@link RateLimit#key()}; checked at startup so a missing one fails
 *                        the boot rather than the first request
 * @param circuitOpenFor  after a Valkey failure, how long to allow requests without asking
 *                        Valkey at all (see {@link RateLimiter})
 */
@ConfigurationProperties(prefix = "chess.ratelimit")
public record RateLimitProperties(boolean enabled,
                                  Map<String, Policy> policies,
                                  Duration circuitOpenFor) {

    public RateLimitProperties {
        if (policies == null) {
            throw new IllegalArgumentException("chess.ratelimit.policies is required");
        }
        for (RateLimit limit : EnumSet.allOf(RateLimit.class)) {
            if (!policies.containsKey(limit.key())) {
                throw new IllegalArgumentException(
                        "chess.ratelimit.policies." + limit.key() + " is missing");
            }
        }
        if (circuitOpenFor == null || circuitOpenFor.isNegative()) {
            throw new IllegalArgumentException("chess.ratelimit.circuit-open-for must be >= 0");
        }
        policies = Map.copyOf(policies);
    }

    public Policy policyFor(RateLimit limit) {
        return policies.get(limit.key());
    }

    /**
     * {@code capacity} requests at once, refilling at {@code capacity} per {@code period}.
     * "10 per 1m" allows a burst of ten, then one every six seconds.
     */
    public record Policy(int capacity, Duration period) {

        public Policy {
            if (capacity < 1 || period == null || period.toMillis() < 1) {
                throw new IllegalArgumentException("a rate-limit policy needs capacity >= 1 and period >= 1ms");
            }
        }

        double refillPerMs() {
            return (double) capacity / period.toMillis();
        }
    }
}
