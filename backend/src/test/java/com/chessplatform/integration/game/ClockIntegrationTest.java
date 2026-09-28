package com.chessplatform.integration.game;

import com.chessplatform.chess.MoveIntent;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.Termination;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.GameTimeouts;
import com.chessplatform.game.internal.ServerClock;
import com.chessplatform.game.SubmitMoveCommand;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The clock and the abort rule against a real database.
 *
 * <p>{@code ClockCalculatorTest} and {@code GameExpiryTest} cover the arithmetic and the
 * rules exhaustively without a database. What can only be tested here is the part
 * involving PostgreSQL: that time comes from the database, that timeouts and aborts
 * persist even when the request that discovered them fails, and that
 * {@code FOR UPDATE SKIP LOCKED} finalises an expired game exactly once.
 *
 * <h2>The scheduled sweeper is off</h2>
 *
 * <p>Disabled for every context built on {@link IntegrationTestBase} — see the comment
 * there for the cached-context race that made disabling it here alone insufficient. Tests
 * drive sweeps explicitly through {@link GameTimeouts}, which makes them deterministic.
 *
 * <h2>Timeouts need a started game</h2>
 *
 * <p>Since Milestone 3.2, a game that expires before both players have moved is
 * <em>aborted</em>, not lost on time. Every timeout test therefore plays 1.e4 e5 first;
 * before 3.2 they expired a game at ply 0, which now tests the abort path instead.
 */
@DisplayName("Game clock")
class ClockIntegrationTest extends IntegrationTestBase {

    private static final TimeControl TEN_SECONDS = new TimeControl(10_000, 0);

    @Autowired
    private GameService gameService;
    @Autowired
    private GameTimeouts timeouts;
    @Autowired
    private ServerClock serverClock;
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private UserRepository users;

    private User white;
    private User black;

