package com.chessplatform.game.domain;

import com.chessplatform.chess.MoveResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One move, appended to a game's log. Never updated, never deleted except by game cascade.
 *
 * <p>Named {@code MoveRecord} rather than {@code Move} to avoid colliding with chesslib's
 * {@code Move} and with the domain's {@code MoveIntent}/{@code MoveResult}. Three types
 * called Move in one codebase is how import lists become a source of bugs.
 *
 * <p>This table, not {@code games.fen}, is the authoritative record of a game: replaying
 * it reproduces every position. That is what lets the FEN column be treated as a cache
 * (ADR-004) and what will make repetition detection a query rather than a rewrite.
 */
@Entity
@Table(name = "moves")
@IdClass(MoveRecordId.class)
public class MoveRecord {

    @Id
    @Column(name = "game_id", nullable = false, updatable = false)
    private UUID gameId;

    @Id
    @Column(name = "ply", nullable = false, updatable = false)
    private int ply;

    @Column(name = "uci", nullable = false, length = 6, updatable = false)
    private String uci;

    @Column(name = "san", nullable = false, length = 10, updatable = false)
    private String san;

    @Column(name = "fen_after", nullable = false, length = 100, updatable = false)
    private String fenAfter;

    /**
     * Client-generated idempotency key, unique per game via {@code uq_moves_client_id}.
     * The mechanism that makes a retried move safe: a duplicate hits the constraint
     * instead of being applied twice (ADR-005).
     */
    @Column(name = "client_move_id", nullable = false, updatable = false)
    private UUID clientMoveId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MoveRecord() {
    }

    private MoveRecord(UUID gameId, int ply, String uci, String san, String fenAfter,
                       UUID clientMoveId, Instant createdAt) {
        this.gameId = gameId;
        this.ply = ply;
        this.uci = uci;
        this.san = san;
        this.fenAfter = fenAfter;
        this.clientMoveId = clientMoveId;
        this.createdAt = createdAt;
    }

    public static MoveRecord of(UUID gameId, int ply, MoveResult result,
                                UUID clientMoveId, Instant at) {
        return new MoveRecord(gameId, ply, result.uci(), result.san(),
                result.positionAfter().fen(), clientMoveId, at);
    }

    public UUID gameId() {
        return gameId;
    }

    public int ply() {
        return ply;
    }

    public String uci() {
        return uci;
    }

    public String san() {
        return san;
    }

    public String fenAfter() {
        return fenAfter;
    }

    public UUID clientMoveId() {
        return clientMoveId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
