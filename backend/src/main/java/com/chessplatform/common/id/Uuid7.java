package com.chessplatform.common.id;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates time-ordered UUIDs per RFC 9562 version 7.
 *
 * <h2>Why not {@code UUID.randomUUID()}</h2>
 *
 * <p>{@code randomUUID()} produces version 4 — 122 bits of entropy with no ordering.
 * As a primary key that is actively hostile to a B-tree index. Each insert lands at a
 * uniformly random point in the index, so the write touches a different page every time.
 * Two consequences: the working set of hot index pages becomes the <em>entire index</em>
 * rather than its right edge, and pages fill unevenly and split. On a table that only
 * ever appends, a v4 key turns sequential writes into random I/O.
 *
 * <p>UUIDv7 puts a 48-bit millisecond timestamp in the high bits, so lexicographic and
 * chronological order coincide. Inserts concentrate at the right edge of the index like
 * a sequence would, while keeping the two properties we actually need from a UUID:
 * generatable without a database round-trip, and safe to expose in a URL without
 * leaking how many rows exist.
 *
 * <h2>Why not a library</h2>
 *
 * <p>{@code java-uuid-generator} does this well. Twenty-five lines of exactly-specified
 * bit layout with its own tests is a smaller risk than another coordinate to verify,
 * and the monotonicity behaviour below is something we want to control explicitly
 * rather than inherit.
 *
 * <h2>Monotonicity within a millisecond</h2>
 *
 * <p>The timestamp only has millisecond resolution, so IDs generated in the same
 * millisecond would otherwise be ordered at random among themselves. RFC 9562 §6.2
 * allows using {@code rand_a} as a counter for this, which is what we do: a new
 * millisecond seeds the counter randomly in its lower half, and subsequent IDs in that
 * millisecond increment it. Ordering is then total, not just approximate.
 *
 * <p>Seeding randomly rather than at zero costs nothing and avoids publishing an exact
 * count of how many IDs were minted in a given millisecond.
 *
 * <h2>Clock regression</h2>
 *
 * <p>If the wall clock moves backwards — NTP correction, VM migration — we do
 * <em>not</em> emit a smaller timestamp. The generator holds its previous value and
 * keeps incrementing the counter, so the sequence stays monotonic across the jump. If
 * the counter exhausts, the timestamp advances by one millisecond and the counter
 * resets. Generated IDs can therefore run very slightly ahead of the wall clock under
 * extreme load, which is the correct trade: these are identifiers, not timestamps, and
 * nothing reads the embedded time as authoritative. (The game clock takes its time from
 * PostgreSQL — see ADR-006.)
 *
 * <p>Thread-safe and lock-free via CAS on an immutable state record. No
 * {@code synchronized}, so no risk of pinning a virtual thread on older JVMs.
 */
public final class Uuid7 {

    /** rand_a is 12 bits. */
    private static final int COUNTER_BITS = 12;
    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;

    /** New milliseconds seed here, leaving the upper half as headroom before exhaustion. */
    private static final int COUNTER_SEED_BOUND = 1 << (COUNTER_BITS - 1);

    private static final long VERSION_7 = 0x7L << 12;
    private static final long VARIANT_RFC9562 = 0x8000000000000000L;
    private static final long LOW_62_BITS = 0x3FFFFFFFFFFFFFFFL;
    private static final long TIMESTAMP_MASK = 0xFFFFFFFFFFFFL;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicReference<State> STATE =
            new AtomicReference<>(new State(Long.MIN_VALUE, 0));

    private Uuid7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /**
     * Visible for testing, so the timestamp can be pinned and regressed deliberately.
     * Not public: callers should never choose an ID's timestamp.
     */
    static UUID generate(long timestampMs) {
        State next;
        State previous;
        do {
            previous = STATE.get();
            if (timestampMs > previous.timestampMs()) {
                next = new State(timestampMs, RANDOM.nextInt(COUNTER_SEED_BOUND));
            } else if (previous.counter() < COUNTER_MAX) {
                // Same millisecond, or the clock went backwards. Either way, hold the
                // previous timestamp and advance the counter — never move backwards.
                next = new State(previous.timestampMs(), previous.counter() + 1);
            } else {
                // Counter exhausted inside one millisecond: borrow from the next.
                next = new State(previous.timestampMs() + 1, 0);
            }
        } while (!STATE.compareAndSet(previous, next));

        long mostSignificant = ((next.timestampMs() & TIMESTAMP_MASK) << 16)
                | VERSION_7
                | next.counter();

        long leastSignificant = (RANDOM.nextLong() & LOW_62_BITS) | VARIANT_RFC9562;

        return new UUID(mostSignificant, leastSignificant);
    }

    /** Extracts the embedded creation time. Useful in tests and when debugging ordering. */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    private record State(long timestampMs, int counter) {
    }
}
