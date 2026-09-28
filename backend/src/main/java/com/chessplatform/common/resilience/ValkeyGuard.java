package com.chessplatform.common.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * One circuit breaker for every degradable use of Valkey on this instance.
 *
 * <h2>The problem it solves (measured, Milestone 4.3 follow-up)</h2>
 *
 * <p>Valkey is optional by design (ADR-004): fanout, presence, rate limiting and
 * notifications all have a fallback. But a fallback reached only <em>after</em> a timeout
 * still costs the timeout — on every call. With Valkey hung, a move paid it twice in
 * sequence on the socket's thread: the rate limiter before the commit, the fanout publish
 * after it. Traced in a browser, the first move after an outage took 3.9 s to become
 * visible, all of it timeouts.
 *
 * <h2>How</h2>
 *
 * <p>The first {@link DataAccessException} opens the circuit for {@code circuit-open-for}
 * (5 s). While open, every guarded call goes straight to its fallback without touching
 * Valkey. The first call after the window is the probe: if it succeeds, normal service; if
 * not, the window restarts. An outage costs one timeout per window per instance.
 *
 * <p><strong>Per instance, not per caller.</strong> "Valkey is down" is a fact about this
 * instance's connection, so the rate limiter discovering it should spare the publisher,
 * presence and matchmaking from rediscovering it one timeout each.
 *
 * <p>Deliberately minimal: no half-open state machine, no failure-rate thresholds. Those
 * earn their keep when a dependency fails partially; a cache that is either answering or
 * not needs only "stop asking for a few seconds".
 */
@Component
public class ValkeyGuard {

    private static final Logger log = LoggerFactory.getLogger(ValkeyGuard.class);

    private final Clock clock;
    private final long openForMillis;
    private final Counter trips;
    private final Counter skipped;

    /** Epoch millis until which calls are not attempted. Racy by design: a few extra probes are harmless. */
    private volatile long openUntil;

    public ValkeyGuard(Clock clock,
                       @Value("${chess.valkey.circuit-open-for:5s}") Duration openFor,
                       MeterRegistry metrics) {
        this.clock = clock;
        this.openForMillis = openFor.toMillis();
        this.trips = Counter.builder("chess.valkey.circuit.trips")
                .description("Times a Valkey failure opened the circuit")
                .register(metrics);
        this.skipped = Counter.builder("chess.valkey.calls.skipped")
                .description("Valkey calls not attempted because the circuit was open")
                .register(metrics);
    }

    /** False while the circuit is open — callers that fail fast (e.g. 503) can check first. */
    public boolean available() {
        return clock.millis() >= openUntil;
    }

    /**
     * Runs {@code operation}, or {@code fallback} if the circuit is open or the operation
     * fails with a {@link DataAccessException}. Other exceptions propagate: they are bugs,
     * not outages.
     */
    public <T> T call(Supplier<T> operation, Supplier<T> fallback) {
        if (!available()) {
            skipped.increment();
            return fallback.get();
        }
        try {
            return operation.get();
        } catch (DataAccessException unavailable) {
            trip(unavailable);
            return fallback.get();
        }
    }

    public void run(Runnable operation) {
        call(() -> {
            operation.run();
            return null;
        }, () -> null);
    }

    private void trip(DataAccessException cause) {
        boolean wasClosed = available();
        openUntil = clock.millis() + openForMillis;
        trips.increment();
        if (wasClosed) {
            log.warn("Valkey unavailable; degrading for {} ms before probing again: {}",
                    openForMillis, cause.toString());
        }
    }
}
