package com.chessplatform.matchmaking.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link Matchmaker#tick()} once a second.
 *
 * <p>A separate bean for the same reason as {@code TimeoutSweeper}: scheduling is the only
 * thing it does, so tests can switch it off by property and call {@code tick()} themselves
 * without racing a background thread — and nothing is ever invoked on {@code this}.
 *
 * <p>One second bounds how long two compatible players wait for each other once both are
 * queued. Pairing on every seek as well would shave that second off; it is not worth a
 * second code path while the window-expansion rule needs a periodic pass anyway.
 */
@Component
@ConditionalOnProperty(name = "chess.matchmaking.scheduler-enabled", matchIfMissing = true)
public class MatchmakerScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchmakerScheduler.class);

    private final Matchmaker matchmaker;

    public MatchmakerScheduler(Matchmaker matchmaker) {
        this.matchmaker = matchmaker;
    }

    @Scheduled(fixedDelay = 1_000)
    public void run() {
        try {
            matchmaker.tick();
        } catch (RuntimeException failure) {
            // Never propagate: an exception out of a @Scheduled method cancels the schedule
            // for the life of the process. Valkey being down is the expected case here, so
            // it is logged briefly, not with a trace every second.
            log.warn("Matchmaking tick failed; retrying on the next tick: {}", failure.toString());
        }
    }
}
