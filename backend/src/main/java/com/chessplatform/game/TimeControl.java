package com.chessplatform.game;

import java.time.Duration;

/**
 * A time control, in the conventional "initial + increment" form.
 *
 * @param initialMs   starting time for each player
 * @param incrementMs added to the mover's clock after each move (Fischer increment)
 */
public record TimeControl(long initialMs, long incrementMs) {


    private static final long MIN_INITIAL_MS = 10_000;
    private static final long MAX_INITIAL_MS = Duration.ofHours(24).toMillis();
    private static final long MAX_INCREMENT_MS = Duration.ofMinutes(2).toMillis();

    /** Long enough to be playable while testing, short enough not to be tedious. */
    public static final TimeControl BLITZ_5_3 = new TimeControl(300_000, 3_000);


    public TimeControl {
        if (initialMs < MIN_INITIAL_MS || initialMs > MAX_INITIAL_MS) {
            throw new IllegalArgumentException(
                    "initial time must be between 10s and 24h; got " + initialMs + "ms");
        }
        if (incrementMs < 0 || incrementMs > MAX_INCREMENT_MS) {
            throw new IllegalArgumentException(
                    "increment must be between 0 and 2m; got " + incrementMs + "ms");
        }
    }

    public static TimeControl ofSeconds(long initialSeconds, long incrementSeconds) {
        return new TimeControl(initialSeconds * 1_000, incrementSeconds * 1_000);
    }

    /** e.g. {@code 5+3}. The notation every chess site uses. */
    public String notation() {
        return "%d+%d".formatted(initialMs / 60_000, incrementMs / 1_000);
    }
}
