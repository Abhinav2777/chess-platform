package com.chessplatform.integration.game;

import com.chessplatform.chess.MoveIntent;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.api.GameController;
import com.chessplatform.game.api.dto.GameResponses;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.game.internal.GameService;
import com.chessplatform.game.internal.GameTimeouts;
import com.chessplatform.game.internal.ServerClock;
import com.chessplatform.game.SubmitMoveCommand;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3's "done when": a skewed application clock has no effect on game timing.
 *
 * <p>ADR-006 claims PostgreSQL {@code now()} is the only clock that decides a game. The
 * other clock tests cannot show that, because in a test both clocks are the same
 * machine's and agree to the millisecond. So this context runs with the JVM's
 * {@link Clock} bean <strong>ten minutes fast</strong> — a host whose NTP has failed badly
 * — and plays a game.
 *
 * <p>Ten minutes rather than a few seconds so that any leak is unmissable: with a 5+3
 * control, a move charged against the JVM clock flags on the spot, and a clock displayed
 * from it reads zero.
 *
 * <h2>What this can and cannot prove</h2>
 *
 * <p>It proves nothing that decides or displays a game reads the injected {@code Clock}.
 * It <em>cannot</em> catch a bare {@code Instant.now()}, which reads the operating system
 * clock and cannot be skewed from inside the JVM — which is exactly how the pre-3.3
 * {@code GameSummary} bug hid. That class of leak is closed by construction instead:
 * {@code GameSummary.withNames} now takes {@code now} as a parameter, and the remaining
 * {@code Instant.now()} calls in main code are timestamps on error bodies and envelopes,
 * which are informational (ADR-006 §"Scope of the time authority").
 *
 * <p>A separate class because it needs its own application context; mixing it into
 * {@code ClockIntegrationTest} would skew every test there too.
 */
@DisplayName("Game clock under a skewed application clock")
@Import(ClockSkewIntegrationTest.SkewedClock.class)
class ClockSkewIntegrationTest extends IntegrationTestBase {

    private static final Duration SKEW = Duration.ofMinutes(10);

    @TestConfiguration
    static class SkewedClock {
        /**
         * Registered alongside the production {@code Clock} and marked primary, so every
         * injection point that asks for a {@code Clock} gets this one. Bean overriding is
         * off in Boot, so replacing the production bean by name is not an option.
         */
        @Bean
        @Primary
        Clock skewedClock() {
            return Clock.offset(Clock.systemUTC(), SKEW);
        }
    }

    @Autowired
    private Clock applicationClock;
    @Autowired
    private ServerClock serverClock;
    @Autowired
    private GameService gameService;
    @Autowired
    private GameTimeouts timeouts;
    @Autowired
    private GameController controller;
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private UserRepository users;

    @AfterEach
    void cleanUp() {
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("a game played on a host ten minutes fast is timed exactly as on a correct one")
    void skewHasNoEffect() {
        // The premise, checked rather than assumed: without it this test proves nothing.
        Duration drift = Duration.between(serverClock.now(), Instant.now(applicationClock));
        assertThat(drift).as("the application clock really is skewed")
                .isGreaterThan(SKEW.minusSeconds(5));

        User white = registrar.register("skew-white", "sw@example.com", "correct-horse-battery");
        User black = registrar.register("skew-black", "sb@example.com", "correct-horse-battery");
        Game game = gameService.createGame(white.id(), black.id(), TimeControl.BLITZ_5_3);

        // Moves: a JVM-clock charge would be ten minutes and flag White on the spot.
        play(game, white, 0, "e2", "e4");
        GameService.MoveAccepted reply = play(game, black, 1, "e7", "e5");
        assertThat(reply.whiteMsLeft()).as("White charged well under a second, plus 3s")
                .isBetween(302_000L, 303_000L);
        assertThat(reply.blackMsLeft()).isBetween(302_000L, 303_000L);

        // Sweeper: a JVM-clock deadline check would expire this game immediately.
        timeouts.finaliseExpiredBatch();
        assertThat(games.findById(game.id()).orElseThrow().status())
                .as("nothing has expired").isEqualTo(GameStatus.ACTIVE);

        // REST: remaining time is computed "as of now" — the server's now.
        GameResponses.GameSummary shown = controller.get(game.id()).game();
        assertThat(shown.whiteMsLeft())
                .as("White is on move and has been thinking for milliseconds, not minutes")
                .isGreaterThan(300_000L);
    }

    private GameService.MoveAccepted play(Game game, User player, int ply, String from, String to) {
        return gameService.submitMove(game.id(), player.id(),
                new SubmitMoveCommand(UUID.randomUUID(), ply, MoveIntent.of(from, to)));
    }
}
