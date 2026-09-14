package com.chessplatform.game.domain;

import com.chessplatform.chess.GameOutcome;
import com.chessplatform.chess.MoveResult;
import com.chessplatform.chess.Position;
import com.chessplatform.chess.Side;
import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.Termination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Game() {
    }

    private Game(UUID id, UUID whitePlayerId, UUID blackPlayerId,
                 String fen, Side sideToMove, Instant createdAt) {
        this.id = id;
        this.whitePlayerId = whitePlayerId;
        this.blackPlayerId = blackPlayerId;
        this.status = GameStatus.ACTIVE;
        this.fen = fen;
        this.ply = 0;
        this.sideToMove = sideToMove;
        this.createdAt = createdAt;
    }

    public static Game start(UUID id, UUID whitePlayerId, UUID blackPlayerId,
                             Position startingPosition, Instant createdAt) {
        if (whitePlayerId.equals(blackPlayerId)) {
            throw new IllegalArgumentException("a player cannot play themselves");
        }
        return new Game(id, whitePlayerId, blackPlayerId,
                startingPosition.fen(), startingPosition.sideToMove(), createdAt);
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
        this.fen = move.positionAfter().fen();
        this.ply += 1;
        this.sideToMove = move.sideToMove();

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

    public void resign(Side resigningSide, Instant at) {
        requireActive();
        finish(GameResult.winFor(resigningSide.opponent()), Termination.RESIGNATION, at);
    }

    public void abort(Instant at) {
        requireActive();
        this.status = GameStatus.ABORTED;
        this.finishedAt = at;
        // Deliberately no result: an aborted game was never played and must not be rated.
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
