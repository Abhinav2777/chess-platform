package com.chessplatform.game.domain;

import com.chessplatform.chess.GameOutcome;
import com.chessplatform.chess.MoveResult;
import com.chessplatform.chess.Position;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.game.ClockCalculator;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.Termination;
import com.chessplatform.game.TimeControl;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A game between two players.
 *
 * <h2>Where the rules live</h2>
 *
 * <p>State transitions are methods on this class, not steps in a service. {@code Game}
 * has no public setters, so there is no route to a game that is FINISHED without a
 * result, or whose ply disagrees with its FEN. The database enforces the same invariant
 * independently via {@code ck_games_result_consistency} — belt and braces, because an
 * invariant guarded in exactly one place is guarded until someone adds a second caller.
 *
 * <h2>The version column</h2>
 *
 * <p>{@link Version} is the optimistic lock from ADR-005. Two transactions that both read
 * version 7 and both try to write version 8 cannot both commit; the loser gets an
 * {@code OptimisticLockingFailureException}. This is the middle of three overlapping
 * defences — the outer two being the idempotency key on {@code moves} and the composite
 * primary key {@code (game_id, ply)}, which makes two moves at one ply physically
 * unstorable.
 */
@Entity
@Table(name = "games")
public class Game {

    /**
     * How long each player has to make their <em>first</em> move before the game is
     * aborted rather than played.
     *
     * <p>A game in which a player never moved was never a game: rating it would hand one
     * player points for the other's mis-click, closed tab, or accidental challenge. Thirty
     * seconds is long enough for a human who is actually there and short enough that an
     * abandoned challenge does not sit in both players' lists for five minutes.
     *
     * <p>Mirrored as a literal in {@code V5__first_move_abort_deadline.sql}, which
     * backfills existing games. Changing it here needs no migration — only games created
     * afterwards are affected — but the two should be kept in step so the record stays
     * explainable.
     */
    public static final Duration FIRST_MOVE_WINDOW = Duration.ofSeconds(30);

    /** Plies 0 and 1: White's first move, then Black's. */
    private static final int OPENING_PLIES = 2;

    /** What, if anything, has to happen to a game because time passed. */
    public enum Expiry {
        /** Nothing. The side to move still has time. */
        NONE,
        /** A player never made their first move. No result, no rating change. */
        ABORT,
        /** The side to move ran out of time in a game that had started. They lose. */
        FLAG
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "white_player_id", nullable = false, updatable = false)
    private UUID whitePlayerId;

