package com.chessplatform.integration.matchmaking;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.game.TimeControl;
import com.chessplatform.game.domain.Game;
import com.chessplatform.game.domain.GameRepository;
import com.chessplatform.game.domain.MoveRepository;
import com.chessplatform.identity.domain.User;
import com.chessplatform.identity.domain.UserRepository;
import com.chessplatform.identity.internal.UserRegistrar;
import com.chessplatform.integration.IntegrationTestBase;
import com.chessplatform.matchmaking.MatchFound;
import com.chessplatform.matchmaking.MatchmakingFacade;
import com.chessplatform.matchmaking.SeekResult;
import com.chessplatform.matchmaking.internal.MatchQueue;
import com.chessplatform.matchmaking.internal.Matchmaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Matchmaking against a real Valkey and a real PostgreSQL.
 *
 * <p>The scheduler is off (IntegrationTestBase), so every pairing here happens because a
 * test called {@link Matchmaker#tick()} — the flagship test calls it from four threads at
 * once, which is the concurrent-instances case without needing four JVMs.
 */
@DisplayName("Matchmaking")
@RecordApplicationEvents
class MatchmakingIntegrationTest extends IntegrationTestBase {

    private static final TimeControl BLITZ = TimeControl.ofSeconds(300, 3);
    private static final TimeControl RAPID = TimeControl.ofSeconds(600, 0);

    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired
    private MatchmakingFacade matchmaking;
    @Autowired
    private Matchmaker matchmaker;
    @Autowired
    private MatchQueue queue;
    @Autowired
    private StringRedisTemplate valkey;
    @Autowired
    private UserRegistrar registrar;
    @Autowired
    private UserRepository users;
    @Autowired
    private GameRepository games;
    @Autowired
    private MoveRepository moves;

    @AfterEach
    void cleanUp() {
        Set<String> keys = valkey.keys("mm:*");
        if (keys != null && !keys.isEmpty()) {
            valkey.delete(keys);
        }
        moves.deleteAll();
        games.deleteAll();
        users.deleteAll();
    }

    private final AtomicInteger sequence = new AtomicInteger();

    private User player(int rating) {
        int n = sequence.incrementAndGet();
        User user = registrar.register("mm" + n + "x" + UUID.randomUUID().toString().substring(0, 6),
                "mm" + n + "-" + UUID.randomUUID() + "@example.com", "correct-horse-battery");
        jdbc().update("UPDATE users SET rating = ? WHERE id = ?", rating, user.id());
        return user;
    }

    private Set<String> queued(String queueName) {
        Set<String> members = valkey.opsForZSet().range("mm:q:" + queueName + ":rating", 0, -1);
        return members == null ? Set.of() : members;
    }

    @Nested
    @DisplayName("seeking")
    class Seeking {

        @Test
        @DisplayName("a repeated seek is a heartbeat: still queued, and the wait is not reset")
        void seekIsIdempotent() {
            User alice = player(1500);

            assertThat(matchmaking.seek(alice.id(), BLITZ).status()).isEqualTo(SeekResult.Status.QUEUED);
            Double joined = valkey.opsForZSet().score("mm:q:300+3:since", alice.id().toString());

            assertThat(matchmaking.seek(alice.id(), BLITZ).status()).isEqualTo(SeekResult.Status.QUEUED);
            assertThat(valkey.opsForZSet().score("mm:q:300+3:since", alice.id().toString()))
                    .as("re-seeking must not move the player to the back of the queue")
                    .isEqualTo(joined);
            assertThat(queued("300+3")).containsExactly(alice.id().toString());
        }

        @Test
        @DisplayName("seeking a second time control while queued is refused")
        void oneQueueAtATime() {
            User alice = player(1500);
            matchmaking.seek(alice.id(), BLITZ);

            assertThatThrownBy(() -> matchmaking.seek(alice.id(), RAPID))
                    .isInstanceOfSatisfying(DomainException.Rejected.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_SEEKING));
            assertThat(queued("600+0")).isEmpty();
        }

        @Test
        @DisplayName("only the preset time controls have queues")
        void unsupportedTimeControl() {
            User alice = player(1500);

            assertThatThrownBy(() -> matchmaking.seek(alice.id(), TimeControl.ofSeconds(420, 7)))
                    .isInstanceOfSatisfying(DomainException.Rejected.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_TIME_CONTROL));
        }

        @Test
        @DisplayName("a player with an active game cannot seek another")
        void alreadyInGame() {
            User alice = player(1500);
            User bob = player(1500);
            matchmaking.seek(alice.id(), BLITZ);
            matchmaking.seek(bob.id(), BLITZ);
            matchmaker.tick();
            matchmaking.acknowledge(alice.id(), gameOf(alice).id());

            assertThatThrownBy(() -> matchmaking.seek(alice.id(), BLITZ))
                    .isInstanceOfSatisfying(DomainException.Rejected.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_IN_GAME));
        }

        @Test
        @DisplayName("cancel leaves the queue; a second cancel has nothing to do")
        void cancel() {
            User alice = player(1500);
            matchmaking.seek(alice.id(), BLITZ);

            assertThat(matchmaking.cancel(alice.id()).status()).isEqualTo(SeekResult.Status.CANCELLED);
            assertThat(matchmaking.cancel(alice.id()).status()).isEqualTo(SeekResult.Status.NOT_SEEKING);
            assertThat(queued("300+3")).isEmpty();
            assertThat(valkey.opsForZSet().size("mm:q:300+3:since")).isZero();
        }
    }

    @Nested
    @DisplayName("pairing")
    class Pairing {

        @Test
        @DisplayName("two compatible players become one game, and both are told the same game")
        void pairsTwoPlayers(ApplicationEvents events) {
            User alice = player(1500);
            User bob = player(1550);
            matchmaking.seek(alice.id(), BLITZ);
            matchmaking.seek(bob.id(), BLITZ);

            assertThat(matchmaker.tick()).isEqualTo(1);

            Game game = gameOf(alice);
            assertThat(Set.of(game.whitePlayerId(), game.blackPlayerId()))
                    .containsExactlyInAnyOrder(alice.id(), bob.id());
            assertThat(game.initialMs()).isEqualTo(300_000);
            assertThat(game.incrementMs()).isEqualTo(3_000);

            // A player who missed the push re-seeks and is told, rather than queued again.
            SeekResult again = matchmaking.seek(alice.id(), BLITZ);
            assertThat(again.status()).isEqualTo(SeekResult.Status.MATCHED);
            assertThat(again.gameId()).isEqualTo(game.id());
            assertThat(matchmaking.unseenMatch(bob.id())).contains(game.id());
            assertThat(queued("300+3")).isEmpty();

            assertThat(events.stream(MatchFound.class))
                    .singleElement()
                    .satisfies(found -> assertThat(found.gameId()).isEqualTo(game.id()));
        }

        @Test
        @DisplayName("acknowledging a match stops it being re-announced")
        void acknowledge() {
            User alice = player(1500);
            User bob = player(1500);
            matchmaking.seek(alice.id(), BLITZ);
            matchmaking.seek(bob.id(), BLITZ);
            matchmaker.tick();

            matchmaking.acknowledge(alice.id(), gameOf(alice).id());

            assertThat(matchmaking.unseenMatch(alice.id())).isEmpty();
            assertThat(matchmaking.unseenMatch(bob.id())).isPresent();
        }

        @Test
        @DisplayName("players in different queues are never paired")
        void queuesAreSeparate() {
            User alice = player(1500);
            User bob = player(1500);
            matchmaking.seek(alice.id(), BLITZ);
            matchmaking.seek(bob.id(), RAPID);

            assertThat(matchmaker.tick()).isZero();
        }

        /**
         * The window grows with waiting. 1200 vs 1500 is outside the 100-point base window;
         * after 25 s each (window 100 + 10 × 25 = 350) it is inside. Waiting is simulated by
         * moving the join times back, rather than by sleeping for 25 seconds.
         */
        @Test
        @DisplayName("a wide rating gap is refused at first and accepted after waiting")
        void windowExpands() {
            User weak = player(1200);
            User strong = player(1500);
            matchmaking.seek(weak.id(), BLITZ);
            matchmaking.seek(strong.id(), BLITZ);

            assertThat(matchmaker.tick()).as("300 points apart, just joined").isZero();

            backdate(weak, 25_000);
            backdate(strong, 25_000);

            assertThat(matchmaker.tick()).as("after 25 s the window is 350").isEqualTo(1);
        }

        @Test
        @DisplayName("the nearest rating wins, not the first found")
        void prefersNearest() {
            User anchor = player(1500);
            User far = player(1580);
            User near = player(1510);
            matchmaking.seek(anchor.id(), BLITZ);
            backdate(anchor, 1_000);   // make the anchor the oldest, so it is served first
            matchmaking.seek(far.id(), BLITZ);
            matchmaking.seek(near.id(), BLITZ);

            matchmaker.tick();

            Game game = gameOf(anchor);
            assertThat(Set.of(game.whitePlayerId(), game.blackPlayerId())).contains(near.id());
        }

        /**
         * The seek key's TTL is the failure detector. Deleting it is what expiry does: a
         * client that stopped re-asserting. Its entry must not be paired, and is removed.
         */
        @Test
        @DisplayName("a seeker whose heartbeat lapsed is skipped and evicted, not paired")
        void staleEntryEvicted() {
            User ghost = player(1500);
            User live = player(1500);
            matchmaking.seek(ghost.id(), BLITZ);
            matchmaking.seek(live.id(), BLITZ);
            valkey.delete("mm:seek:" + ghost.id());

            assertThat(matchmaker.tick()).isZero();
            assertThat(queued("300+3")).containsExactly(live.id().toString());
        }

        /**
         * An instance dies after claiming a pair and before creating the game. Simulated by
         * claiming through the queue directly (no game is created) and then letting the
         * PENDING markers lapse. The players must be told to wait while PENDING, and be able
         * to queue again afterwards — nothing lost, nothing stuck.
         */
        @Test
        @DisplayName("a pair claimed by an instance that then died is recovered by re-seeking")
        void crashBetweenClaimAndGame() {
            User alice = player(1500);
            User bob = player(1500);
            matchmaking.seek(alice.id(), BLITZ);
            matchmaking.seek(bob.id(), BLITZ);

            assertThat(queue.pairOne("300+3")).isPresent();   // claimed; the "instance" dies here
            assertThat(matchmaking.seek(alice.id(), BLITZ).status()).isEqualTo(SeekResult.Status.PAIRING);
            assertThat(matchmaking.cancel(alice.id()).status())
                    .as("too late to cancel a claimed pair").isEqualTo(SeekResult.Status.PAIRING);

            valkey.delete(List.of("mm:match:" + alice.id(), "mm:match:" + bob.id()));   // pending-ttl lapses

            assertThat(matchmaking.seek(alice.id(), BLITZ).status()).isEqualTo(SeekResult.Status.QUEUED);
            assertThat(matchmaking.seek(bob.id(), BLITZ).status()).isEqualTo(SeekResult.Status.QUEUED);
            assertThat(matchmaker.tick()).isEqualTo(1);
            assertThat(games.count()).as("exactly one game, from the second attempt").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * Phase 4's "done when": 20 players paired correctly, no duplicates, no lost entries.
         *
         * <p>Every player seeks twice (a double-click, or a retry), all 40 seeks released at
         * once, while four matchmakers tick concurrently — as four instances would. Ratings
         * span 95 points, inside the base window, so every pairing is permitted and the only
         * thing under test is the concurrency.
         */
        @Test
        @DisplayName("20 players seeking twice against 4 concurrent matchmakers form exactly 10 games")
        void twentyPlayersFourMatchmakers() throws Exception {
            List<User> players = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                players.add(player(1200 + i * 5));
            }

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger created = new AtomicInteger();
            List<Callable<Object>> work = new ArrayList<>();
            for (User player : players) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    work.add(() -> {
                        start.await();
                        return matchmaking.seek(player.id(), BLITZ);
                    });
                }
            }
            for (int m = 0; m < 4; m++) {
                work.add(() -> {
                    start.await();
                    long deadline = System.currentTimeMillis() + 15_000;
                    while (created.get() < 10 && System.currentTimeMillis() < deadline) {
                        created.addAndGet(matchmaker.tick());
                    }
                    return null;
                });
            }

            List<Future<Object>> futures;
            try (ExecutorService pool = Executors.newFixedThreadPool(work.size())) {
                futures = work.stream().map(pool::submit).toList();
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
            for (Future<Object> future : futures) {
                future.get();   // surfaces any exception from a seek or a tick
            }

            List<Game> all = games.findAll();
            assertThat(all).as("exactly ten games").hasSize(10);
            assertThat(created.get()).as("ticks reported the same ten").isEqualTo(10);

            Map<UUID, Integer> gamesPerPlayer = new HashMap<>();
            for (Game game : all) {
                gamesPerPlayer.merge(game.whitePlayerId(), 1, Integer::sum);
                gamesPerPlayer.merge(game.blackPlayerId(), 1, Integer::sum);
            }
            assertThat(gamesPerPlayer).as("every player placed").hasSize(20);
            assertThat(Collections.max(gamesPerPlayer.values()))
                    .as("no player in two games").isEqualTo(1);

            assertThat(queued("300+3")).as("no entry left behind").isEmpty();
            assertThat(valkey.opsForZSet().size("mm:q:300+3:since")).isZero();
            assertThat(valkey.keys("mm:seek:*")).as("no orphaned seek keys").isEmpty();
            for (User player : players) {
                assertThat(matchmaking.unseenMatch(player.id()))
                        .as("each player is told their own game")
                        .contains(gameOf(player).id());
            }
        }
    }

    private Game gameOf(User player) {
        return games.findAll().stream()
                .filter(g -> g.whitePlayerId().equals(player.id()) || g.blackPlayerId().equals(player.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no game for " + player.username()));
    }

    private void backdate(User player, long millis) {
        valkey.opsForZSet().incrementScore("mm:q:300+3:since", player.id().toString(), -millis);
    }
}
