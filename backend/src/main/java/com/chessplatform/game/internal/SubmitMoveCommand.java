package com.chessplatform.game.internal;

import com.chessplatform.chess.MoveIntent;

import java.util.UUID;

/**
 * A request to play a move.
 *
 * @param clientMoveId client-generated idempotency key. Without it a client whose
 *                     acknowledgement is lost must choose between losing the move and
 *                     playing it twice — and in a rated game both are unacceptable.
 * @param expectedPly  the ply the client believes the game is at. Optimistic concurrency
 *                     at the protocol level: a client that missed its opponent's move
 *                     submits the wrong ply and is told to resync, instead of silently
 *                     playing into a position it cannot see.
 */
public record SubmitMoveCommand(UUID clientMoveId, int expectedPly, MoveIntent intent) {
}
