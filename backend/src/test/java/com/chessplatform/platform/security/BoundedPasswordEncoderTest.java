package com.chessplatform.platform.security;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hashing pool: hashes run on platform threads, the queue is bounded, and a full queue is a
 * 503 the caller can retry — not an unbounded wait (Phase 9.4).
 */
@DisplayName("Bounded password encoder")
class BoundedPasswordEncoderTest {

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private BoundedPasswordEncoder encoder;

    @AfterEach
    void close() {
        if (encoder != null) {
            encoder.close();
        }
    }

    @Test
    @DisplayName("hashes on a platform thread of the pool, never on the caller's virtual thread")
    void platformThread() throws Exception {
        AtomicReference<Thread> hashedOn = new AtomicReference<>();
        encoder = new BoundedPasswordEncoder(recording(hashedOn), 1, 4, metrics);

        AtomicReference<String> hash = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> hash.set(encoder.encode("secret")));
        caller.join(5_000);

        assertThat(hash.get()).isEqualTo("hashed:secret");
        assertThat(hashedOn.get().isVirtual()).isFalse();
        assertThat(hashedOn.get().getName()).startsWith("password-hash-");
    }

    @Test
    @DisplayName("matches delegates and returns the delegate's answer")
    void matches() {
        encoder = new BoundedPasswordEncoder(recording(new AtomicReference<>()), 1, 4, metrics);

        assertThat(encoder.matches("secret", "hashed:secret")).isTrue();
        assertThat(encoder.matches("wrong", "hashed:secret")).isFalse();
    }

    @Test
    @DisplayName("a full queue refuses at once with SERVER_BUSY and a Retry-After, and counts it")
    void fullQueue() throws Exception {
        CountDownLatch hashing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        encoder = new BoundedPasswordEncoder(blocking(hashing, release), 1, 1, metrics);

        // One hash occupies the only thread, one waits in the only queue slot.
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        Thread running = Thread.ofVirtual().start(() -> first.set(encoder.encode("first")));
        assertThat(hashing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread queued = Thread.ofVirtual().start(() -> second.set(encoder.encode("second")));
        awaitQueued(1);

        assertThatThrownBy(() -> encoder.encode("third"))
                .isInstanceOfSatisfying(DomainException.Unavailable.class, busy -> {
                    assertThat(busy.code()).isEqualTo(ErrorCode.SERVER_BUSY);
                    assertThat(busy.retryAfter()).contains(Duration.ofSeconds(1));
                });
        assertThat(metrics.counter("chess.auth.hashing.rejected").count()).isEqualTo(1.0);

        // The refused request did not disturb the admitted ones. Asserted on their results, not on
        // executor.completed: the pool counts a task only after its caller has already been woken,
        // so that counter can lag a join (seen in CI: 1.0 for 2).
        release.countDown();
        running.join(5_000);
        queued.join(5_000);
        assertThat(first.get()).isEqualTo("hashed:first");
        assertThat(second.get()).isEqualTo("hashed:second");
    }

    @Test
    @DisplayName("the delegate's own exception reaches the caller unchanged")
    void delegateFailure() {
        PasswordEncoder failing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                throw new IllegalArgumentException("rawPassword cannot be null");
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return false;
            }
        };
        encoder = new BoundedPasswordEncoder(failing, 1, 1, metrics);

        assertThatThrownBy(() -> encoder.encode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawPassword cannot be null");
    }

    @Test
    @DisplayName("threads 0 means one per processor the JVM sees, and never fewer than one")
    void defaultThreads() {
        PasswordHashingProperties auto = new PasswordHashingProperties(null, null);

        assertThat(auto.effectiveThreads(1)).isEqualTo(1);
        assertThat(auto.effectiveThreads(4)).isEqualTo(4);
        assertThat(auto.effectiveThreads(0)).isEqualTo(1);
        assertThat(new PasswordHashingProperties(2, null).effectiveThreads(8)).isEqualTo(2);
        assertThat(auto.queueCapacity()).isEqualTo(16);
    }

    @Test
    @DisplayName("negative sizes fail at startup, naming the property")
    void invalidSizes() {
        assertThatThrownBy(() -> new PasswordHashingProperties(-1, 16))
                .hasMessageContaining("chess.auth.hashing.threads");
        assertThatThrownBy(() -> new PasswordHashingProperties(0, -1))
                .hasMessageContaining("chess.auth.hashing.queue-capacity");
    }

    private void awaitQueued(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (metrics.get("executor.queued").tag("name", BoundedPasswordEncoder.EXECUTOR_NAME)
                       .gauge().value() < expected) {
            assertThat(System.nanoTime()).as("hash queued in time").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static PasswordEncoder recording(AtomicReference<Thread> hashedOn) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                hashedOn.set(Thread.currentThread());
                return "hashed:" + raw;
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return encoded.equals("hashed:" + raw);
            }
        };
    }

    private static PasswordEncoder blocking(CountDownLatch started, CountDownLatch release) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "hashed:" + raw;
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return false;
            }
        };
    }
}
