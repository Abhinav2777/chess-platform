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
import com.chessplatform.game.internal.SubmitMoveCommand;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Gameplay")
class GameplayIntegrationTest extends IntegrationTestBase {

    @Autowired
    private GameService gameService;
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
    private Game game;

    @BeforeEach
    void createPlayers() {
        white = registrar.register("magnus", "w@example.com", "correct-horse-battery");
        black = registrar.register("hikaru", "b@example.com", "correct-horse-battery");
        game = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);
    }

    @AfterEach
    void cleanUp() {
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    private GameService.MoveAccepted play(User player, int expectedPly, String from, String to) {
        return gameService.submitMove(game.id(), player.id(),
                new SubmitMoveCommand(UUID.randomUUID(), expectedPly, MoveIntent.of(from, to)));
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("starts active, at ply zero, with white to move")
        void startsCorrectly() {
            assertThat(game.status()).isEqualTo(GameStatus.ACTIVE);
            assertThat(game.ply()).isZero();
            assertThat(game.sideToMove()).isEqualTo(Side.WHITE);
            assertThat(game.result()).isNull();
            assertThat(game.version()).isZero();
        }

        @Test
        @DisplayName("records a move and advances the turn")
        void playsAMove() {
            GameService.MoveAccepted accepted = play(white, 0, "e2", "e4");

            assertThat(accepted.ply()).isEqualTo(1);
            assertThat(accepted.san()).isEqualTo("e4");
            assertThat(accepted.sideToMove()).isEqualTo(Side.BLACK);
            assertThat(accepted.replayed()).isFalse();

            Game reloaded = games.findById(game.id()).orElseThrow();
            assertThat(reloaded.ply()).isEqualTo(1);
            assertThat(reloaded.sideToMove()).isEqualTo(Side.BLACK);
            // The optimistic lock advanced — evidence the version column is live and not
            // merely declared.
            assertThat(reloaded.version()).isEqualTo(1);
        }

        @Test
        @DisplayName("plays a complete game ending in checkmate, and awards it to the mover")
        void playsToCheckmate() {
            play(white, 0, "e2", "e4");
            play(black, 1, "e7", "e5");
            play(white, 2, "f1", "c4");
            play(black, 3, "b8", "c6");
            play(white, 4, "d1", "h5");
            play(black, 5, "g8", "f6");

            GameService.MoveAccepted mate = play(white, 6, "h5", "f7");

            assertThat(mate.gameOver()).isTrue();

            Game finished = games.findById(game.id()).orElseThrow();
            assertThat(finished.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(finished.termination()).isEqualTo(Termination.CHECKMATE);
            // White delivered mate, so White won. Getting this backwards is a bug no
            // compiler catches and no symmetric test notices.
            assertThat(finished.result()).isEqualTo(GameResult.WHITE_WIN);
            assertThat(finished.finishedAt()).isNotNull();
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).hasSize(7);
        }

        @Test
        @DisplayName("resignation awards the win to the opponent")
        void resigns() {
            play(white, 0, "e2", "e4");

            Game resigned = gameService.resign(game.id(), black.id());

            assertThat(resigned.status()).isEqualTo(GameStatus.FINISHED);
            assertThat(resigned.result()).isEqualTo(GameResult.WHITE_WIN);
            assertThat(resigned.termination()).isEqualTo(Termination.RESIGNATION);
        }

        @Test
        @DisplayName("rejects any move once the game has ended")
        void rejectsMovesAfterEnd() {
            gameService.resign(game.id(), white.id());

            assertThatThrownBy(() -> play(black, 1, "e7", "e5"))
                    .isInstanceOf(DomainException.Rejected.class);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a move by the player whose turn it is not")
        void rejectsOutOfTurn() {
            assertThatThrownBy(() -> play(black, 0, "e7", "e5"))
                    .isInstanceOf(DomainException.Rejected.class)
                    .hasMessageContaining("not your turn");
        }

        @Test
        @DisplayName("rejects an illegal move")
        void rejectsIllegalMove() {
            assertThatThrownBy(() -> play(white, 0, "e2", "e5"))
                    .isInstanceOf(DomainException.Rejected.class);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).isEmpty();
        }

        @Test
        @DisplayName("rejects a move from someone who is not a player")
        void rejectsNonPlayer() {
            User stranger = registrar.register("stranger", "s@example.com", "correct-horse-battery");

            assertThatThrownBy(() -> gameService.submitMove(game.id(), stranger.id(),
                    new SubmitMoveCommand(UUID.randomUUID(), 0, MoveIntent.of("e2", "e4"))))
                    .isInstanceOf(DomainException.Rejected.class)
                    .hasMessageContaining("not a player");
        }

        /**
         * A client that missed its opponent's move is playing against a board it cannot
         * see. Applying the move anyway would be silently wrong — the player would have
         * moved into a position they never looked at.
         */
        @Test
        @DisplayName("rejects a move whose expected ply is out of date")
        void rejectsStaleClient() {
            play(white, 0, "e2", "e4");

            assertThatThrownBy(() -> play(black, 0, "e7", "e5"))
                    .isInstanceOf(DomainException.Conflict.class)
                    .hasMessageContaining("out of date");
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /**
         * The property that lets a client retry safely. Without it, a lost acknowledgement
         * forces a choice between losing the move and playing it twice — and in a rated
         * game both are unacceptable.
         */
        @Test
        @DisplayName("a retried move returns the original result and is applied once")
        void retryReturnsOriginalResult() {
            UUID clientMoveId = UUID.randomUUID();
            SubmitMoveCommand command =
                    new SubmitMoveCommand(clientMoveId, 0, MoveIntent.of("e2", "e4"));

            GameService.MoveAccepted first = gameService.submitMove(game.id(), white.id(), command);
            GameService.MoveAccepted retry = gameService.submitMove(game.id(), white.id(), command);

            assertThat(retry.ply()).isEqualTo(first.ply());
            assertThat(retry.uci()).isEqualTo(first.uci());
            assertThat(retry.fenAfter()).isEqualTo(first.fenAfter());
            assertThat(retry.replayed()).isTrue();

            assertThat(moves.findByGameIdOrderByPlyAsc(game.id()))
                    .as("the move must exist exactly once")
                    .hasSize(1);
            assertThat(games.findById(game.id()).orElseThrow().ply()).isEqualTo(1);
        }

        @Test
        @DisplayName("a different key for the same move is a genuine second attempt")
        void distinctKeysAreDistinctAttempts() {
            play(white, 0, "e2", "e4");

            // Same move, new idempotency key: not a retry. It is now Black's turn, so
            // this must be refused on turn, not silently replayed.
            assertThatThrownBy(() -> play(white, 1, "e2", "e4"))
                    .isInstanceOf(DomainException.Rejected.class);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * The flagship test. It is the executable form of this project's central claim.
         *
         * <p>Sixteen threads submit a move for the same ply of the same game, released
         * together by a latch so they interleave inside the read-validate-write window.
         * Each carries a distinct idempotency key, so none of them is a retry — these are
         * sixteen genuine competing attempts.
         *
         * <p>Exactly one may win. The losers fail in whichever of three ways the race
         * resolves — wrong turn once the side flips, a stale ply, or an optimistic-lock
         * conflict — and which one is not deterministic. That is fine: the assertion is
         * about the invariant, not the mechanism.
         *
         * <p>With the version column removed, this test produces duplicate moves and a
         * corrupted ply. That is what makes it evidence rather than decoration.
         */
        @Test
        @DisplayName("exactly one of many concurrent moves is committed")
        void onlyOneConcurrentMoveWins() throws Exception {
            int contenders = 16;
            var startLine = new CountDownLatch(1);

            List<Callable<Integer>> attempts = IntStream.range(0, contenders)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        startLine.await();
                        return gameService.submitMove(game.id(), white.id(),
                                new SubmitMoveCommand(UUID.randomUUID(), 0,
                                        MoveIntent.of("e2", "e4"))).ply();
                    })
                    .toList();

            List<Future<Integer>> futures;
            try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
                futures = attempts.stream().map(pool::submit).toList();
                startLine.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            }

            long succeeded = futures.stream().filter(future -> {
                try {
                    future.get();
                    return true;
                } catch (Exception rejected) {
                    return false;
                }
            }).count();

            assertThat(succeeded)
                    .as("exactly one move may be committed at a given ply")
                    .isEqualTo(1);
            assertThat(moves.findByGameIdOrderByPlyAsc(game.id()))
                    .as("no duplicate move reached the table")
                    .hasSize(1);
            assertThat(games.findById(game.id()).orElseThrow().ply())
                    .as("the game advanced exactly one ply")
                    .isEqualTo(1);
        }

        /**
         * Both players moving at once. In chess this is usually one legal move and one
         * illegal one, since only one side may move — so the interesting assertion is
         * that the game stays consistent, not that both are served.
         */
        @Test
        @DisplayName("simultaneous moves by both players leave the game consistent")
        void bothPlayersMoveAtOnce() throws Exception {
            var startLine = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> whiteMove = pool.submit(() -> {
                    startLine.await();
                    return play(white, 0, "e2", "e4");
                });
                Future<?> blackMove = pool.submit(() -> {
                    startLine.await();
                    return play(black, 0, "e7", "e5");
                });
                startLine.countDown();

                assertThatThrownBy(blackMove::get)
                        .as("black cannot move on white's turn")
                        .hasCauseInstanceOf(DomainException.class);
                assertThat(whiteMove.get()).isNotNull();
            }

            assertThat(moves.findByGameIdOrderByPlyAsc(game.id())).hasSize(1);
            assertThat(games.findById(game.id()).orElseThrow().ply()).isEqualTo(1);
        }
    }
}
