package com.chessplatform.realtime.internal;

import com.chessplatform.rating.RatingsChanged;
import com.chessplatform.realtime.protocol.Envelope;
import com.chessplatform.realtime.protocol.Payloads;
import com.chessplatform.realtime.protocol.ServerMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells each player their new rating once the rating transaction has committed.
 *
 * <p>Through {@link UserNotifier}, not a game channel: the rating worker that applied the
 * change is usually not the instance holding the players' sockets, and by now the game view
 * may already be closed. The user channel reaches whichever instance they are on.
 *
 * <p>Fire-and-forget, like every notification here: a player who misses it sees the current
 * rating in the lobby, read from the database.
 */
@Component
public class RatingAnnouncer {

    private final UserNotifier notifier;

    public RatingAnnouncer(UserNotifier notifier) {
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRatingsChanged(RatingsChanged changed) {
        for (RatingsChanged.Change change : changed.changes()) {
            notifier.notify(change.userId(), Envelope.of(ServerMessage.RATING_UPDATED,
                    new Payloads.RatingUpdated(changed.gameId(), change.after(), change.delta())));
        }
    }
}
