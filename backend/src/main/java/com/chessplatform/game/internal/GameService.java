package com.chessplatform.game.internal;

import com.chessplatform.chess.ChessRules;
import com.chessplatform.chess.MoveResult;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.common.id.Uuid7;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRecord;
import com.chessplatform.game.GameEvents;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.MoveRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Game lifecycle and the move pipeline.
 *
 * <h2>The invariant</h2>
 *
 * <p><strong>For a given {@code (game_id, ply)}, exactly one move is ever committed.</strong>
 * Everything below exists to make that true under retries, double-clicks, two browser
 * tabs, and two application instances handling the same game.
 *
 * <h2>Three overlapping defences (ADR-005)</h2>
 *
 * <ol>
 *   <li><strong>Idempotency.</strong> {@code uq_moves_client_id}. A retry returns the
 *       original result rather than an error, so the client cannot distinguish a retry
 *       from a first attempt — which is the definition of idempotent.</li>
 *   <li><strong>Optimistic locking.</strong> {@code games.version}. Two writers that both
 *       read version 7 cannot both write version 8.</li>
 *   <li><strong>Structural.</strong> {@code PRIMARY KEY (game_id, ply)}. With the other
 *       two broken, two moves at one ply remain physically unstorable.</li>
 * </ol>
 *
 * <p><strong>No distributed lock anywhere.</strong> The database already provides the
 * guarantee atomically. A Redis lock would add a TTL-expiry failure mode — a GC pause
 * longer than the TTL yields two holders — without removing the need for any of the three
 * constraints above, and would make data correctness depend on a cache being available.
 *
 * <h2>Why the checks are ordered as they are</h2>
 *
 * <p>Cheapest and most specific first. A player submitting a move for a finished game
 * should be told that, not handed a generic conflict — and the ordering means the
 * expensive work (loading, rules evaluation, writing) never runs for a request that was
 * always going to be refused.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final GameRepository games;
    private final MoveRepository moves;
    private final ChessRules rules;
    private final Clock clock;
    private final ServerClock serverClock;
    private final ApplicationEventPublisher events;

    private final Timer moveLatency;
    private final Counter conflicts;
    private final Counter idempotentReplays;
    private final Counter staleSubmissions;
    private final Counter flagFalls;

    public GameService(GameRepository games, MoveRepository moves, ChessRules rules,
                       Clock clock, ServerClock serverClock,
                       ApplicationEventPublisher events, MeterRegistry metrics) {
        this.games = games;
        this.moves = moves;
        this.rules = rules;
        this.clock = clock;
        this.serverClock = serverClock;
        this.events = events;

        this.moveLatency = Timer.builder("chess.move.processing")
                .description("Time to validate and commit a move")
                .register(metrics);
        // The metric to point at when asked whether the concurrency design is exercised
        // or merely theoretical. A counter that never moves in production is a signal in
        // itself — either the race does not happen, or the code path is unreachable.
        this.conflicts = Counter.builder("chess.move.conflicts")
                .description("Moves rejected by optimistic locking or a constraint")
                .register(metrics);
        this.idempotentReplays = Counter.builder("chess.move.idempotent_replays")
                .description("Retried moves served from the existing record")
                .register(metrics);
        this.staleSubmissions = Counter.builder("chess.move.stale_submissions")
                .description("Moves rejected because the client's ply was out of date")
                .register(metrics);
        this.flagFalls = Counter.builder("chess.move.flag_falls")
                .description("Moves rejected because the mover had already run out of time")
                .register(metrics);
    }

    @Transactional
    public Game createGame(UUID whitePlayerId, UUID blackPlayerId, TimeControl timeControl) {
        // Started from the DATABASE clock, not the application's. White's clock begins the
        // instant the game is created, so this value decides a game and must come from the
        // same authority every subsequent move is measured against (ServerClock).
        Game game = Game.start(Uuid7.generate(), whitePlayerId, blackPlayerId,
                rules.startingPosition(), timeControl, serverClock.now());
        return games.save(game);
    }

    @Transactional(readOnly = true)
    public Game requireGame(UUID gameId) {
        return games.findById(gameId).orElseThrow(() -> new DomainException.NotFound(
                ErrorCode.GAME_NOT_FOUND, "No such game."));
    }

    /**
     * Validates and commits a move.
     *
     * @throws DomainException.Rejected on an illegal move, wrong turn, finished game, or
     *                                  a caller who is not a player
     * @throws DomainException.Conflict on a stale ply or a lost write race — both mean
     *                                  "resync and try again", not "your move was wrong"
     */
    @Transactional
    public MoveAccepted submitMove(UUID gameId, UUID playerId, SubmitMoveCommand command) {
        Timer.Sample sample = Timer.start();
        try {
            return process(gameId, playerId, command);
        } finally {
            sample.stop(moveLatency);
        }
    }

    private MoveAccepted process(UUID gameId, UUID playerId, SubmitMoveCommand command) {
        // 1. Idempotency, the fast path. This lookup is ergonomics, not safety — two
        //    concurrent retries can both read "not present" before either writes. The
        //    unique constraint below is what actually guarantees single application.
        Optional<MoveRecord> alreadyApplied =
                moves.findByGameIdAndClientMoveId(gameId, command.clientMoveId());
        if (alreadyApplied.isPresent()) {
            idempotentReplays.increment();
            return MoveAccepted.replayOf(alreadyApplied.get(), requireGame(gameId));
        }

        Game game = requireGame(gameId);

        // 2. Authorisation before state checks: a non-player must not learn whether a
        //    game is active by the shape of the error they get back.
        Side side = game.sideOf(playerId);

        // 3. Terminal games reject everything. Checked on the entity so the rule lives
        //    with the state it guards.
        if (!game.isActive()) {
            throw new DomainException.Rejected(
                    ErrorCode.GAME_NOT_ACTIVE, "This game has already ended.");
        }

        // 4. Turn. Also enforced by the rules engine, which will not generate a move for
        //    the wrong side — but checked here to return a precise error rather than a
        //    generic "illegal move".
        if (side != game.sideToMove()) {
            throw new DomainException.Rejected(
                    ErrorCode.NOT_YOUR_TURN, "It is not your turn.");
        }

        // 5. Stale client. The client believed the game was at a different ply, which
        //    means it is playing against a position it cannot see — usually because it
        //    missed the opponent's move. Reject and let it resync rather than apply a
        //    move to a board the player never looked at.
        if (command.expectedPly() != game.ply()) {
            staleSubmissions.increment();
            throw new DomainException.Conflict(ErrorCode.CONFLICT,
                    "Your board is out of date. Expected ply %d but the game is at %d."
                            .formatted(command.expectedPly(), game.ply()));
        }

        // 6. The clock, before legality. A player who has already run out does not get to
        //    play a legal move: the game ended the moment their time did, and only nobody
        //    having looked kept it ACTIVE. Checking after would let a move land on a game
        //    that was over.
        Instant now = serverClock.now();
        if (game.hasFlagged(now)) {
            flagFalls.increment();
            game.flagOnTime(now);
            games.saveAndFlush(game);
            publishGameEnded(game);
            throw new DomainException.Rejected(
                    ErrorCode.OUT_OF_TIME, "Your time ran out.");
        }

        // 7. Legality. The only authority on whether this move is playable.
        MoveResult result = rules.apply(game.position(), command.intent());

        moves.save(MoveRecord.of(gameId, game.ply() + 1, result, command.clientMoveId(), now));
        // Charges the mover, adds their increment, and recomputes the deadline for the
        // player who must now move.
        game.applyMove(result, now);

        try {
            // saveAndFlush, not save. `save` defers the write to commit, where both the
            // optimistic-lock failure and the unique-constraint violation would escape
            // this method and surface as an opaque 500 instead of a 409.
            games.saveAndFlush(game);
        } catch (OptimisticLockingFailureException lostTheRace) {
            // Another transaction advanced this game between our read and our write.
            // ADR-005 prescribes re-read and re-validate rather than blind retry — and
            // re-validation would reject this move anyway, because the turn has now
            // flipped. So the correct response is: resync, then decide.
            conflicts.increment();
            log.info("Optimistic lock conflict on game={} ply={} player={}",
                    gameId, game.ply(), playerId);
            throw new DomainException.Conflict(ErrorCode.CONFLICT,
                    "Someone moved first. Refresh the position and try again.");
        } catch (DataIntegrityViolationException duplicate) {
            // The idempotency key or the (game_id, ply) primary key fired. Both mean this
            // move, or another at this ply, already exists.
            //
            // We cannot read the original here: PostgreSQL has aborted the transaction, so
            // any query in it fails. The client retries, step 1 finds the record, and the
            // result is idempotent — one round trip later than ideal, which is the honest
            // cost of not opening a second transaction on a rare path.
            conflicts.increment();
            log.info("Duplicate move rejected by constraint on game={} player={}",
                    gameId, playerId);
            throw new DomainException.Conflict(ErrorCode.CONFLICT,
                    "That move was already submitted. Retry to fetch the result.");
        }

        // Published inside the transaction; delivered only after it commits, because the
        // listener is @TransactionalEventListener(AFTER_COMMIT). Broadcasting from here
        // directly would mean a rollback leaves every client showing a move that never
        // happened — and the rollback path is the optimistic-lock conflict, which is
        // precisely the case this system exists to handle.
        events.publishEvent(new GameEvents.MovePlayed(gameId, playerId, game.ply(),
                result.uci(), result.san(), result.positionAfter().fen(),
                result.sideToMove(), !game.isActive(),
                // Empty once the game is over, which the client uses to stop accepting
                // input without needing to interpret the status itself.
                game.isActive() ? rules.legalMoves(result.positionAfter()) : java.util.List.of(),
                game.whiteMsLeft(), game.blackMsLeft()));

        if (!game.isActive()) {
            publishGameEnded(game);
        }

        return MoveAccepted.of(result, game);
    }

    private void publishGameEnded(Game game) {
        events.publishEvent(new GameEvents.GameEnded(game.id(),
                game.whitePlayerId(), game.blackPlayerId(),
                game.result(), game.termination()));
    }

    @Transactional
    public Game resign(UUID gameId, UUID playerId) {
        Game game = requireGame(gameId);
        Side side = game.sideOf(playerId);
        game.resign(side, serverClock.now());
        // No explicit save: `game` is managed inside this transaction, so the dirty check
        // at commit issues the UPDATE — and the @Version column is bumped with it, so a
        // resignation racing a move is still resolved by optimistic locking.
        publishGameEnded(game);
        return game;
    }

    /**
     * What the caller needs after a successful (or replayed) move.
     *
     * @param whiteMsLeft stored remaining time, not "remaining right now". The side to
     *                    move is spending from this instant onward, so a client ticks it
     *                    down locally for display and resyncs on the next server message.
     *                    Sending a snapshot of a moving value is the only honest option —
     *                    any "live" figure is stale by the network latency anyway.
     */
    public record MoveAccepted(int ply, String uci, String san, String fenAfter,
                               Side sideToMove, boolean gameOver, boolean replayed,
                               long whiteMsLeft, long blackMsLeft) {

        static MoveAccepted of(MoveResult result, Game game) {
            return new MoveAccepted(game.ply(), result.uci(), result.san(),
                    result.positionAfter().fen(), result.sideToMove(),
                    !game.isActive(), false, game.whiteMsLeft(), game.blackMsLeft());
        }

        /**
         * A replay reports the state the move produced, which is the point: the client
         * gets the same answer it would have got the first time, flagged so it can tell
         * a retry succeeded rather than a new move being played.
         */
        static MoveAccepted replayOf(MoveRecord record, Game game) {
            return new MoveAccepted(record.ply(), record.uci(), record.san(),
                    record.fenAfter(), game.sideToMove(), !game.isActive(), true,
                    game.whiteMsLeft(), game.blackMsLeft());
        }
    }
}
