package com.chessplatform.matchmaking;

import com.chessplatform.game.TimeControl;

import java.util.UUID;

/**
 * Where a player stands in matchmaking after a seek or cancel.
 *
 * @param timeControl the queue concerned; null only for {@link Status#NOT_SEEKING}
 * @param gameId      set only for {@link Status#MATCHED}
 */
public record SeekResult(Status status, TimeControl timeControl, UUID gameId) {

    public enum Status {
        /** In the queue (or still in it — a repeated seek is a heartbeat, not an error). */
        QUEUED,
        /** Claimed by a pairing; the game is being created. Wait for the match. */
        PAIRING,
        /** Paired, and the game exists. */
        MATCHED,
        /** Left the queue at the player's request. */
        CANCELLED,
        /** Nothing to cancel. */
        NOT_SEEKING
    }

    static SeekResult of(Status status, TimeControl timeControl) {
        return new SeekResult(status, timeControl, null);
    }

    static SeekResult matched(UUID gameId) {
        return new SeekResult(Status.MATCHED, null, gameId);
    }
}
