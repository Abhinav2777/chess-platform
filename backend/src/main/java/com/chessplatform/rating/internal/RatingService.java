package com.chessplatform.rating.internal;

import com.chessplatform.game.GameFinished;
import com.chessplatform.identity.IdentityFacade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one finished game to both players' ratings — exactly once, however many times the
 * event is delivered.
 *
 * <h2>Why this needs care</h2>
 *
 * <p>{@code rating += delta} is not idempotent: SQS Standard delivers at least once, the relay
 * may send twice, and a consumer that crashes after committing but before acknowledging will
 * see the message again. Applied twice, a win counts twice — silently.
 *
 * <h2>One transaction, in this order</h2>
 *
 * <ol>
 *   <li><strong>Claim the event</strong> in {@code processed_events} with {@code ON CONFLICT DO
 *       NOTHING}. Zero rows inserted means it was already applied: stop. Not a caught
 *       primary-key violation — in PostgreSQL a failed statement aborts the transaction.
 *       Concurrent duplicates are handled too: the second insert waits on the first's index
 *       entry, then sees the conflict.</li>
 *   <li><strong>Lock both players</strong> ({@code FOR UPDATE}, id order — no deadlocks).</li>
 *   <li><strong>Compute Elo from the ratings just read</strong>, write both, record history.</li>
 * </ol>
 *
 * <p>All or nothing. A crash anywhere before commit rolls back the claim with the change, so
 * redelivery applies it once. A crash after commit leaves the claim, so redelivery skips it.
 */
@Service
public class RatingService {

    private static final Logger log = LoggerFactory.getLogger(RatingService.class);
    static final String CONSUMER = "rating";

    public enum Outcome { APPLIED, DUPLICATE, SKIPPED_PLAYER_GONE }

    private final JdbcTemplate jdbc;
    private final IdentityFacade identity;
    private final Counter applied;
    private final Counter duplicates;

    public RatingService(JdbcTemplate jdbc, IdentityFacade identity, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.applied = Counter.builder("chess.rating.applied")
                .description("Games applied to ratings").register(metrics);
        // Not an error: at-least-once delivery working as designed. A counter that never
        // moves would be the suspicious one — it would mean the dedupe path is untested in
        // production.
        this.duplicates = Counter.builder("chess.rating.duplicates")
                .description("Redelivered events recognised and skipped").register(metrics);
    }

    @Transactional
    public Outcome apply(UUID eventId, GameFinished game) {
        int claimed = jdbc.update(
                "INSERT INTO processed_events (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                CONSUMER, eventId);
        if (claimed == 0) {
            duplicates.increment();
            log.info("Event {} for game {} already applied; skipping", eventId, game.gameId());
            return Outcome.DUPLICATE;
        }

        Map<UUID, Integer> ratings = identity.lockRatings(List.of(game.whitePlayerId(), game.blackPlayerId()));
        Integer white = ratings.get(game.whitePlayerId());
        Integer black = ratings.get(game.blackPlayerId());
        if (white == null || black == null) {
            // A deleted account. Nothing to rate; the claim stays so the event is not retried.
            log.warn("Game {} not rated: a player no longer exists", game.gameId());
            return Outcome.SKIPPED_PLAYER_GONE;
        }

        Elo.Change change = Elo.change(white, black, game.result().whiteScore());
        record(game, eventId, game.whitePlayerId(), white, change.white());
        record(game, eventId, game.blackPlayerId(), black, change.black());
        applied.increment();
        log.info("Rated game {}: white {} ({}{}), black {} ({}{})", game.gameId(),
                white, change.white() >= 0 ? "+" : "", change.white(),
                black, change.black() >= 0 ? "+" : "", change.black());
        return Outcome.APPLIED;
    }

    private void record(GameFinished game, UUID eventId, UUID userId, int before, int delta) {
        int after = Math.clamp(before + delta, 0, 4000);
        identity.setRating(userId, after);
        // Plain INSERT, deliberately: if this row already exists, a second event id was minted
        // for the same game. That is a bug upstream, and it should fail — roll back, retry,
        // and land in the DLQ where someone sees it — not be quietly absorbed.
        jdbc.update("""
                INSERT INTO rating_history (game_id, user_id, event_id, rating_before, rating_after, delta)
                VALUES (?, ?, ?, ?, ?, ?)
                """, game.gameId(), userId, eventId, before, after, after - before);
    }
}
