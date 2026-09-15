package com.chessplatform.game.api;

import com.chessplatform.chess.ChessRules;
import com.chessplatform.chess.MoveIntent;
import com.chessplatform.chess.Position;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.game.GameFacade;
import com.chessplatform.game.GameView;
import com.chessplatform.game.api.dto.GameRequests;
import com.chessplatform.game.api.dto.GameResponses;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.SubmitMoveCommand;
import com.chessplatform.identity.IdentityFacade;
import com.chessplatform.identity.UserSummary;
import com.chessplatform.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;
    private final GameFacade gameFacade;
    private final MoveRepository moves;
    private final IdentityFacade identity;
    private final ChessRules rules;
    private final SecureRandom random = new SecureRandom();

    public GameController(GameService gameService, GameFacade gameFacade, MoveRepository moves,
                          IdentityFacade identity, ChessRules rules) {
        this.gameService = gameService;
        this.gameFacade = gameFacade;
        this.moves = moves;
        this.identity = identity;
        this.rules = rules;
    }

    @PostMapping
    public ResponseEntity<GameResponses.GameSummary> create(
            @Valid @RequestBody GameRequests.CreateGame request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        UserSummary opponent = identity.findByUsername(request.opponentUsername())
                .orElseThrow(() -> new DomainException.NotFound(
                        ErrorCode.USER_NOT_FOUND, "No player with that username."));

        if (opponent.id().equals(caller.id())) {
            throw new DomainException.Rejected(
                    ErrorCode.VALIDATION_FAILED, "You cannot play against yourself.");
        }

        Side callerSide = resolveSide(request.playAs());
        Game game = callerSide == Side.WHITE
                ? gameService.createGame(caller.id(), opponent.id())
                : gameService.createGame(opponent.id(), caller.id());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GameResponses.GameSummary.from(GameFacade.toView(game)));
    }

    /**
     * Full state plus the move log and legal moves — everything a client needs to render
     * the board from cold. This is also the polling fallback when the WebSocket is
     * unavailable (Phase 2), and the shape the WebSocket's {@code GAME_SNAPSHOT} will
     * carry, so the two paths agree by construction rather than by discipline.
     */
    @GetMapping("/{gameId}")
    public GameResponses.GameDetail get(@PathVariable UUID gameId) {
        GameView view = gameFacade.findById(gameId)
                .orElseThrow(() -> new DomainException.NotFound(
                        ErrorCode.GAME_NOT_FOUND, "No such game."));

        List<GameResponses.PlayedMove> played = moves.findByGameIdOrderByPlyAsc(gameId).stream()
                .map(move -> new GameResponses.PlayedMove(
                        move.ply(), move.uci(), move.san(), move.createdAt()))
                .toList();

        List<String> legal = view.status().isTerminal()
                ? List.of()
                : rules.legalMoves(new Position(view.fen()));

        return new GameResponses.GameDetail(
                GameResponses.GameSummary.from(view), played, legal);
    }

    /**
     * No {@code PUT}, and no move ID in the path. A move is not a resource being created
     * at a location the client chooses — it is an operation whose acceptance depends
     * entirely on server state. {@code POST} to a collection is the honest verb.
     */
    @PostMapping("/{gameId}/moves")
    public GameResponses.MoveAccepted move(@PathVariable UUID gameId,
                                           @Valid @RequestBody GameRequests.SubmitMove request,
                                           @AuthenticationPrincipal AuthenticatedUser caller) {

        GameService.MoveAccepted accepted = gameService.submitMove(gameId, caller.id(),
                new SubmitMoveCommand(
                        request.clientMoveId(),
                        request.expectedPly(),
                        new MoveIntent(request.from(), request.to(), request.promotion())));

        return new GameResponses.MoveAccepted(accepted.ply(), accepted.uci(), accepted.san(),
                accepted.fenAfter(), accepted.sideToMove(), accepted.gameOver(),
                accepted.replayed());
    }

    @PostMapping("/{gameId}/resign")
    public GameResponses.GameSummary resign(@PathVariable UUID gameId,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return GameResponses.GameSummary.from(
                GameFacade.toView(gameService.resign(gameId, caller.id())));
    }

    @GetMapping
    public List<GameResponses.GameSummary> myGames(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<GameView> games = gameFacade.findByPlayer(caller.id(), page, size);

        // Every player id across every game, resolved in a single query. This is what
        // IdentityFacade.findAllById exists for: the obvious implementation looks up each
        // game's two players as it maps, which is 2N queries — invisible at two games and
        // ruinous at two hundred. Giving callers a batch method is more effective than
        // telling them not to loop.
        Set<UUID> playerIds = games.stream()
                .flatMap(game -> Stream.of(game.whitePlayerId(), game.blackPlayerId()))
                .collect(Collectors.toSet());
        Map<UUID, UserSummary> players = identity.findAllById(playerIds);

        return games.stream()
                .map(game -> GameResponses.GameSummary.withNames(game,
                        nameOf(players, game.whitePlayerId()),
                        nameOf(players, game.blackPlayerId())))
                .toList();
    }

    /** A deleted account leaves a game that still needs rendering, so this never throws. */
    private static String nameOf(Map<UUID, UserSummary> players, UUID id) {
        UserSummary player = players.get(id);
        return player == null ? "unknown" : player.username();
    }

    /**
     * Random when unspecified. Letting the challenger always take White would hand them a
     * measurable advantage — White scores around 54% at every level — which turns "invite
     * a friend" into a way to farm rating.
     */
    private Side resolveSide(String requested) {
        if (requested == null || requested.isBlank()) {
            return random.nextBoolean() ? Side.WHITE : Side.BLACK;
        }
        return switch (requested.toUpperCase(java.util.Locale.ROOT)) {
            case "WHITE" -> Side.WHITE;
            case "BLACK" -> Side.BLACK;
            case "RANDOM" -> random.nextBoolean() ? Side.WHITE : Side.BLACK;
            default -> throw new DomainException.Rejected(ErrorCode.VALIDATION_FAILED,
                    "playAs must be WHITE, BLACK or RANDOM.");
        };
    }
}
