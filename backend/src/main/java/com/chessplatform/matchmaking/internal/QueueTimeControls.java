package com.chessplatform.matchmaking.internal;

import com.chessplatform.game.TimeControl;

import java.util.List;
import java.util.Optional;

/**
 * The time controls that have a queue — the client's presets, and nothing else.
 *
 * <p>A queue per arbitrary time control would split a small player base into queues of one,
 * and would let any client create unbounded keys in Valkey. Direct challenges still accept
 * any valid time control; only the pool of strangers is restricted.
 */
public final class QueueTimeControls {

    public static final List<TimeControl> SUPPORTED = List.of(
            TimeControl.ofSeconds(60, 0),
            TimeControl.ofSeconds(180, 2),
            TimeControl.ofSeconds(300, 3),
            TimeControl.ofSeconds(600, 0));

    private QueueTimeControls() {
    }

    /** "300+3" — the queue name, and the value stored in a player's seek key. */
    public static String nameOf(TimeControl timeControl) {
        return timeControl.initialMs() / 1000 + "+" + timeControl.incrementMs() / 1000;
    }

    public static Optional<TimeControl> byName(String name) {
        return SUPPORTED.stream().filter(tc -> nameOf(tc).equals(name)).findFirst();
    }

    public static boolean isSupported(TimeControl timeControl) {
        return SUPPORTED.contains(timeControl);
    }
}
