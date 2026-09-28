package com.chessplatform.matchmaking;

import com.chessplatform.game.TimeControl;

import java.util.UUID;

/**
 * Published after a pairing has become a committed game.
 *
 * <p>An application event rather than a call into {@code realtime}, because
 * {@code realtime} already depends on this module (it forwards {@code SEEK} from the
 * socket). A direct call back would be a module cycle, which {@code ModuleBoundaryTest}
 * forbids. The same shape as {@code GameEvents} → {@code GameEventBroadcaster}.
 *
 * <p>Published only after the game row is committed, so a listener that tells a player
 * "your game is ready" can never point them at a game that does not exist.
 */
public record MatchFound(UUID gameId, UUID whitePlayerId, UUID blackPlayerId,
                         TimeControl timeControl) {
}
