package com.chessplatform.matchmaking.internal;

import com.chessplatform.game.GameFacade;
import com.chessplatform.game.GameView;
import com.chessplatform.game.TimeControl;
import com.chessplatform.matchmaking.MatchFound;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns claimed pairs into games.
 *
 * <h2>Runs on every instance, with no coordination</h2>
 *
 * <p>No leader election and no lock. Two instances ticking at the same instant both call
 * {@code pair.lua}; Valkey runs one script, then the other, and the second finds the first
 * pair already gone. A player can be claimed once. That is the property the 20-player test
 * attacks with several matchmakers at once.
 *
 * <h2>The commit point is PostgreSQL</h2>
 *
 * <p>After the script claims a pair, the players are out of the queue and marked PENDING,
 * but nothing durable exists yet. The game row is the commit. If this instance dies in
 * between, the PENDING markers expire and the players' next re-seek queues them again: a
 * few seconds' delay, never a lost player and never a half-created game.
 *
 * <p>Deliberately not {@code @Transactional}. Game creation has its own transaction inside
 * {@code GameFacade.startGame}, so when it returns the game is committed and may be
 * announced. Wrapping the loop would hold one transaction open across many Valkey calls
 * and delay every announcement to the end of the tick.
 */
@Component
public class Matchmaker {

    private static final Logger log = LoggerFactory.getLogger(Matchmaker.class);

    private final MatchQueue queue;
    private final GameFacade games;
    private final ApplicationEventPublisher events;
    private final MatchmakingProperties properties;

    private final Counter matches;
    private final Counter pairingFailures;
    private final Timer waitTime;

    public Matchmaker(MatchQueue queue, GameFacade games, ApplicationEventPublisher events,
                      MatchmakingProperties properties, MeterRegistry metrics) {
        this.queue = queue;
        this.games = games;
        this.events = events;
        this.properties = properties;
        this.matches = Counter.builder("chess.matchmaking.matches")
                .description("Pairs that became games")
                .register(metrics);
        this.pairingFailures = Counter.builder("chess.matchmaking.pairing_failures")
                .description("Claimed pairs whose game could not be created")
                .register(metrics);
        // The product metric: how long people wait. A histogram, because the average hides
        // exactly the players who matter — the 2400 waiting three minutes for an opponent.
        this.waitTime = Timer.builder("chess.matchmaking.wait")
                .description("Time from joining the queue to being paired")
                .publishPercentileHistogram()
                .register(metrics);
    }

    /**
     * One pass over every queue. Returns the number of games created.
     *
     * <p>Bounded per queue by {@code pairs-per-tick}, so one busy queue cannot starve the
     * others or make a tick unboundedly long.
     */
    public int tick() {
        int created = 0;
        for (TimeControl timeControl : QueueTimeControls.SUPPORTED) {
            String name = QueueTimeControls.nameOf(timeControl);
            for (int i = 0; i < properties.pairsPerTick(); i++) {
                Optional<MatchQueue.Pairing> pairing = queue.pairOne(name);
                if (pairing.isEmpty()) {
                    break;
                }
                if (start(pairing.get(), timeControl)) {
                    created++;
                }
            }
        }
        return created;
    }

    private boolean start(MatchQueue.Pairing pairing, TimeControl timeControl) {
        // Colours at random. The first player returned is the longest waiter; giving them
        // White would make "queue early" a small, systematic advantage.
        boolean firstIsWhite = ThreadLocalRandom.current().nextBoolean();
        UUID white = firstIsWhite ? pairing.first() : pairing.second();
        UUID black = firstIsWhite ? pairing.second() : pairing.first();

        GameView game;
        try {
            game = games.startGame(white, black, timeControl);
        } catch (RuntimeException creationFailed) {
            // PostgreSQL refused or is down. Release the claims now rather than letting them
            // lapse, so the players' next re-seek queues them immediately.
            pairingFailures.increment();
            log.warn("Could not create a game for pair {} / {}; releasing them",
                    pairing.first(), pairing.second(), creationFailed);
            releaseQuietly(pairing);
            return false;
        }

        // From here the game exists. Failing to record or announce it must not undo that:
        // both players can still find it in their game list, and a reconnect re-announces
        // anything still recorded (see MatchmakingFacade#unseenMatch).
        try {
            queue.recordMatch(white, game.id());
            queue.recordMatch(black, game.id());
        } catch (RuntimeException valkeyUnavailable) {
            log.warn("Game {} created but its match could not be recorded: {}",
                    game.id(), valkeyUnavailable.toString());
        }

        matches.increment();
        waitTime.record(Duration.ofMillis(pairing.firstWaitedMs()));
        waitTime.record(Duration.ofMillis(pairing.secondWaitedMs()));
        log.info("Matched {} (white) and {} (black) in {} as game {}",
                white, black, QueueTimeControls.nameOf(timeControl), game.id());

        events.publishEvent(new MatchFound(game.id(), white, black, timeControl));
        return true;
    }

    private void releaseQuietly(MatchQueue.Pairing pairing) {
        try {
            queue.releasePending(pairing.first(), pairing.second());
        } catch (RuntimeException ignored) {
            // Valkey is down too. The markers expire on their own (pending-ttl).
        }
    }
}
