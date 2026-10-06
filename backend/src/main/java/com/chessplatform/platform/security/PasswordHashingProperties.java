package com.chessplatform.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sizing of the password-hashing pool ({@link BoundedPasswordEncoder}).
 *
 * @param threads       Platform threads that hash. 0 (the default) means one per processor the JVM
 *                      sees — on a 0.5-vCPU task, one: hashing can then take at most half the CPU
 *                      from the request carrier. More threads than processors only lets hashing
 *                      take a larger share; it cannot make hashes faster.
 * @param queueCapacity Hashes allowed to wait. The latency budget: the worst wait is about
 *                      {@code queueCapacity × hash time ÷ threads}. Beyond it, 503.
 */
@ConfigurationProperties(prefix = "chess.auth.hashing")
public record PasswordHashingProperties(Integer threads, Integer queueCapacity) {

    private static final int DEFAULT_QUEUE_CAPACITY = 16;

    public PasswordHashingProperties {
        if (threads == null) {
            threads = 0;
        }
        if (queueCapacity == null) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
        if (threads < 0) {
            throw new IllegalArgumentException(
                    "chess.auth.hashing.threads must be >= 0 (0 = one per processor); got " + threads);
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException(
                    "chess.auth.hashing.queue-capacity must be >= 0; got " + queueCapacity);
        }
    }

    /** The configured thread count, or one per processor the JVM sees. */
    public int effectiveThreads(int availableProcessors) {
        return threads > 0 ? threads : Math.max(1, availableProcessors);
    }
}
