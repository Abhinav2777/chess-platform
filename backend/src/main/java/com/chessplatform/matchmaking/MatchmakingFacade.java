package com.chessplatform.matchmaking;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.common.resilience.ValkeyGuard;
import com.chessplatform.game.GameFacade;
import com.chessplatform.game.TimeControl;
import com.chessplatform.identity.IdentityFacade;
import com.chessplatform.matchmaking.internal.MatchQueue;
import com.chessplatform.matchmaking.internal.MatchmakingProperties;
import com.chessplatform.matchmaking.internal.QueueTimeControls;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The matchmaking module's public surface: join, re-assert and leave the queue, and find
 * out about a match that has not been seen yet. Pairing itself runs in the background
 * ({@code Matchmaker}) and announces results as {@link MatchFound} events.
 *
 * <h2>A seek is idempotent, and that is load-bearing</h2>
 *
 * <p>Seeking again for the same time control is not an error — it refreshes the seek's TTL
 * and, if the entry was lost, repairs it. The client re-seeks every few seconds while it
 * waits, so one message is at once the heartbeat that keeps the seek alive and the
 * recovery path after a Valkey restart or an instance dying mid-pairing (ADR-016).
 */
@Service
@EnableConfigurationProperties(MatchmakingProperties.class)
public class MatchmakingFacade {

    private final MatchQueue queue;
    private final ValkeyGuard guard;
    private final GameFacade games;
    private final IdentityFacade identity;
    private final Counter seeks;

    public MatchmakingFacade(MatchQueue queue, ValkeyGuard guard, GameFacade games,
                             IdentityFacade identity, MeterRegistry metrics) {
        this.queue = queue;
        this.guard = guard;
        this.games = games;
        this.identity = identity;
        this.seeks = Counter.builder("chess.matchmaking.seeks")
                .description("Seek requests, including re-assertions")
                .register(metrics);
    }

    /**
     * Joins the queue for a time control, or re-asserts an existing seek.
     *
     * @throws DomainException.Rejected    unsupported time control, already seeking another
     *                                     one, or already playing
     * @throws DomainException.Unavailable Valkey is unreachable
     */
    public SeekResult seek(UUID userId, TimeControl timeControl) {
        if (!QueueTimeControls.isSupported(timeControl)) {
            throw new DomainException.Rejected(ErrorCode.UNSUPPORTED_TIME_CONTROL,
                    "There is no queue for that time control.");
        }
        seeks.increment();

        // A match the player has not seen yet takes precedence over everything below —
        // including the active-game check, which the new game itself would otherwise fail.
        Optional<SeekResult> alreadyMatched = valkey(() -> queue.matchOf(userId))
                .map(MatchmakingFacade::fromMatchValue);
        if (alreadyMatched.isPresent()) {
            return alreadyMatched.get();
        }

        // One game at a time. Not atomic with the queue (it is a PostgreSQL read), so a
        // player who accepts a direct challenge in the same instant could still be paired;
        // they would then have two games, which the abort window cleans up. Not worth a
        // distributed transaction.
        if (games.hasActiveGame(userId)) {
            throw new DomainException.Rejected(ErrorCode.ALREADY_IN_GAME,
                    "Finish your current game before looking for another.");
        }

        int rating = identity.getById(userId).rating();
        String name = QueueTimeControls.nameOf(timeControl);
        MatchQueue.Outcome outcome = valkey(() -> queue.seek(userId, rating, name));

        return switch (outcome.status()) {
            case "QUEUED" -> SeekResult.of(SeekResult.Status.QUEUED, timeControl);
            case "MATCHED" -> fromMatchValue(outcome.detail());
            case "ALREADY_SEEKING" -> throw new DomainException.Rejected(ErrorCode.ALREADY_SEEKING,
                    "Already looking for a %s game. Cancel that first.".formatted(outcome.detail()));
            default -> throw new IllegalStateException("unexpected seek outcome " + outcome);
        };
    }

    /** Leaves the queue. Too late once paired: the result says MATCHED or PAIRING instead. */
    public SeekResult cancel(UUID userId) {
        MatchQueue.Outcome outcome = valkey(() -> queue.cancel(userId));
        return switch (outcome.status()) {
            case "CANCELLED" -> SeekResult.of(SeekResult.Status.CANCELLED,
                    QueueTimeControls.byName(outcome.detail()).orElse(null));
            case "NOT_SEEKING" -> SeekResult.of(SeekResult.Status.NOT_SEEKING, null);
            case "MATCHED" -> fromMatchValue(outcome.detail());
            default -> throw new IllegalStateException("unexpected cancel outcome " + outcome);
        };
    }

    /**
     * A created game this player has not acknowledged, if any — the pull half of match
     * delivery. The push ({@link MatchFound} over the socket) is fire-and-forget; a client
     * reconnecting at the wrong moment misses it, and asks here instead. Empty rather than
     * failing when Valkey is down: this is a courtesy on reconnect, not a request.
     */
    public Optional<UUID> unseenMatch(UUID userId) {
        return guard.call(() -> queue.matchOf(userId)
                .filter(value -> !MatchQueue.PENDING_VALUE.equals(value))
                .map(UUID::fromString), Optional::empty);
    }

    /** The player has opened the game; stop re-announcing it. Best effort. */
    public void acknowledge(UUID userId, UUID gameId) {
        // Best effort through the circuit; the record expires on its own (match-ttl).
        guard.run(() -> queue.acknowledge(userId, gameId));
    }

    private static SeekResult fromMatchValue(String value) {
        return MatchQueue.PENDING_VALUE.equals(value)
                ? SeekResult.of(SeekResult.Status.PAIRING, null)
                : SeekResult.matched(UUID.fromString(value));
    }

    /** Through the circuit: while it is open, a seek is refused at once rather than after a timeout. */
    private <T> T valkey(Supplier<T> call) {
        return guard.call(call, () -> {
            throw new DomainException.Unavailable(ErrorCode.MATCHMAKING_UNAVAILABLE,
                    "Matchmaking is temporarily unavailable. Direct challenges still work.");
        });
    }
}
