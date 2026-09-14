package com.chessplatform.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * A {@link Clock} bean rather than calls to {@code Instant.now()} scattered through
     * the code, so time is an injected dependency and tests can pin or advance it
     * without sleeping. A test suite that sleeps is a test suite that is slow and flaky.
     *
     * <p><strong>This is not the game clock.</strong> Anything that decides a game's
     * outcome takes its timestamp from PostgreSQL's {@code now()}, because application
     * servers on different hosts disagree by tens of milliseconds and that error
     * accumulates across a game's moves (ADR-006). This bean is for
     * everything else — {@code created_at}, token expiry, metrics windows — where
     * millisecond-level inter-pod skew is irrelevant.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
