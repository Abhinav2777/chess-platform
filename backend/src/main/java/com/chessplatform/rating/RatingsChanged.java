package com.chessplatform.rating;

import java.util.List;
import java.util.UUID;

/**
 * Published inside the rating transaction when a game changed ratings; for listeners that
 * must act only after it commits ({@code @TransactionalEventListener(AFTER_COMMIT)}).
 *
 * <p>Never published for a duplicate delivery — the dedupe claim stops the work before this
 * point — so a player is told about a rating change exactly as many times as it happened.
 */
public record RatingsChanged(UUID gameId, List<Change> changes) {

    public RatingsChanged {
        changes = List.copyOf(changes);
    }

    public record Change(UUID userId, int before, int after) {
        public int delta() {
            return after - before;
        }
    }
}
