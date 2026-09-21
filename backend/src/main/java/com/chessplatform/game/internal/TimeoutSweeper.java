package com.chessplatform.game.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically finalises games whose clock has run out.
 *
 * <p>The clock is computed rather than ticked (ADR-006), so nothing notices a flag-fall by
 * itself. Moves and reads catch it lazily; this catches the case they cannot — both players
 * walked away and nobody will ever ask.
 *
 * <h2>Deliberately not transactional</h2>
 *
 * <p>This class only schedules. The transactional work lives in {@link GameTimeouts} and is
 * reached through Spring's proxy, because the first version called its own
 * {@code @Transactional} method via {@code this} — which bypasses the proxy entirely. There
 * was no transaction, nothing was saved, and the sweeper silently finalised nothing.
 *
 * <p>Keeping the transaction <em>out</em> of this class also matters for the error
 * handling below. Were {@code sweep()} itself transactional, a database failure would mark
 * the transaction rollback-only; the catch block would swallow the exception, the proxy
 * would then attempt to commit, throw {@code UnexpectedRollbackException} outside the
 * catch, and that exception would cancel the schedule.
 */
@Component
@ConditionalOnProperty(name = "chess.clock.sweeper-enabled", matchIfMissing = true)
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    private final GameTimeouts timeouts;

    public TimeoutSweeper(GameTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: fixed rate schedules from the previous
     * run's <em>start</em>, so a slow sweep overlaps itself and the backlog compounds.
     */
    @Scheduled(fixedDelay = 1_000)
    public void sweep() {
        try {
            int count = timeouts.finaliseExpiredBatch();
            if (count > 0) {
                log.info("Finalised {} game(s) on time", count);
            }
        } catch (RuntimeException failure) {
            // Never propagate: an exception out of a @Scheduled method cancels the schedule
            // for the life of the process, so one database blip would end all timeout
            // handling until a restart.
            log.warn("Timeout sweep failed; retrying on the next tick", failure);
        }
    }
}
