package com.chessplatform.game;

import java.util.UUID;

/**
 * The {@code GAME_FINISHED} message: a game ended with a result. The contract between the
 * game module and every asynchronous consumer (the rating worker first).
 *
 * <p><strong>Only rated endings.</strong> An aborted game (ADR-014) never produces one, so a
 * consumer cannot rate a game nobody played even by mistake. {@code result} is therefore
 * never null.
 *
 * <p>{@code schemaVersion} is in the payload from day one. Queued messages outlive deploys:
 * a consumer running the new code will read messages the old code wrote, and needs a way to
 * tell them apart once the shape changes.
 */
public record GameFinished(int schemaVersion,
                           UUID gameId,
                           UUID whitePlayerId,
                           UUID blackPlayerId,
                           GameResult result,
                           Termination termination) {

    public static final String TYPE = "GAME_FINISHED";
    public static final int SCHEMA_VERSION = 1;
}
