package com.chessplatform.game;

import com.chessplatform.game.domain.Game;
import com.chessplatform.chess.ChessRules;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.ServerClock;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The game module's sole entry point for other modules.
 *
 * <p>Exposes what collaborators actually need and nothing else: reads ({@link #state},
 * {@link #findById}, {@link #findByPlayer}), starting a game (matchmaking), and playing and
 * resigning on a player's behalf (the WebSocket transport). Every mutation takes the
 * acting player's id, so authorisation stays inside this module.
 *
 * <p>Before Milestone 4.1b {@code realtime} reached into {@code game.internal} and
 * {@code game.domain} directly. The module rule forbidding that was not checking anything
 * — ArchUnit could not read Java 25 bytecode — so nobody was told (DEVELOPMENT_LOG, 4.1b).
 */
@Service
public class GameFacade {

    private static final int MAX_PAGE_SIZE = 50;

    private final GameRepository games;
    private final MoveRepository moves;
    private final GameService gameService;
    private final ChessRules rules;
    private final ServerClock serverClock;

    public GameFacade(GameRepository games, MoveRepository moves, GameService gameService,
                      ChessRules rules, ServerClock serverClock) {
        this.games = games;
        this.moves = moves;
        this.gameService = gameService;
        this.rules = rules;
        this.serverClock = serverClock;
    }

    /**
     * The game, its move log and the database time, as one consistent snapshot.
     *
     * <p>{@code REPEATABLE READ} makes PostgreSQL use one snapshot for every statement in
     * the transaction, so the game row and the log cannot straddle a concurrent move; and
     * {@code now()} is the transaction's start time, so {@code asOf} is the same instant.
     * Read-only, so the stronger isolation costs nothing: a read-only transaction cannot hit
     * a serialisation failure.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<GameState> state(UUID gameId) {
        return games.findById(gameId).map(game -> {
            List<GameState.MoveView> log = moves.findByGameIdOrderByPlyAsc(gameId).stream()
                    .map(m -> new GameState.MoveView(m.ply(), m.uci(), m.san(), m.createdAt()))
                    .toList();
            List<String> legal = game.isActive() ? rules.legalMoves(game.position()) : List.of();
            return new GameState(toView(game), log, legal, serverClock.now());
        });
    }

    /**
     * Plays a move for a player — the same pipeline, with the same three defences, whatever
     * the transport (ADR-005). The outcome reaches every client, including the mover,
     * through the {@code GameEvents} it publishes after commit.
     */
    public void submitMove(UUID gameId, UUID playerId, SubmitMoveCommand command) {
        gameService.submitMove(gameId, playerId, command);
    }

    /** Resigns for a player; aborts instead before both have moved (ADR-014). */
    public GameView resign(UUID gameId, UUID playerId) {
        return toView(gameService.resign(gameId, playerId));
    }

    /**
     * Starts a game between two players. Its own transaction: when this returns, the game
     * is committed and visible to any instance, so a caller may announce it immediately.
     */
    public GameView startGame(UUID whitePlayerId, UUID blackPlayerId, TimeControl timeControl) {
        return toView(gameService.createGame(whitePlayerId, blackPlayerId, timeControl));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveGame(UUID playerId) {
        return games.existsActiveForPlayer(playerId);
    }

    @Transactional(readOnly = true)
    public Optional<GameView> findById(UUID gameId) {
        return games.findById(gameId).map(GameFacade::toView);
    }

    @Transactional(readOnly = true)
    public List<GameView> findByPlayer(UUID playerId, int page, int size) {
        // Clamped, not trusted. An unbounded page size is a trivial denial-of-service:
        // one request asking for a million rows will happily try.
        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return games.findByPlayer(playerId, PageRequest.of(Math.max(page, 0), bounded))
                .stream()
                .map(GameFacade::toView)
                .toList();
    }

    public static GameView toView(Game game) {
        return new GameView(game.id(), game.whitePlayerId(), game.blackPlayerId(),
                game.status(), game.result(), game.termination(), game.fen(),
                game.ply(), game.sideToMove(),
                game.initialMs(), game.incrementMs(),
                game.whiteMsLeft(), game.blackMsLeft(), game.lastMoveAt(),
                game.createdAt(), game.finishedAt());
    }
}
