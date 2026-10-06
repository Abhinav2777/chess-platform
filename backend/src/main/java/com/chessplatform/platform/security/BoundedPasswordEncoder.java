package com.chessplatform.platform.security;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the deliberately slow password hash on a small pool of <em>platform</em> threads with a
 * bounded queue, and sheds load with a 503 when the queue is full.
 *
 * <h2>Why not just hash on the request's virtual thread (Phase 9.4)</h2>
 *
 * <p>Virtual threads are scheduled cooperatively onto carrier threads, one per available
 * processor, and a virtual thread gives its carrier up only when it blocks. bcrypt never blocks:
 * it is a few hundred milliseconds of pure CPU. On a 0.5-vCPU Fargate task the JVM sees one
 * processor, so there is one carrier — and while a hash runs, <em>no other request on the
 * instance runs at all</em>. A request that holds a pooled connection and is waiting for its
 * query's reply becomes runnable when the reply arrives, then waits behind every queued hash,
 * connection still checked out. A burst of sign-ups emptied the 10-connection pool that way even
 * though no hash ever held a connection itself (docs/perf/optimisation-02.md).
 *
 * <p>Platform threads are preempted by the operating system. Hashing on them, the carrier and the
 * hashing threads share the CPU by time slices, so game traffic keeps running during a burst.
 * The thread count bounds the share hashing can take: {@code n} hashing threads against one
 * carrier take at most {@code n/(n+1)} of the CPU, however many sign-ups arrive.
 *
 * <h2>Why a bounded queue and a 503</h2>
 *
 * <p>Without a bound, a burst queues without limit and every caller waits longer than its client
 * will — work done for nobody. The queue's capacity is the latency budget: the worst wait is about
 * {@code capacity × hash time ÷ threads}. Beyond it, refusing at once with {@code Retry-After} is
 * the honest answer; the caller (a person signing in) retries a second later.
 *
 * <p>The refusal does not reopen the user-enumeration timing oracle {@code UserAuthenticator}
 * closes: it depends only on the queue, never on whether the account exists.
 *
 * <h2>Observability</h2>
 *
 * <p>The pool is a Micrometer-monitored executor named {@code password.hashing}: queue depth
 * ({@code executor.queued}), time waiting in it ({@code executor.idle}), hash time
 * ({@code executor}). Refusals count in {@code chess.auth.hashing.rejected}.
 */
public final class BoundedPasswordEncoder implements PasswordEncoder, AutoCloseable {

    static final String EXECUTOR_NAME = "password.hashing";
    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private final PasswordEncoder delegate;
    private final ThreadPoolExecutor pool;
    private final ExecutorService monitored;
    private final Counter rejected;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int threads, int queueCapacity,
                                  MeterRegistry metrics) {
        if (threads < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException(
                    "threads must be >= 1 and queueCapacity >= 0; got " + threads + ", " + queueCapacity);
        }
        this.delegate = delegate;
        AtomicInteger sequence = new AtomicInteger();
        // A capacity of 0 still needs a queue type that holds nothing; ArrayBlockingQueue needs >= 1.
        this.pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                queueCapacity == 0 ? new SynchronousQueue<>()
                                   : new ArrayBlockingQueue<>(queueCapacity),
                work -> Thread.ofPlatform()
                        .name("password-hash-" + sequence.incrementAndGet())
                        .daemon(true)
                        .unstarted(work),
                new ThreadPoolExecutor.AbortPolicy());
        this.monitored = ExecutorServiceMetrics.monitor(metrics, pool, EXECUTOR_NAME);
        this.rejected = Counter.builder("chess.auth.hashing.rejected")
                .description("Password hashes refused because the hashing queue was full (HTTP 503)")
                .register(metrics);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return run(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return run(() -> delegate.matches(rawPassword, encodedPassword));
    }

    /** Reads the stored hash's prefix and cost; no hashing, so no reason to queue. */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T run(Callable<T> hashing) {
        Future<T> result;
        try {
            result = monitored.submit(hashing);
        } catch (RejectedExecutionException queueFull) {
            rejected.increment();
            throw new DomainException.Unavailable(ErrorCode.SERVER_BUSY,
                    "The server is busy. Please try again in a moment.", RETRY_AFTER);
        }
        // Blocking here is cheap: the caller is a virtual thread, which unmounts and frees its
        // carrier for other requests while the hash runs elsewhere.
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a password hash", interrupted);
        } catch (ExecutionException failed) {
            // The delegate's own exception (e.g. IllegalArgumentException for a null password),
            // unchanged, as if it had been called directly.
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Password hashing failed", failed.getCause());
        }
    }

    /**
     * Called by Spring when the context closes — after graceful shutdown has drained in-flight
     * requests, so nothing is still waiting on a hash. Queued work is abandoned, not run.
     */
    @Override
    public void close() {
        pool.shutdownNow();
    }

    int threads() {
        return pool.getCorePoolSize();
    }

    int queueCapacity() {
        return pool.getQueue().remainingCapacity() + pool.getQueue().size();
    }
}
