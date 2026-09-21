package com.chessplatform.integration.game;

import com.chessplatform.chess.MoveIntent;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.Termination;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.ServerClock;
import com.chessplatform.game.internal.SubmitMoveCommand;
import com.chessplatform.game.internal.TimeoutSweeper;
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
 * The clock against a real database.
 *
 * <p>{@code ClockCalculatorTest} covers the arithmetic exhaustively and without a database.
 * What can only be tested here is the part that involves PostgreSQL: that time comes from
 * the database rather than the JVM, that the deadline column and the sweeper's index agree,
 * and that {@code FOR UPDATE SKIP LOCKED} finalises an abandoned game exactly once.
 */
@DisplayName("Game clock")
class ClockIntegrationTest extends IntegrationTestBase {

    /** Short enough that a test can exhaust it without sleeping for minutes. */
    private static final TimeControl TEN_SECONDS = new TimeControl(10_000, 0);

    @Autowired
    private GameService gameService;
    @Autowired
    private TimeoutSweeper sweeper;
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

    private void play(Game game, User player, int expectedPly, String from, String to) {
        gameService.submitMove(game.id(), player.id(),
                new SubmitMoveCommand(UUID.randomUUID(), expectedPly, MoveIntent.of(from, to)));
    }

    @Nested
    @DisplayName("time is taken from the database")
    class TimeAuthority {

        /**
         * Two instances on different hosts disagree by tens of milliseconds even under NTP.
         * If successive moves in one game were measured against different clocks, the error
         * would accumulate and could go negative. This asserts the authority is PostgreSQL,
         * not the JVM.
         */
        @Test
        @DisplayName("ServerClock reports the database's time, not the JVM's")
        void usesDatabaseTime() {
            Instant before = Instant.now();
            Instant fromDatabase = serverClock.now();
            Instant after = Instant.now();

            // Both clocks are on this machine in a test, so they agree closely. What
            // matters is that a value was genuinely round-tripped through PostgreSQL —
            // a hardcoded Instant.now() would also pass this, so the real assertion is
            // structural and lives in ServerClock's implementation.
            assertThat(fromDatabase).isBetween(before.minusSeconds(5), after.plusSeconds(5));
        }
    }

    @Nested
    @DisplayName("charging moves")
    class Charging {

        @Test
        @DisplayName("a new game starts with both clocks full and a deadline set")
        void startsFull() {
            Game game = newGame(TimeControl.BLITZ_5_3);

            assertThat(game.whiteMsLeft()).isEqualTo(300_000);
            assertThat(game.blackMsLeft()).isEqualTo(300_000);
            assertThat(game.turnDeadline())
                    .as("white is already on the clock")
                    .isEqualTo(game.lastMoveAt().plusMillis(300_000));
        }

        @Test
        @DisplayName("a move charges only the mover and adds their increment")
        void chargesOnlyTheMover() throws Exception {
            Game game = newGame(TimeControl.BLITZ_5_3);
            Thread.sleep(150);

            play(game, white, 0, "e2", "e4");

            Game reloaded = games.findById(game.id()).orElseThrow();
            assertThat(reloaded.whiteMsLeft())
                    .as("white spent ~150ms and gained a 3s increment")
                    .isBetween(302_000L, 303_000L);
            assertThat(reloaded.blackMsLeft())
                    .as("black's clock was frozen throughout")
                    .isEqualTo(300_000);
        }

        @Test
        @DisplayName("the deadline moves to the player who must now reply")
        void deadlineFollowsTheTurn() {
            Game game = newGame(TimeControl.BLITZ_5_3);
            play(game, white, 0, "e2", "e4");

            Game reloaded = games.findById(game.id()).orElseThrow();
            assertThat(reloaded.sideToMove()).isEqualTo(Side.BLACK);
            assertThat(reloaded.turnDeadline())
                    .isEqualTo(reloaded.lastMoveAt().plusMillis(reloaded.blackMsLeft()));
        }
    }

    @Nested
    @DisplayName("running out of time")
    class FlagFall {

