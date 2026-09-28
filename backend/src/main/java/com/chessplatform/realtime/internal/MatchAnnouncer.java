package com.chessplatform.realtime.internal;

import com.chessplatform.chess.Side;
import com.chessplatform.game.TimeControl;
import com.chessplatform.matchmaking.MatchFound;
import com.chessplatform.realtime.protocol.Envelope;
import com.chessplatform.realtime.protocol.Payloads;
import com.chessplatform.realtime.protocol.ServerMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tells both players their game is ready.
 *
 * <p>A plain {@code @EventListener}, not {@code AFTER_COMMIT}: {@code MatchFound} is
 * published by the matchmaker <em>after</em> {@code startGame} has returned, i.e. after the
 * game's own transaction committed, and outside any transaction. There is nothing left to
 * wait for — and an {@code AFTER_COMMIT} listener with no transaction active would simply
 * never fire.
 */
@Component
public class MatchAnnouncer {

    private final UserNotifier notifier;
    private final Counter announced;

    public MatchAnnouncer(UserNotifier notifier, MeterRegistry metrics) {
        this.notifier = notifier;
        this.announced = Counter.builder("chess.ws.matches.announced")
                .description("MATCH_FOUND messages published (two per match)")
                .register(metrics);
    }

    @EventListener
    public void onMatchFound(MatchFound match) {
        notifier.notify(match.whitePlayerId(), envelope(match.gameId(), Side.WHITE, match.timeControl()));
        notifier.notify(match.blackPlayerId(), envelope(match.gameId(), Side.BLACK, match.timeControl()));
        announced.increment(2);
    }

    static Envelope envelope(UUID gameId, Side side, TimeControl timeControl) {
        return Envelope.of(ServerMessage.MATCH_FOUND, new Payloads.MatchFound(gameId, side,
                timeControl.initialMs() / 1000, timeControl.incrementMs() / 1000));
    }
}
