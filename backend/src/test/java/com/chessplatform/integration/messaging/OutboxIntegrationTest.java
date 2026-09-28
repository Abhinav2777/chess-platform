package com.chessplatform.integration.messaging;

import com.chessplatform.chess.MoveIntent;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.game.SubmitMoveCommand;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.GameTimeouts;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import com.chessplatform.messaging.Outbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every rated ending writes exactly one GAME_FINISHED row, in the game's own transaction;
 * aborts write none. One test per path a game can end by, because the design's claim is
 * that a single listener covers them all — a claim worth checking path by path.
 */
@DisplayName("Outbox: GAME_FINISHED is written with the game")
class OutboxIntegrationTest extends IntegrationTestBase {

    @Autowired
    private GameService gameService;
    @Autowired
    private GameTimeouts timeouts;
    @Autowired
    private Outbox outbox;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private UserRepository users;
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;

    private User white;
    private User black;

    @BeforeEach
    void players() {
        white = registrar.register("ow" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
        black = registrar.register("ob" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.com", "correct-horse-battery");
    }

    @AfterEach
    void cleanUp() {
        jdbc().update("DELETE FROM outbox");
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    private Game newGame() {
        return gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);
    }

    private void play(Game game, User player, int ply, String from, String to) {
        gameService.submitMove(game.id(), player.id(),
                new SubmitMoveCommand(UUID.randomUUID(), ply, MoveIntent.of(from, to)));
    }

    private List<Map<String, Object>> eventsFor(Game game) {
        return jdbc().queryForList(
                "SELECT event_type, payload::text AS payload, published_at FROM outbox WHERE aggregate_id = ?",
                game.id());
    }

    private void expire(UUID gameId) {
        jdbc().update("""
                UPDATE games SET last_move_at = now() - INTERVAL '1 hour',
                                 turn_deadline = now() - INTERVAL '1 minute'
                 WHERE id = ?
                """, gameId);
    }

    @Test
    @DisplayName("checkmate writes one event with the result")
    void checkmate() {
        Game game = newGame();
        play(game, white, 0, "f2", "f3");
        play(game, black, 1, "e7", "e5");
        play(game, white, 2, "g2", "g4");
        play(game, black, 3, "d8", "h4");   // fool's mate

        List<Map<String, Object>> events = eventsFor(game);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("event_type")).isEqualTo("GAME_FINISHED");
        assertThat((String) events.getFirst().get("payload"))
                .contains("\"result\": \"BLACK_WIN\"", "\"termination\": \"CHECKMATE\"", "\"schemaVersion\": 1");
        assertThat(events.getFirst().get("published_at")).as("unsent until the relay runs").isNull();
    }

    @Test
    @DisplayName("resignation writes one event")
    void resignation() {
        Game game = newGame();
        play(game, white, 0, "e2", "e4");
        play(game, black, 1, "e7", "e5");
        gameService.resign(game.id(), black.id());

        assertThat(eventsFor(game)).singleElement()
                .satisfies(e -> assertThat((String) e.get("payload")).contains("\"termination\": \"RESIGNATION\""));
    }

    @Test
    @DisplayName("a flag found by the sweeper writes one event")
    void timeoutBySweeper() {
        Game game = newGame();
        play(game, white, 0, "e2", "e4");
        play(game, black, 1, "e7", "e5");
        expire(game.id());

        timeouts.finaliseExpiredBatch();

        assertThat(eventsFor(game)).singleElement()
                .satisfies(e -> assertThat((String) e.get("payload")).contains("\"termination\": \"TIMEOUT\""));
    }

    /**
     * The subtle one. The move path finalises a flagged game in REQUIRES_NEW and then throws
     * OUT_OF_TIME, rolling back the move's own transaction. The event must be in the inner,
     * committed transaction — or the timeout is saved and never rated.
     */
    @Test
    @DisplayName("a flag found on the move path writes one event, despite the move's rollback")
    void timeoutOnMovePath() {
        Game game = newGame();
        play(game, white, 0, "e2", "e4");
        play(game, black, 1, "e7", "e5");
        expire(game.id());

        assertThatThrownBy(() -> play(game, white, 2, "g1", "f3"))
                .isInstanceOf(DomainException.Rejected.class);

        assertThat(eventsFor(game)).singleElement()
                .satisfies(e -> assertThat((String) e.get("payload")).contains("\"termination\": \"TIMEOUT\""));
    }

    @Test
    @DisplayName("aborted games write nothing — by resignation or by the sweeper")
    void abortsAreNotEvents() {
        Game resignedEarly = newGame();
        gameService.resign(resignedEarly.id(), white.id());

        Game neverStarted = newGame();
        expire(neverStarted.id());
        timeouts.finaliseExpiredBatch();

        assertThat(eventsFor(resignedEarly)).isEmpty();
        assertThat(eventsFor(neverStarted)).isEmpty();
    }

    @Test
    @DisplayName("appending outside a transaction is refused, not quietly committed on its own")
    void mandatoryTransaction() {
        assertThatThrownBy(() -> outbox.append("GAME_FINISHED", UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("the database refuses a second event for the same game")
    void onePerGame() {
        UUID gameId = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> outbox.append("GAME_FINISHED", gameId, Map.of("n", 1)));

        assertThatThrownBy(() -> transactions.executeWithoutResult(
                tx -> outbox.append("GAME_FINISHED", gameId, Map.of("n", 2))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_outbox_event_per_aggregate");
    }
}