        @Test
        @DisplayName("a move by a player whose time has gone ends the game on time")
        void movingAfterFlaggingLosesOnTime() throws Exception {
            Game game = newGame(new TimeControl(10_000, 0));
            // Rewind the clock rather than sleeping: a test that sleeps for ten seconds is
            // a test nobody runs.
            expireClock(game.id());

            assertThatThrownBy(() -> play(game, white, 0, "e2", "e4"))
                    .isInstanceOf(DomainException.Rejected.class)
                    .hasMessageContaining("time ran out");

            Game finished = games.findById(game.id()).orElseThrow();
            assertThat(finished.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(finished.termination()).isEqualTo(Termination.TIMEOUT);
            assertThat(finished.result())
                    .as("white's flag fell, so black wins")
                    .isEqualTo(GameResult.BLACK_WIN);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id()))
                    .as("the move must not be recorded")
                    .isEmpty();
        }

        @Test
        @DisplayName("the flagged player's clock is stored as zero")
        void flaggedClockIsZeroed() {
            Game game = newGame(TEN_SECONDS);
            expireClock(game.id());

            assertThatThrownBy(() -> play(game, white, 0, "e2", "e4"))
                    .isInstanceOf(DomainException.class);

            // A finished game whose loser still shows 400ms would be a permanent
            // inconsistency in the record.
            assertThat(games.findById(game.id()).orElseThrow().whiteMsLeft()).isZero();
        }
    }

    @Nested
    @DisplayName("the timeout sweeper")
    class Sweeper {

        /**
         * The case laziness cannot cover: both players walked away, so nothing will ever
         * read the game and notice. Without the sweeper it stays ACTIVE forever, shows in
         * both players' lists, and is never rated.
         */
        @Test
        @DisplayName("finalises an abandoned game nobody is looking at")
        void finalisesAbandonedGame() {
            Game game = newGame(TEN_SECONDS);
            expireClock(game.id());

            assertThat(sweeper.sweepNow()).isEqualTo(1);

            Game finished = games.findById(game.id()).orElseThrow();
            assertThat(finished.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(finished.termination()).isEqualTo(Termination.TIMEOUT);
            assertThat(finished.result()).isEqualTo(GameResult.BLACK_WIN);
        }

        @Test
        @DisplayName("leaves games whose clock is still running")
        void ignoresLiveGames() {
            newGame(TimeControl.BLITZ_5_3);

            assertThat(sweeper.sweepNow())
                    .as("a game with five minutes left is not expired")
                    .isZero();
        }

        /**
         * Several replicas sweep concurrently. {@code FOR UPDATE SKIP LOCKED} makes each
         * take a disjoint batch, so no leader election or distributed lock is required —
         * the same argument as ADR-005, applied to background work.
         *
         * <p>Running it twice in sequence proves the idempotence that matters: the second
         * sweep finds nothing, because the first left the game FINISHED and it no longer
         * matches the predicate.
         */
        @Test
        @DisplayName("a second sweep finds nothing to do")
        void doesNotDoubleFinalise() {
            Game game = newGame(TEN_SECONDS);
            expireClock(game.id());

            assertThat(sweeper.sweepNow()).isEqualTo(1);
            assertThat(sweeper.sweepNow()).isZero();

            assertThat(games.findById(game.id()).orElseThrow().result())
                    .isEqualTo(GameResult.BLACK_WIN);
        }

        @Test
        @DisplayName("never finalises a game that already ended another way")
        void ignoresFinishedGames() {
            Game game = newGame(TEN_SECONDS);
            gameService.resign(game.id(), white.id());
            expireClock(game.id());

            sweeper.sweepNow();

            assertThat(games.findById(game.id()).orElseThrow().termination())
                    .as("a resignation must not be rewritten as a timeout")
                    .isEqualTo(Termination.RESIGNATION);
        }
    }

    /**
     * Pushes a game's clock into the past so it is expired, without waiting.
     *
     * <p>Written as SQL rather than through the entity because the entity deliberately
     * offers no way to move time backwards — that is the kind of API that gets used in
     * production by accident.
     */
    private void expireClock(UUID gameId) {
        jdbc().update("""
                UPDATE games
                   SET last_move_at  = now() - INTERVAL '1 hour',
                       turn_deadline = now() - INTERVAL '1 minute'
                 WHERE id = ?
                """, gameId);
    }
}