    @BeforeEach
    void createPlayers() {
        white = registrar.register("w" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        black = registrar.register("b" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
    }

    @AfterEach
    void cleanUp() {
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    private Game newGame(TimeControl timeControl) {
        return gameService.createGame(white.id(), black.id(), timeControl);
    }

    /** A game both players have moved in: 1.e4 e5, White to move at ply 2. */
    private Game startedGame(TimeControl timeControl) {
        Game game = newGame(timeControl);
        play(game, white, 0, "e2", "e4");
        play(game, black, 1, "e7", "e5");
        return game;
    }

    private void play(Game game, User player, int expectedPly, String from, String to) {
        gameService.submitMove(game.id(), player.id(),
                new SubmitMoveCommand(UUID.randomUUID(), expectedPly, MoveIntent.of(from, to)));
    }

    private Game reload(Game game) {
        return games.findById(game.id()).orElseThrow();
    }

    @Nested
    @DisplayName("time is taken from the database")
    class TimeAuthority {

        @Test
        @DisplayName("ServerClock reports the database's time, not the JVM's")
        void usesDatabaseTime() {
            Instant before = Instant.now();
            Instant fromDatabase = serverClock.now();
            Instant after = Instant.now();

            // Both clocks are on this machine in a test, so they agree closely. The real
            // assertion is structural and lives in ServerClock's implementation.
            assertThat(fromDatabase).isBetween(before.minusSeconds(5), after.plusSeconds(5));
        }
    }

    @Nested
    @DisplayName("charging moves")
    class Charging {

        @Test
        @DisplayName("a new game starts with both clocks full and the abort window as its deadline")
        void startsFull() {
            Game game = newGame(TimeControl.BLITZ_5_3);

            assertThat(game.whiteMsLeft()).isEqualTo(300_000);
            assertThat(game.blackMsLeft()).isEqualTo(300_000);
            assertThat(game.turnDeadline())
                    .as("white must make a first move within the window, which is sooner "
                        + "than their five-minute clock")
                    .isEqualTo(game.lastMoveAt().plus(Game.FIRST_MOVE_WINDOW));
        }

        @Test
        @DisplayName("a move charges only the mover and adds their increment")
        void chargesOnlyTheMover() throws Exception {
            Game game = newGame(TimeControl.BLITZ_5_3);
            Thread.sleep(150);

            play(game, white, 0, "e2", "e4");

            Game reloaded = reload(game);
            assertThat(reloaded.whiteMsLeft())
                    .as("white spent ~150ms and gained a 3s increment")
                    .isBetween(302_000L, 303_000L);
            assertThat(reloaded.blackMsLeft())
                    .as("black's clock was frozen throughout")
                    .isEqualTo(300_000);
        }

        @Test
        @DisplayName("once both have moved, the deadline is the mover's flag-fall")
        void deadlineFollowsTheTurn() {
            Game game = startedGame(TimeControl.BLITZ_5_3);

            Game reloaded = reload(game);
            assertThat(reloaded.sideToMove()).isEqualTo(Side.WHITE);
            assertThat(reloaded.turnDeadline())
                    .isEqualTo(reloaded.lastMoveAt().plusMillis(reloaded.whiteMsLeft()));
        }
    }

    @Nested
    @DisplayName("running out of time")
    class FlagFall {

        /**
         * The write-then-throw case. The move is rejected with an exception, and the
         * timeout must persist anyway — which it did not, until the finalisation moved into
         * its own REQUIRES_NEW transaction. Asserting on the reloaded game rather than on
         * the exception is what caught it: the exception was correct, the database was not.
         */
        @Test
        @DisplayName("a move by a player whose time has gone ends the game on time")
        void movingAfterFlaggingLosesOnTime() {
            Game game = startedGame(TEN_SECONDS);
            expire(game.id());

            assertThatThrownBy(() -> play(game, white, 2, "g1", "f3"))
                    .isInstanceOf(DomainException.Rejected.class)
                    .hasMessageContaining("time ran out");

            Game finished = reload(game);
            assertThat(finished.status())
                    .as("the timeout must survive the exception that rejected the move")
                    .isEqualTo(GameStatus.FINISHED);
            assertThat(finished.termination()).isEqualTo(Termination.TIMEOUT);
            assertThat(finished.result())
                    .as("white's flag fell, so black wins")
                    .isEqualTo(GameResult.BLACK_WIN);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id()))
                    .as("the rejected move must not be recorded")
                    .hasSize(2);
        }

        @Test
        @DisplayName("the flagged player's clock is stored as zero")
        void flaggedClockIsZeroed() {
            Game game = startedGame(TEN_SECONDS);
            expire(game.id());

            assertThatThrownBy(() -> play(game, white, 2, "g1", "f3"))
                    .isInstanceOf(DomainException.class);

            // A finished game whose loser still shows time would be a permanent
            // inconsistency in the record.
            assertThat(reload(game).whiteMsLeft()).isZero();
        }
    }

    @Nested
    @DisplayName("aborting games nobody started")
    class Abort {

        /**
         * The same write-then-throw shape as a flag-fall, on the abort path: the late move
         * is refused AND the abort persists.
         */
        @Test
        @DisplayName("a first move after the window closes is refused and the game is aborted")
        void lateFirstMoveAborts() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            expire(game.id());

            assertThatThrownBy(() -> play(game, white, 0, "e2", "e4"))
                    .isInstanceOfSatisfying(DomainException.Rejected.class, rejected ->
                            assertThat(rejected.code()).isEqualTo(ErrorCode.GAME_ABORTED));

            Game aborted = reload(game);
            assertThat(aborted.status())
                    .as("the abort must survive the exception that rejected the move")
                    .isEqualTo(GameStatus.ABORTED);
            assertThat(aborted.result()).as("an aborted game is never rated").isNull();
            assertThat(aborted.termination()).isEqualTo(Termination.ABANDONED);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).isEmpty();
        }

        @Test
        @DisplayName("black never replying to the first move also aborts")
        void blackNeverRepliesAborts() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            play(game, white, 0, "e2", "e4");
            expire(game.id());

            assertThat(timeouts.finaliseExpiredBatch()).isEqualTo(1);

