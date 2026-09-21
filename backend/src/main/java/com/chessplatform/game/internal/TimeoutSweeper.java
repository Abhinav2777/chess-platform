package com.chessplatform.game.internal;

import com.chessplatform.game.GameEvents;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finalises games whose clock has run out.
 *
 * <h2>Why anything is needed at all</h2>
 *
 * <p>The clock is computed rather than ticked (ADR-006), so nothing notices a flag-fall by
 * itself. Two layers cover it. <strong>Lazily</strong>, any move or read evaluates the
 * deadline — which handles the common case, because the opponent is usually watching and
 * will act. <strong>Actively</strong>, this sweeper handles the case that laziness cannot:
 * both players walked away, and nobody will ever ask.
 *
 * <p>Without the sweeper such a game stays ACTIVE forever, appears in both players' lists,
 * and is never rated.
 *
 * <h2>Why it is cheap</h2>
 *
 * <p>{@code turn_deadline} is a stored, partially-indexed column, so the query is an index
 * scan bounded by the number of <em>expired</em> games — not by the number of games. At
 * rest it touches nothing. Computing deadlines on the fly would make this a full scan
 * every second, which is the difference between a background job and an outage.
 *
 * <h2>Concurrency</h2>
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets every replica sweep at once, taking disjoint
 * batches, with no leader election and no distributed lock. A game being finalised here
 * while its player submits a move is resolved by the optimistic lock: whichever commits
 * second sees a version conflict, and a move arriving after a flag correctly loses.
 */
@Component
@ConditionalOnProperty(name = "chess.clock.sweeper-enabled", matchIfMissing = true)
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    /**
     * Bounded so a backlog is drained over several sweeps rather than in one transaction
     * holding hundreds of row locks while the move pipeline waits behind it.
     */
    private static final int BATCH_SIZE = 100;

    private final GameRepository games;
    private final ServerClock serverClock;
    private final ApplicationEventPublisher events;
    private final Counter finalised;

    public TimeoutSweeper(GameRepository games, ServerClock serverClock,
                          ApplicationEventPublisher events, MeterRegistry metrics) {
        this.games = games;
        this.serverClock = serverClock;
        this.events = events;
        // Evidence the sweeper actually fires. A counter that never moves means either
        // nobody abandons games or the job is dead, and those look identical without it.
        this.finalised = Counter.builder("chess.clock.timeouts")
                .description("Games finalised on time by the sweeper")
                .register(metrics);
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}. Fixed rate schedules the next run a fixed
     * interval after the previous one <em>started</em>, so a sweep that takes longer than
     * the interval overlaps with itself and the backlog compounds. Fixed delay measures
     * from completion, so a slow sweep simply runs less often.
     */
    @Scheduled(fixedDelay = 1_000)
    public void sweep() {
        try {
            int count = finaliseExpiredBatch();
            if (count > 0) {
                log.info("Finalised {} game(s) on time", count);
            }
        } catch (RuntimeException failure) {
            // Never propagate: an exception out of a @Scheduled method with fixedDelay
            // cancels the schedule for the remaining life of the process. A transient
            // database blip would silently stop all timeout handling until a restart.
            log.warn("Timeout sweep failed; will retry on the next tick", failure);
        }
    }

    @Transactional
    public int finaliseExpiredBatch() {
        List<UUID> expired = games.claimExpired(BATCH_SIZE);
        if (expired.isEmpty()) {
            return 0;
        }

        Instant now = serverClock.now();
        for (UUID gameId : expired) {
            games.findById(gameId).ifPresent(game -> {
                // Re-checked after claiming. The row was ACTIVE and expired when the
                // predicate ran, but a move could have committed in between — the claim
                // locks the row, it does not freeze time.
                if (game.isActive() && game.hasFlagged(now)) {
                    game.flagOnTime(now);
                    events.publishEvent(new GameEvents.GameEnded(game.id(),
                            game.whitePlayerId(), game.blackPlayerId(),
                            game.result(), game.termination()));
                    finalised.increment();
                }
            });
        }
        return expired.size();
    }

    /** Test seam: lets a test drive a sweep deterministically rather than waiting a tick. */
    public int sweepNow() {
        return finaliseExpiredBatch();
    }
}
