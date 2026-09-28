package com.chessplatform.unit.game;

import com.chessplatform.chess.GameOutcome;
import com.chessplatform.chess.MoveResult;
import com.chessplatform.chess.Position;
import com.chessplatform.chess.Side;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.Termination;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The abort rule and the timeout rule, and which one applies when.
 *
 * <p>Pure: {@link Game} is constructed directly and time is passed in. The only way to get
 * a boundary like "exactly 30 seconds" right is to be able to state it exactly, which an
 * integration test that sleeps can never do.
 */
@DisplayName("Game expiry: abort vs timeout")
class GameExpiryTest {

    private static final Instant T0 = Instant.parse("2026-09-28T12:00:00Z");
    private static final long WINDOW_MS = Game.FIRST_MOVE_WINDOW.toMillis();
    private static final TimeControl TEN_SECONDS = new TimeControl(10_000, 0);

    private static final MoveResult E4 = new MoveResult("e2e4", "e4",
            new Position("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"),
            Side.BLACK, GameOutcome.IN_PROGRESS);
    private static final MoveResult E5 = new MoveResult("e7e5", "e5",
            new Position("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"),
            Side.WHITE, GameOutcome.IN_PROGRESS);

    private static Game newGame(TimeControl timeControl) {
        return Game.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Position.starting(), timeControl, T0);
    }

    private static Instant at(long millis) {
        return T0.plusMillis(millis);
    }

    @Nested
    @DisplayName("before anyone has moved")
    class Unstarted {

        @Test
        @DisplayName("the stored deadline is the abort window, not the five-minute clock")
        void deadlineIsTheWindow() {
            Game game = newGame(TimeControl.BLITZ_5_3);

            assertThat(game.turnDeadline()).isEqualTo(at(WINDOW_MS));
        }

        @Test
        @DisplayName("nothing happens one millisecond before the window closes")
        void openJustBeforeTheWindow() {
            assertThat(newGame(TimeControl.BLITZ_5_3).expiryAt(at(WINDOW_MS - 1)))
                    .isEqualTo(Game.Expiry.NONE);
        }

        @Test
        @DisplayName("reaching the window exactly aborts — the same boundary as a flag at zero")
        void abortsExactlyAtTheWindow() {
            assertThat(newGame(TimeControl.BLITZ_5_3).expiryAt(at(WINDOW_MS)))
                    .isEqualTo(Game.Expiry.ABORT);
        }

        /**
         * With 10 seconds on the clock, White flags before the window closes. Calling that
         * a timeout would award Black a rated win in a game nobody played a move in.
         */
        @Test
        @DisplayName("a clock shorter than the window aborts rather than flags")
        void shortClockAbortsNotFlags() {
            Game game = newGame(TEN_SECONDS);

            assertThat(game.turnDeadline())
                    .as("the deadline is whichever comes first")
                    .isEqualTo(at(10_000));
            assertThat(game.expiryAt(at(10_000))).isEqualTo(Game.Expiry.ABORT);
        }

        @Test
        @DisplayName("a clock anomaly that puts 'now' before the start expires nothing")
        void clockAnomalyNeverExpires() {
            assertThat(newGame(TimeControl.BLITZ_5_3).expiryAt(T0.minusSeconds(60)))
                    .isEqualTo(Game.Expiry.NONE);
        }
    }

    @Nested
    @DisplayName("after White's first move")
    class BlacksFirstMove {

        @Test
        @DisplayName("Black gets a fresh window of their own, measured from White's move")
        void blackGetsAWindow() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            game.applyMove(E4, at(20_000));

            assertThat(game.turnDeadline()).isEqualTo(at(20_000 + WINDOW_MS));
            assertThat(game.expiryAt(at(20_000 + WINDOW_MS - 1))).isEqualTo(Game.Expiry.NONE);
            assertThat(game.expiryAt(at(20_000 + WINDOW_MS))).isEqualTo(Game.Expiry.ABORT);
        }
    }

    @Nested
    @DisplayName("once both players have moved")
    class Started {

        private Game startedGame(TimeControl timeControl) {
            Game game = newGame(timeControl);
            game.applyMove(E4, at(1_000));
            game.applyMove(E5, at(2_000));
            return game;
        }

        @Test
        @DisplayName("the deadline is the mover's flag-fall again")
        void deadlineIsTheClock() {
            Game game = startedGame(TimeControl.BLITZ_5_3);

            assertThat(game.turnDeadline())
                    .isEqualTo(game.lastMoveAt().plusMillis(game.whiteMsLeft()));
        }

        @Test
        @DisplayName("a long think past the window is just a long think")
        void windowNoLongerApplies() {
            Game game = startedGame(TimeControl.BLITZ_5_3);

            assertThat(game.expiryAt(at(2_000 + WINDOW_MS * 2))).isEqualTo(Game.Expiry.NONE);
        }

        @Test
        @DisplayName("running out of time is a timeout, not an abort")
        void runningOutFlags() {
            Game game = startedGame(TEN_SECONDS);

            // White spent 1s on move one, so 9s remain from the 2s mark.
            assertThat(game.expiryAt(at(2_000 + 9_000 - 1))).isEqualTo(Game.Expiry.NONE);
            assertThat(game.expiryAt(at(2_000 + 9_000))).isEqualTo(Game.Expiry.FLAG);
        }
    }

    @Nested
    @DisplayName("aborting")
    class Aborting {

        @Test
        @DisplayName("an aborted game has no result, so it can never be rated")
        void abortHasNoResult() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            game.abort(at(WINDOW_MS));

            assertThat(game.status()).isEqualTo(GameStatus.ABORTED);
            assertThat(game.result()).isNull();
            assertThat(game.termination()).isEqualTo(Termination.ABANDONED);
            assertThat(game.finishedAt()).isEqualTo(at(WINDOW_MS));
            assertThat(game.expiryAt(at(WINDOW_MS * 10)))
                    .as("an ended game never expires again")
                    .isEqualTo(Game.Expiry.NONE);
        }

        /**
         * The guard that stops the abort path becoming an escape hatch: a player losing a
         * started game must not be able to make the result disappear.
         */
        @Test
        @DisplayName("a game both players have moved in cannot be aborted")
        void cannotAbortAStartedGame() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            game.applyMove(E4, at(1_000));
            game.applyMove(E5, at(2_000));

            assertThatThrownBy(() -> game.abort(at(3_000)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(game.status()).isEqualTo(GameStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("resigning")
    class Resigning {

        @Test
        @DisplayName("resigning before both players have moved aborts instead")
        void earlyResignationAborts() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            game.applyMove(E4, at(1_000));

            game.resign(Side.BLACK, at(2_000));

            assertThat(game.status()).isEqualTo(GameStatus.ABORTED);
            assertThat(game.result())
                    .as("no rated win for a game Black never played")
                    .isNull();
        }

        @Test
        @DisplayName("resigning a started game awards the win")
        void resignationAfterStartCounts() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            game.applyMove(E4, at(1_000));
            game.applyMove(E5, at(2_000));

            game.resign(Side.WHITE, at(3_000));

            assertThat(game.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(game.result()).isEqualTo(GameResult.BLACK_WIN);
            assertThat(game.termination()).isEqualTo(Termination.RESIGNATION);
        }
    }
}