            Game aborted = reload(game);
            assertThat(aborted.status()).isEqualTo(GameStatus.ABORTED);
            assertThat(aborted.result())
                    .as("white made one move; that is not a game worth a rating change")
                    .isNull();
        }

        /**
         * With a clock shorter than the window, White's flag falls first. Recorded as an
         * abort, not a timeout: Black must not win a rated game White never played in.
         */
        @Test
        @DisplayName("a flag before anyone has moved is an abort, not a loss")
        void earlyFlagIsAnAbort() {
            Game game = newGame(TEN_SECONDS);
            expire(game.id());

            timeouts.finaliseExpiredBatch();

            assertThat(reload(game).status()).isEqualTo(GameStatus.ABORTED);
        }

        @Test
        @DisplayName("resigning before both players have moved aborts instead")
        void earlyResignationAborts() {
            Game game = newGame(TimeControl.BLITZ_5_3);

            Game after = gameService.resign(game.id(), white.id());

            assertThat(after.status()).isEqualTo(GameStatus.ABORTED);
            assertThat(reload(game).result()).isNull();
        }

        @Test
        @DisplayName("the database refuses an ABORTED game that has a result")
        void databaseRejectsARatedAbort() {
            Game game = newGame(TimeControl.BLITZ_5_3);

            // ck_games_result_consistency, from V1. The entity cannot produce this state;
            // the database refuses it anyway, for the code paths that do not go through
            // the entity — a manual fix, a future migration, a bulk job.
            assertThatThrownBy(() -> jdbc().update(
                    "UPDATE games SET status = 'ABORTED', result = 'WHITE_WIN' WHERE id = ?",
                    game.id()))
                    .hasMessageContaining("ck_games_result_consistency");
        }
    }

    @Nested
    @DisplayName("the timeout sweep")
    class Sweep {

        /**
         * The case laziness cannot cover: both players walked away. Before the 3.1 fix this
         * returned 1 — the row WAS claimed — while persisting nothing, because the method
         * was called on {@code this} and never ran in a transaction. Asserting on the
         * reloaded game is what exposed it.
         */
        @Test
        @DisplayName("finalises an abandoned game nobody is looking at")
        void finalisesAbandonedGame() {
            Game game = startedGame(TEN_SECONDS);
            expire(game.id());

            assertThat(timeouts.finaliseExpiredBatch()).isEqualTo(1);

            Game finished = reload(game);
            assertThat(finished.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(finished.termination()).isEqualTo(Termination.TIMEOUT);
            assertThat(finished.result()).isEqualTo(GameResult.BLACK_WIN);
        }

        @Test
        @DisplayName("aborts an unstarted game nobody is looking at")
        void abortsUnstartedGame() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            expire(game.id());

            assertThat(timeouts.finaliseExpiredBatch()).isEqualTo(1);

            assertThat(reload(game).status()).isEqualTo(GameStatus.ABORTED);
        }

        @Test
        @DisplayName("leaves games whose clock is still running")
        void ignoresLiveGames() {
            newGame(TimeControl.BLITZ_5_3);
            startedGame(TimeControl.BLITZ_5_3);

            assertThat(timeouts.finaliseExpiredBatch())
                    .as("neither a fresh game nor one with five minutes left is expired")
                    .isZero();
        }

        /**
         * The second sweep finds nothing because the first persisted FINISHED, so the row
         * no longer matches {@code status = 'ACTIVE'}. Before the 3.1 fix the second sweep
         * also returned 1 — the same game, claimed again, because nothing had been written.
         */
        @Test
        @DisplayName("a second sweep finds nothing to do")
        void doesNotDoubleFinalise() {
            Game game = startedGame(TEN_SECONDS);
            expire(game.id());

            assertThat(timeouts.finaliseExpiredBatch()).isEqualTo(1);
            assertThat(timeouts.finaliseExpiredBatch()).isZero();

            assertThat(reload(game).result()).isEqualTo(GameResult.BLACK_WIN);
        }

        @Test
        @DisplayName("never finalises a game that already ended another way")
        void ignoresFinishedGames() {
            Game game = startedGame(TEN_SECONDS);
            gameService.resign(game.id(), white.id());
            expire(game.id());

            timeouts.finaliseExpiredBatch();

            assertThat(reload(game).termination())
                    .as("a resignation must not be rewritten as a timeout")
                    .isEqualTo(Termination.RESIGNATION);
        }
    }

    /**
     * Pushes a game's last move an hour into the past, so its clock and its first-move
     * window have both expired, without waiting.
     *
     * <p>Raw SQL because the entity deliberately offers no way to move time backwards —
     * that is the kind of API that gets used in production by accident.
     */
    private void expire(UUID gameId) {
        jdbc().update("""
                UPDATE games
                   SET last_move_at  = now() - INTERVAL '1 hour',
                       turn_deadline = now() - INTERVAL '1 minute'
                 WHERE id = ?
                """, gameId);
    }
}
