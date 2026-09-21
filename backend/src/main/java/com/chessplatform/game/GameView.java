package com.chessplatform.game;

import com.chessplatform.chess.Side;

import java.time.Instant;
import java.util.UUID;

/**
 * The game module's public view of a game.
 *
 * <p>Other modules receive this, never the {@code Game} entity — enforced by ArchUnit
 * (ADR-001). The {@code rating} module in Phase 5 will consume exactly this shape, and
 * because it is already the contract, extracting {@code rating} later requires no change
 * to this side of the boundary.
 */
public record GameView(UUID id,
                       UUID whitePlayerId,
                       UUID blackPlayerId,
                       GameStatus status,
                       GameResult result,
                       Termination termination,
                       String fen,
                       int ply,
                       Side sideToMove,
                       long initialMs,
                       long incrementMs,
                       long whiteMsLeft,
                       long blackMsLeft,
                       Instant lastMoveAt,
                       Instant createdAt,
                       Instant finishedAt) {

    /**
     * Remaining time for a side as of {@code now}.
     *
     * <p>Derived, never stored — which is the whole point of ADR-006. The side to move has
     * been spending since {@code lastMoveAt}; the other side's clock is frozen.
     */
    public long remainingMs(Side side, Instant now) {
        return com.chessplatform.game.ClockCalculator.remainingMs(
                side, sideToMove, whiteMsLeft, blackMsLeft, lastMoveAt, now);
    }
}
