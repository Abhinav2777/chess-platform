package com.chessplatform.game;

import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.internal.GameService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The game module's sole entry point for other modules.
 *
 * <p>Deliberately narrow. Other modules may read games and — since Phase 4 — start one,
 * because matchmaking has to turn a pairing into a game and cannot reach
 * {@code game.internal}. They cannot play moves or end games: those stay behind the HTTP
 * and WebSocket paths, which carry the player's identity. A facade should expose what
 * collaborators need, not everything the module can do.
 */
@Service
public class GameFacade {

    private static final int MAX_PAGE_SIZE = 50;

    private final GameRepository games;
    private final GameService gameService;

    public GameFacade(GameRepository games, GameService gameService) {
        this.games = games;
        this.gameService = gameService;
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