    @Column(name = "black_player_id", nullable = false, updatable = false)
    private UUID blackPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 16)
    private GameResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "termination", length = 24)
    private Termination termination;

    /**
     * The current position. A denormalisation of the move log: replaying {@code moves}
     * reproduces it exactly, which is what makes the log rather than this column the real
     * record (ADR-004).
     */
    @Column(name = "fen", nullable = false, length = 100)
    private String fen;

    @Column(name = "ply", nullable = false)
    private int ply;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_to_move", nullable = false, length = 5)
    private Side sideToMove;

    // ---- clock (ADR-006) --------------------------------------------------
    // Five values, no ticking process. Remaining time is derived from these and the
    // current time on every read, which is why the clock survives a reconnect to a
    // different instance without any handling at all.

    @Column(name = "initial_ms", nullable = false, updatable = false)
    private long initialMs;

    @Column(name = "increment_ms", nullable = false, updatable = false)
    private long incrementMs;

    @Column(name = "white_ms_left", nullable = false)
    private long whiteMsLeft;

    @Column(name = "black_ms_left", nullable = false)
    private long blackMsLeft;

    /** When the clock last changed hands. The side to move has been spending since then. */
    @Column(name = "last_move_at", nullable = false)
    private Instant lastMoveAt;

    /**
     * The next instant at which this game needs the server's attention if nobody moves:
     * the mover's flag-fall, or — while a player has yet to make their first move — the
     * end of {@link #FIRST_MOVE_WINDOW}, whichever comes first.
     *
     * <p>Stored rather than computed so the sweeper's query is an index scan bounded by
     * expired games rather than a scan of all of them. Folding the abort window into the
     * same column is what lets one index, one query and one sweeper handle both
     * timeouts and aborts; {@link #expiryAt} decides which one applies.
     */
    @Column(name = "turn_deadline", nullable = false)
    private Instant turnDeadline;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Game() {
    }

    private Game(UUID id, UUID whitePlayerId, UUID blackPlayerId, String fen,
                 Side sideToMove, TimeControl timeControl, Instant startedAt) {
        this.id = id;
        this.whitePlayerId = whitePlayerId;
        this.blackPlayerId = blackPlayerId;
        this.status = GameStatus.ACTIVE;
        this.fen = fen;
        this.ply = 0;
        this.sideToMove = sideToMove;
        this.initialMs = timeControl.initialMs();
        this.incrementMs = timeControl.incrementMs();
        this.whiteMsLeft = timeControl.initialMs();
        this.blackMsLeft = timeControl.initialMs();
        // White's clock starts the moment the game does. Nobody has moved, so the
        // "previous move" is the start of the game.
        this.lastMoveAt = startedAt;
        this.createdAt = startedAt;
        this.turnDeadline = nextDeadline(startedAt);
    }

    public static Game start(UUID id, UUID whitePlayerId, UUID blackPlayerId,
                             Position startingPosition, TimeControl timeControl,
                             Instant startedAt) {
        if (whitePlayerId.equals(blackPlayerId)) {
            throw new IllegalArgumentException("a player cannot play themselves");
        }
        return new Game(id, whitePlayerId, blackPlayerId, startingPosition.fen(),
                startingPosition.sideToMove(), timeControl, startedAt);
    }

    /**
     * Advances the game by one move.
     *
     * <p>Takes a {@link MoveResult} — an already-validated outcome from the rules engine —
     * rather than a move to validate. The entity's job is state transition; legality is
     * the rules module's job. Mixing them would mean the entity needed a
     * {@code ChessRules} reference, which is how entities turn into services.
     */
    public void applyMove(MoveResult move, Instant at) {
        requireActive();

        // Charge the mover before anything else. The caller is expected to have checked
        // hasFlagged() already, so reaching here with a flagged clock is a programming
        // error rather than a game outcome — and failing loudly beats silently applying a
        // move that should never have been accepted.
        ClockCalculator.Charge charge = ClockCalculator.charge(
                msLeftFor(sideToMove), lastMoveAt, at, incrementMs);
        if (charge.flagged()) {
            throw new IllegalStateException(
                    "applyMove called on a flagged clock; check hasFlagged() first");
        }
        setMsLeftFor(sideToMove, charge.msLeft());
        this.lastMoveAt = at;

        this.fen = move.positionAfter().fen();
        this.ply += 1;
        this.sideToMove = move.sideToMove();

        // The deadline belongs to whoever must move next. Recomputed on every move or the
        // sweeper's index would point at a stale time and flag the wrong player. Computed
        // after `ply` advances, so Black's first move still gets the abort window.
        this.turnDeadline = nextDeadline(at);

        if (move.outcome().isTerminal()) {
            // The mover is whoever was to move BEFORE this move, i.e. the opponent of
            // whoever is to move now. Getting this backwards awards checkmate to the
            // player who was mated — an error no compiler catches and no casual test
            // notices, since both sides are symmetric in every other respect.
            Side mover = move.sideToMove().opponent();
            GameResult outcome = move.outcome() == GameOutcome.CHECKMATE
                    ? GameResult.winFor(mover)
                    : GameResult.DRAW;
            finish(outcome, Termination.from(move.outcome()), at);
        }
    }

    /**
     * Resigns — or, before both players have moved, aborts.
     *
     * <p>A resignation at ply 0 would award a rated win for a game in which nobody played
     * a move. That is the textbook rating-farming route: two accounts, one challenge, an
     * instant resignation, repeat. Turning it into an abort closes it with no extra rule
     * to enforce elsewhere, and it matches what players expect from every major server,
     * where the button reads "Abort" until both sides have moved.
     */
    public void resign(Side resigningSide, Instant at) {
        requireActive();
        if (awaitingFirstMove()) {
            abort(at);
            return;
        }
        finish(GameResult.winFor(resigningSide.opponent()), Termination.RESIGNATION, at);
    }

    /** True when the side to move has no time left. Cheap: arithmetic on stored values. */
    public boolean hasFlagged(Instant now) {
        return remainingMs(sideToMove, now) <= 0;
    }

    /**
     * True until both players have made a move. While this holds, an expired game is
     * aborted rather than lost on time.
     */
    public boolean awaitingFirstMove() {
        return ply < OPENING_PLIES;
    }

    /**
     * Decides what time has done to this game, if anything.
     *
     * <h2>Why a flag in the opening is an abort, not a loss</h2>
     *
     * <p>With a 10-second time control a player's clock runs out before the 30-second
     * abort window does. Treating that as a timeout would award a win for a game the loser
     * never played a move in — exactly what aborting exists to prevent. So until both
     * players have moved, <em>any</em> expiry aborts.
     *
     * <p>Boundaries match the flag rule: reaching the window exactly aborts, just as
     * reaching zero exactly flags. A {@code lastMoveAt} in the future (a clock anomaly —
     * see {@code ClockCalculator}) never expires anything.
     */
    public Expiry expiryAt(Instant now) {
        if (!isActive()) {
            return Expiry.NONE;
        }
        if (awaitingFirstMove()) {
            boolean windowClosed = !now.isBefore(lastMoveAt.plus(FIRST_MOVE_WINDOW));
            return windowClosed || hasFlagged(now) ? Expiry.ABORT : Expiry.NONE;
        }
        return hasFlagged(now) ? Expiry.FLAG : Expiry.NONE;
    }

    /**
     * Ends the game on time. The side to move lost; their opponent wins.
     *
     * <p>Their clock is zeroed so the persisted state matches what every client was
     * already showing — a finished game whose loser still has 400ms on the board would be
     * a permanent, visible inconsistency in the game record.
     */
    public void flagOnTime(Instant at) {
        requireActive();
        setMsLeftFor(sideToMove, 0);
        finish(GameResult.winFor(sideToMove.opponent()), Termination.TIMEOUT, at);
    }

    /** Remaining time for a side at a given instant. Never negative. */
    public long remainingMs(Side side, Instant now) {
        return ClockCalculator.remainingMs(side, sideToMove, whiteMsLeft, blackMsLeft,
                lastMoveAt, now);
    }

    private long msLeftFor(Side side) {
        return side == Side.WHITE ? whiteMsLeft : blackMsLeft;
    }

    private void setMsLeftFor(Side side, long msLeft) {
        if (side == Side.WHITE) {
            this.whiteMsLeft = msLeft;
        } else {
            this.blackMsLeft = msLeft;
        }
    }

    public long initialMs() {
        return initialMs;
    }

    public long incrementMs() {
        return incrementMs;
    }

    public long whiteMsLeft() {
        return whiteMsLeft;
    }

    public long blackMsLeft() {
        return blackMsLeft;
    }

    public Instant lastMoveAt() {
        return lastMoveAt;
    }

    public Instant turnDeadline() {
        return turnDeadline;
    }

    /**
     * Aborts a game in which a player never made their first move.
     *
     * <p>Deliberately no result: an aborted game was never played and must not be rated.
     * {@code ck_games_result_consistency} enforces the same thing in the database — a
     * non-FINISHED game with a result cannot be stored.
     *
     * <p>Guarded here as well as by the caller. Aborting a game both players have moved in
     * would let a losing player escape a result by walking away, which is the one thing an
     * abort rule must never allow.
     */
    public void abort(Instant at) {
        requireActive();
        if (!awaitingFirstMove()) {
            throw new IllegalStateException(
                    "cannot abort game " + id + " at ply " + ply + ": both players have moved");
        }
        this.status = GameStatus.ABORTED;
        this.termination = Termination.ABANDONED;
        this.finishedAt = at;
    }

    /**
     * When this game next needs attention if nobody moves. See {@link #turnDeadline}.
     * Reads {@code ply}, {@code sideToMove} and the stored clocks, so it must be called
     * after they have been updated for the current turn.
     */
    private Instant nextDeadline(Instant from) {
        Instant flagFall = ClockCalculator.deadline(from, msLeftFor(sideToMove));
        if (!awaitingFirstMove()) {
            return flagFall;
        }
        Instant abortAt = from.plus(FIRST_MOVE_WINDOW);
        return abortAt.isBefore(flagFall) ? abortAt : flagFall;
    }

    private void finish(GameResult outcome, Termination how, Instant at) {
        this.status = GameStatus.FINISHED;
        this.result = outcome;
        this.termination = how;
        this.finishedAt = at;
    }

    private void requireActive() {
        if (status != GameStatus.ACTIVE) {
            throw new DomainException.Rejected(
                    ErrorCode.GAME_NOT_ACTIVE, "This game has already ended.");
        }
    }

    /**
     * Which side the given user plays.
     *
     * @throws DomainException.Rejected if the user is not in this game — spectators do not
     *                                  exist yet, and a non-player has no side
     */
    public Side sideOf(UUID playerId) {
        if (playerId.equals(whitePlayerId)) {
            return Side.WHITE;
        }
        if (playerId.equals(blackPlayerId)) {
            return Side.BLACK;
        }
        throw new DomainException.Rejected(
                ErrorCode.NOT_A_PLAYER, "You are not a player in this game.");
    }

    public UUID playerOn(Side side) {
        return side == Side.WHITE ? whitePlayerId : blackPlayerId;
    }

    public boolean isActive() {
        return status == GameStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID whitePlayerId() {
        return whitePlayerId;
    }

    public UUID blackPlayerId() {
        return blackPlayerId;
    }

    public GameStatus status() {
        return status;
    }

    public GameResult result() {
        return result;
    }

    public Termination termination() {
        return termination;
    }

    public String fen() {
        return fen;
    }

    public Position position() {
        return new Position(fen);
    }

    public int ply() {
        return ply;
    }

    public Side sideToMove() {
        return sideToMove;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Game game && Objects.equals(id, game.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Game[id=%s, status=%s, ply=%d, toMove=%s, v=%d]"
                .formatted(id, status, ply, sideToMove, version);
    }
}
