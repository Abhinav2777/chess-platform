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
                       Instant createdAt,
                       Instant finishedAt) {
}
