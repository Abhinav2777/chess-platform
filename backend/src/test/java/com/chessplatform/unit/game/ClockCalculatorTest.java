package com.chessplatform.unit.game;

import com.chessplatform.chess.Side;
import com.chessplatform.game.ClockCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clock, tested as arithmetic.
 *
 * <p>No database, no Spring, no sleeping — the whole point of making the clock a pure
 * function is that every edge case becomes numbers in and numbers out. A flag-fall at
 * exactly zero, a clock that moved backwards, an increment at the boundary: all of them
 * are ordinary assertions here, and every one of them would need an awkward, slow and
 * flaky test if the clock were a ticking timer.
 *
 * <p>This is the class that decides games. It gets the most exhaustive test in the project
 * for that reason and no other.
 */
@DisplayName("ClockCalculator")
class ClockCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-09-14T12:00:00Z");
    private static final long FIVE_MINUTES = Duration.ofMinutes(5).toMillis();
    private static final long THREE_SECONDS = 3_000;

    @Nested
    @DisplayName("remaining time")
    class Remaining {

        @Test
        @DisplayName("the side to move is spending; the other side is frozen")
        void onlyTheMoverSpends() {
            Instant now = T0.plusMillis(10_000);

            assertThat(ClockCalculator.remainingMs(Side.WHITE, Side.WHITE,
                    FIVE_MINUTES, FIVE_MINUTES, T0, now))
                    .isEqualTo(FIVE_MINUTES - 10_000);

            assertThat(ClockCalculator.remainingMs(Side.BLACK, Side.WHITE,
                    FIVE_MINUTES, FIVE_MINUTES, T0, now))
                    .as("the player not to move spends nothing")
                    .isEqualTo(FIVE_MINUTES);
        }

        @Test
        @DisplayName("never reports negative time")
        void clampsAtZero() {
            Instant longAfter = T0.plus(Duration.ofHours(1));

            assertThat(ClockCalculator.remainingMs(Side.WHITE, Side.WHITE,
                    FIVE_MINUTES, FIVE_MINUTES, T0, longAfter))
                    .isZero();
        }

        /**
         * A database failover to a replica a few milliseconds behind, or an administrative
         * time correction, can put {@code lastMoveAt} in the future. Without the clamp this
         * would CREDIT the mover time they never had — and because the value is written
         * back on the next move, the error would persist for the rest of the game.
         */
        @Test
        @DisplayName("a clock that moved backwards never credits time")
        void clockRegressionDoesNotCreditTime() {
            Instant beforeTheLastMove = T0.minusMillis(5_000);

            assertThat(ClockCalculator.remainingMs(Side.WHITE, Side.WHITE,
                    FIVE_MINUTES, FIVE_MINUTES, T0, beforeTheLastMove))
                    .as("no time passed, and certainly none was gained")
                    .isEqualTo(FIVE_MINUTES);
        }
    }

    @Nested
    @DisplayName("charging a move")
    class Charging {

        @Test
        @DisplayName("deducts thinking time and adds the increment")
        void deductsAndIncrements() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    FIVE_MINUTES, T0, T0.plusMillis(10_000), THREE_SECONDS);

            assertThat(charge.flagged()).isFalse();
            assertThat(charge.msLeft()).isEqualTo(FIVE_MINUTES - 10_000 + THREE_SECONDS);
        }

        @Test
        @DisplayName("an instant move still earns the increment")
        void instantMoveGainsTime() {
            ClockCalculator.Charge charge =
                    ClockCalculator.charge(FIVE_MINUTES, T0, T0, THREE_SECONDS);

            // A Fischer increment means a fast player gains time. That is the point of it.
            assertThat(charge.msLeft()).isEqualTo(FIVE_MINUTES + THREE_SECONDS);
        }

        @Test
        @DisplayName("zero increment deducts only")
        void zeroIncrement() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    FIVE_MINUTES, T0, T0.plusMillis(1_000), 0);

            assertThat(charge.msLeft()).isEqualTo(FIVE_MINUTES - 1_000);
        }

        @Test
        @DisplayName("flags when time runs out")
        void flagsWhenOutOfTime() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    10_000, T0, T0.plusMillis(10_001), THREE_SECONDS);

            assertThat(charge.flagged()).isTrue();
            assertThat(charge.msLeft()).isZero();
        }

        /**
         * The off-by-one that would decide a real game and be impossible to argue
         * afterwards. Reaching exactly zero is out of time.
         */
        @Test
        @DisplayName("exactly zero is a flag, not a survival")
        void exactlyZeroFlags() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    10_000, T0, T0.plusMillis(10_000), THREE_SECONDS);

            assertThat(charge.flagged()).isTrue();
        }

        @Test
        @DisplayName("one millisecond short of zero survives")
        void oneMillisecondSpare() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    10_000, T0, T0.plusMillis(9_999), THREE_SECONDS);

            assertThat(charge.flagged()).isFalse();
            assertThat(charge.msLeft()).isEqualTo(1 + THREE_SECONDS);
        }

        /**
         * A player who runs out mid-think does not receive the increment. Every physical
         * chess clock behaves this way, and awarding it would let a flagged player be
         * revived by the bonus for a move they never completed.
         */
        @Test
        @DisplayName("a flagged player does not receive the increment")
        void flaggedPlayerGetsNoIncrement() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    1_000, T0, T0.plusMillis(60_000), Duration.ofMinutes(2).toMillis());

            assertThat(charge.flagged()).isTrue();
            assertThat(charge.msLeft()).isZero();
        }

        @Test
        @DisplayName("a clock that moved backwards charges nothing")
        void regressionChargesNothing() {
            ClockCalculator.Charge charge = ClockCalculator.charge(
                    FIVE_MINUTES, T0, T0.minusMillis(5_000), THREE_SECONDS);

            assertThat(charge.flagged()).isFalse();
            assertThat(charge.msLeft()).isEqualTo(FIVE_MINUTES + THREE_SECONDS);
        }
    }

    @Nested
    @DisplayName("deadline")
    class Deadline {

        @Test
        @DisplayName("is the last move plus the mover's remaining time")
        void isLastMovePlusRemaining() {
            assertThat(ClockCalculator.deadline(T0, FIVE_MINUTES))
                    .isEqualTo(T0.plus(Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("is immediate for a player with no time")
        void isImmediateWhenOutOfTime() {
            assertThat(ClockCalculator.deadline(T0, 0)).isEqualTo(T0);
        }

        /**
         * The deadline is what the sweeper's index is built on, so it must agree exactly
         * with what {@code remainingMs} reports. If they could disagree, a game would
         * either be flagged while its clock still showed time, or show zero and never be
         * swept.
         */
        @Test
        @DisplayName("agrees with remainingMs at the exact moment of expiry")
        void agreesWithRemaining() {
            long msLeft = 42_000;
            Instant deadline = ClockCalculator.deadline(T0, msLeft);

            assertThat(ClockCalculator.remainingMs(Side.WHITE, Side.WHITE,
                    msLeft, FIVE_MINUTES, T0, deadline))
                    .isZero();
            assertThat(ClockCalculator.remainingMs(Side.WHITE, Side.WHITE,
                    msLeft, FIVE_MINUTES, T0, deadline.minusMillis(1)))
                    .isEqualTo(1);
        }
    }
}
