package com.chessplatform.game.internal;

import com.chessplatform.game.GameEvents;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ends games because time passed: a timeout when a player's clock runs out, an abort when
 * a player never makes their first move. The only place either is ever written.
 *
 * <h2>One mechanism for both</h2>
 *
 * <p>Timeouts and aborts are the same shape of problem — "nobody moved before a deadline"
 * — so they share one stored deadline, one partial index, one {@code SKIP LOCKED} query and
 * one sweeper. The entity's {@link Game#expiryAt} decides which outcome applies. A second
 * sweeper with its own query for aborts would double the background load and, worse, give
 * two jobs the power to end the same game in different ways.
 *
 * <h2>Why this is its own bean</h2>
 *
 * <p>Two bugs shipped because this logic lived inside other classes, and both were
 * failures of rules this project had already written down.
 *
 * <p><strong>Write-then-throw.</strong> The move pipeline finalised a flagged game and then
 * threw {@code OUT_OF_TIME}. {@code submitMove} is transactional, Spring rolls back on a
 * {@code RuntimeException}, and the finalisation vanished — the game stayed ACTIVE forever.
 * The same bug as refresh-token revocation in ADR-013.
 *
 * <p><strong>Self-invocation.</strong> The sweeper called its own {@code @Transactional}
 * method via {@code this}, which bypasses Spring's proxy, so there was no transaction at
 * all. The entity was mutated while detached and never saved. <em>The production sweeper
 * never finalised a single game.</em> The same rule as {@code TokenFamilyRevoker}.
 *
 * <p>Moving both paths here, and calling them only from other beans, makes both
 * structurally impossible rather than something to remember.
 *
 * <h2>Two methods, two transaction shapes — and why they must differ</h2>
 *
 * <p>{@link #finaliseIfExpired} uses {@code REQUIRES_NEW} so the write survives the
 * caller's exception. That is safe because the move pipeline holds no lock on the row: it
 * read the game with a plain SELECT and relies on optimistic locking.
 *
 * <p>{@link #finaliseExpiredBatch} must <strong>not</strong> use {@code REQUIRES_NEW}. It
 * claims rows with {@code FOR UPDATE SKIP LOCKED}, and that lock is held by the claiming
 * transaction. A separate inner transaction updating the same row would wait for a lock
 * held by its own caller — which is itself waiting for the inner call to return. PostgreSQL
 * cannot detect that as a deadlock, because one side is waiting in application code rather
 * than on a lock; it simply hangs until the lock timeout. Claim and update must share one
 * transaction.
 */
@Service
public class GameTimeouts {

    /**
     * Bounded so a backlog drains over several sweeps rather than in one transaction
     * holding hundreds of row locks while the move pipeline waits behind it.
     */
    private static final int BATCH_SIZE = 100;

    private final GameRepository games;
    private final ServerClock serverClock;
    private final ApplicationEventPublisher events;
    private final Counter timeouts;
    private final Counter aborts;

    public GameTimeouts(GameRepository games, ServerClock serverClock,
                        ApplicationEventPublisher events, MeterRegistry metrics) {
        this.games = games;
        this.serverClock = serverClock;
        this.events = events;
        // Evidence the paths actually fire. A counter that never moves means either nobody
        // abandons games or the job is dead — and for one milestone, it was the second.
        this.timeouts = Counter.builder("chess.clock.timeouts")
                .description("Games finalised on time")
                .register(metrics);
        this.aborts = Counter.builder("chess.game.aborts")
                .description("Games aborted because a player never made a first move")
                .register(metrics);
    }

    /**
     * Ends one game on time or aborts it, in its own transaction.
     *
     * <p>Called by the move pipeline when the game has already expired. The caller then
     * throws to reject the move; {@code REQUIRES_NEW} is what lets this write survive that.
     *
     * <p>Re-reads the game and re-decides rather than trusting the caller's copy: by the
     * time this runs the sweeper may already have finalised it, and a second finalisation
     * would overwrite a correct result.
     *
     * @return what this call did — {@link Game.Expiry#NONE} if the game was already over
     *         or, on re-reading, had not expired after all
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Game.Expiry finaliseIfExpired(UUID gameId) {
        Instant now = serverClock.now();
        return games.findById(gameId)
                .map(game -> finalise(game, now))
                .orElse(Game.Expiry.NONE);
    }

    /**
     * Claims and finalises a batch of expired games. Used by the sweeper.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets every replica sweep at once, taking disjoint
     * batches, with no leader election and no distributed lock. A row locked by one sweeper
     * is invisible to the others until that transaction commits, by which time it is no
     * longer ACTIVE — so double-finalisation cannot happen.
     *
     * @return number of games claimed in this batch
     */
    @Transactional
    public int finaliseExpiredBatch() {
        List<UUID> expired = games.claimExpired(BATCH_SIZE);
        if (expired.isEmpty()) {
            return 0;
        }
        Instant now = serverClock.now();
        // Loaded inside the claiming transaction, so each entity is MANAGED: the changes
        // below are dirty-checked and flushed at commit without an explicit save.
        for (UUID gameId : expired) {
            games.findById(gameId).ifPresent(game -> finalise(game, now));
        }
        return expired.size();
    }

    private Game.Expiry finalise(Game game, Instant now) {
        // Re-decided after claiming: the row was expired when the predicate ran, but a move
        // could have committed in between. Claiming locks the row; it does not freeze time.
        Game.Expiry expiry = game.expiryAt(now);
        switch (expiry) {
            case NONE -> {
                return expiry;
            }
            case ABORT -> {
                game.abort(now);
                aborts.increment();
            }
            case FLAG -> {
                game.flagOnTime(now);
                timeouts.increment();
            }
        }
        // Published inside the transaction and delivered AFTER_COMMIT, so no client hears
        // about an ending that did not persist.
        events.publishEvent(new GameEvents.GameEnded(game.id(),
                game.whitePlayerId(), game.blackPlayerId(),
                game.status(), game.result(), game.termination()));
        return expiry;
    }
}
