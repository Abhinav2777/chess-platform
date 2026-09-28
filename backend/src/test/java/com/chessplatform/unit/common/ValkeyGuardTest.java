package com.chessplatform.unit.common;

import com.chessplatform.common.resilience.ValkeyGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The instance-wide circuit, with a clock the test moves. */
@DisplayName("ValkeyGuard")
class ValkeyGuardTest {

    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ValkeyGuard guard = new ValkeyGuard(clock, Duration.ofSeconds(5), metrics);
    private final AtomicInteger attempts = new AtomicInteger();

    private String failing() {
        attempts.incrementAndGet();
        throw new QueryTimeoutException("Redis command timed out");
    }

    @Test
    @DisplayName("passes results through while Valkey answers")
    void healthy() {
        assertThat(guard.call(() -> "value", () -> "fallback")).isEqualTo("value");
        assertThat(guard.available()).isTrue();
    }

    @Test
    @DisplayName("a failure returns the fallback and stops further attempts for the window")
    void opensOnFailure() {
        assertThat(guard.call(this::failing, () -> "fallback")).isEqualTo("fallback");
        assertThat(guard.available()).isFalse();

        for (int i = 0; i < 10; i++) {
            assertThat(guard.call(this::failing, () -> "fallback")).isEqualTo("fallback");
        }
        assertThat(attempts).as("only the first call reached Valkey").hasValue(1);
        assertThat(metrics.counter("chess.valkey.calls.skipped").count()).isEqualTo(10);
    }

    @Test
    @DisplayName("after the window the next call probes, and a success closes the circuit")
    void probesAfterWindow() {
        guard.call(this::failing, () -> "fallback");
        clock.advance(Duration.ofSeconds(6));

        assertThat(guard.available()).isTrue();
        assertThat(guard.call(() -> "back", () -> "fallback")).isEqualTo("back");
        assertThat(guard.available()).isTrue();
    }

    @Test
    @DisplayName("one caller's failure spares every other caller the timeout")
    void sharedAcrossCallers() {
        guard.call(this::failing, () -> null);   // e.g. the rate limiter

        AtomicInteger publisherCalls = new AtomicInteger();
        guard.run(publisherCalls::incrementAndGet);   // e.g. the fanout publisher
        assertThat(publisherCalls).hasValue(0);
    }

    @Test
    @DisplayName("exceptions that are not outages propagate — they are bugs, not degradation")
    void bugsPropagate() {
        assertThatThrownBy(() -> guard.call(() -> {
            throw new IllegalStateException("bug");
        }, () -> "fallback")).isInstanceOf(IllegalStateException.class);
        assertThat(guard.available()).as("a bug does not open the circuit").isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
