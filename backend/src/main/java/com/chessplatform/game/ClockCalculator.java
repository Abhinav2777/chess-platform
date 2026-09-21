package com.chessplatform.game;

import com.chessplatform.chess.Side;

import java.time.Instant;

/**
 * The chess clock, as a pure function.
 *
 * <h2>The clock does not tick</h2>
 *
 * <p>There is no timer, no scheduled task per game, and no mutable counter. Remaining time
 * is computed on demand from three persisted values — the side's stored milliseconds, the
 * timestamp of the last move, and the current time:
 *
 * <pre>
 *   remaining(side to move) = stored − (now − lastMoveAt)
 *   remaining(other side)   = stored                        // frozen
 * </pre>
 *
 * <h2>Why the obvious design is wrong</h2>
 *
 * <p>A {@code ScheduledExecutorService} per active game, decrementing an in-memory counter,
 * fails in five separate ways: the counter dies with the process; two instances can both
 * hold a timer for one game after a rebalance and double-fire; N thousand games means N
 * thousand timers; GC pauses and thread-pool saturation make the tick interval a fiction;
 * and nothing can be restored on restart.
 *
 * <p>A computed clock has none of those properties because it has no state of its own. It
 * gives the same answer read a millisecond or an hour later, from any instance, after any
 * number of restarts. <strong>The reconnect-to-a-different-pod scenario needs no handling
 * at all</strong> — the clock was never in the pod that was left.
 *
 * <h2>Everything here is static and pure</h2>
 *
 * <p>No clock, no repository, no Spring. Every edge case — flag-fall, a clock that moved
 * backwards, increments at the boundary — is testable by passing numbers in and comparing
 * numbers out, with no database and no sleeping. A test suite that sleeps is slow and
 * flaky in equal measure, and the flakiness always appears in CI rather than locally.
 */
public final class ClockCalculator {

    private ClockCalculator() {
    }

    /**
     * Time left for a side at a given instant.
     *
     * <p>Only the side to move is spending time; the other side's clock is frozen at its
     * stored value. Never negative — a flagged clock reads zero, and whether that ends the
     * game is decided by the caller, not here.
     */
    public static long remainingMs(Side side, Side sideToMove,
                                   long whiteMsLeft, long blackMsLeft,
                                   Instant lastMoveAt, Instant now) {
        long stored = side == Side.WHITE ? whiteMsLeft : blackMsLeft;
        if (side != sideToMove) {
            return stored;
        }
        return Math.max(0, stored - elapsedMs(lastMoveAt, now));
    }

    /**
     * Charges the mover for the time they took and adds their increment.
     *
     * @return the new remaining time, or a flagged result if they ran out
     */
    public static Charge charge(long msLeft, Instant lastMoveAt, Instant now, long incrementMs) {
        long afterThinking = msLeft - elapsedMs(lastMoveAt, now);

        // <= 0, not < 0. Reaching exactly zero is out of time — a player with no time left
        // has no time left, and "exactly zero still counts" is the kind of off-by-one that
        // decides a real game and is impossible to argue afterwards.
        if (afterThinking <= 0) {
            return new Charge(0, true);
        }

        // The increment is added only on a completed move. A player who flags mid-think
        // does not receive it, which is what every chess clock in the world does.
        return new Charge(afterThinking + incrementMs, false);
    }

    /**
     * When the side to move runs out, assuming they do not move.
     *
     * <p>Persisted on every move so the timeout sweeper can find expired games by index
     * rather than by computing this for every row.
     */
    public static Instant deadline(Instant lastMoveAt, long msLeftForMover) {
        return lastMoveAt.plusMillis(msLeftForMover);
    }

    /**
     * Elapsed milliseconds, clamped at zero.
     *
     * <p>The clamp is not defensive noise. {@code lastMoveAt} can legitimately be in the
     * future relative to {@code now} — a database failover to a replica whose clock is a
     * few milliseconds behind, or an administrative time correction. Without the clamp a
     * negative elapsed would <em>credit</em> the mover time they never had, and the error
     * would persist for the rest of the game because it is written back into the stored
     * value.
     *
     * <p>Failing toward "no time passed" is the safe direction: a player is never punished
     * for a server-side clock anomaly, and the error does not accumulate.
     */
    private static long elapsedMs(Instant lastMoveAt, Instant now) {
        return Math.max(0, now.toEpochMilli() - lastMoveAt.toEpochMilli());
    }

    /**
     * @param msLeft  remaining time after the move, zero when flagged
     * @param flagged whether the mover ran out of time instead of completing the move
     */
    public record Charge(long msLeft, boolean flagged) {
    }
}
