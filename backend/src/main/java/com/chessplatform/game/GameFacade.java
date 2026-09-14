package com.chessplatform.game;

import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The game module's sole entry point for other modules.
 *
 * <p>Read-only by design. Nothing outside this module creates games or plays moves —
 * those go through the HTTP layer or, from Phase 4, matchmaking calling
 * {@code GameService} within the module. A facade should expose what collaborators need,
 * not everything the module can do.
 */
@Service
public class GameFacade {

    private static final int MAX_PAGE_SIZE = 50;

    private final GameRepository games;

    public GameFacade(GameRepository games) {
        this.games = games;
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
                game.ply(), game.sideToMove(), game.createdAt(), game.finishedAt());
    }
}
