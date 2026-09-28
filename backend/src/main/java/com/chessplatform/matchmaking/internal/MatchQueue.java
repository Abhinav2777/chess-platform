package com.chessplatform.matchmaking.internal;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The matchmaking queue in Valkey. Every state change is a Lua script, so each one is
 * atomic with respect to every other — the whole concurrency story of this module.
 *
 * <h2>Keys</h2>
 *
 * <pre>
 * mm:q:{tc}:rating   ZSET   userId -> rating          who is waiting, ordered by strength
 * mm:q:{tc}:since    ZSET   userId -> joined (ms)     who is waiting, ordered by patience
 * mm:seek:{userId}   STRING tc, TTL seekTtl           liveness: expires unless re-asserted
 * mm:match:{userId}  STRING PENDING | gameId, TTL     the outcome, until the player sees it
 * </pre>
 *
 * <p>Two sorted sets rather than one because pairing needs two orders: oldest-first to
 * decide who is served next, and by rating to find their nearest opponent. The seek key is
 * separate from both because a sorted-set member cannot expire on its own.
 *
 * <p><strong>Nothing here is the only copy of anything that matters</strong> (ADR-004). If
 * Valkey is flushed, every waiting player's next re-seek queues them again (losing their
 * accumulated wait), and no game is affected — games live in PostgreSQL.
 *
 * <p>Every method may throw {@code DataAccessException} when Valkey is unreachable. The
 * callers decide what that means: a refused seek, or a skipped tick.
 */
@Component
public class MatchQueue {

    static final String QUEUE_PREFIX = "mm:q:";
    static final String SEEK_PREFIX = "mm:seek:";
    static final String MATCH_PREFIX = "mm:match:";
    /** The match-key value between a claim and the game being committed. */
    public static final String PENDING_VALUE = "PENDING";

    // Raw List: the script's result type is a Class object, and List<String>.class does not
    // exist. Spring's string serialiser returns the elements as Strings; outcome() and
    // pairOne() read them through toString() rather than trusting an unchecked cast.
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SEEK = script("seek", List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CANCEL = script("cancel", List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> PAIR = script("pair", List.class);
    private static final RedisScript<Long> COMPARE_AND_DELETE = script("compare-and-delete", Long.class);
    private static final RedisScript<Long> COMPARE_AND_SET = script("compare-and-set", Long.class);

    private final StringRedisTemplate valkey;
    private final MatchmakingProperties properties;

    public MatchQueue(StringRedisTemplate valkey, MatchmakingProperties properties) {
        this.valkey = valkey;
        this.properties = properties;
    }

    /** The raw outcome of a seek or cancel script: a status word and its detail. */
    public record Outcome(String status, String detail) {
    }

    /** Two claimed players and how long each had waited, by Valkey's clock. */
    public record Pairing(UUID first, UUID second, long firstWaitedMs, long secondWaitedMs) {
    }

    public Outcome seek(UUID userId, int rating, String queue) {
        return outcome(valkey.execute(SEEK,
                List.of(ratingKey(queue), sinceKey(queue), SEEK_PREFIX + userId, MATCH_PREFIX + userId),
                userId.toString(), Integer.toString(rating), queue,
                Long.toString(properties.seekTtl().toSeconds())));
    }

    public Outcome cancel(UUID userId) {
        return outcome(valkey.execute(CANCEL,
                List.of(SEEK_PREFIX + userId, MATCH_PREFIX + userId),
                userId.toString(), QUEUE_PREFIX));
    }

    /** PENDING, a game id, or empty. A plain read: it decides nothing on its own. */
    public Optional<String> matchOf(UUID userId) {
        return Optional.ofNullable(valkey.opsForValue().get(MATCH_PREFIX + userId));
    }

    /** Claims one compatible pair from a queue, or empty if none is possible right now. */
    public Optional<Pairing> pairOne(String queue) {
        List<?> claimed = valkey.execute(PAIR,
                List.of(ratingKey(queue), sinceKey(queue)),
                Integer.toString(properties.baseWindow()),
                Double.toString(properties.windowGrowth()),
                Integer.toString(properties.maxWindow()),
                Integer.toString(properties.scanLimit()),
                Long.toString(properties.pendingTtl().toSeconds()),
                SEEK_PREFIX, MATCH_PREFIX,
                Integer.toString(properties.neighbours()));
        if (claimed == null || claimed.size() < 4) {
            return Optional.empty();
        }
        return Optional.of(new Pairing(
                UUID.fromString(claimed.get(0).toString()),
                UUID.fromString(claimed.get(1).toString()),
                Long.parseLong(claimed.get(2).toString()),
                Long.parseLong(claimed.get(3).toString())));
    }

    /** Replaces a player's PENDING marker with the created game. False if it had lapsed. */
    public boolean recordMatch(UUID userId, UUID gameId) {
        Long set = valkey.execute(COMPARE_AND_SET, List.of(MATCH_PREFIX + userId),
                PENDING_VALUE, gameId.toString(), Long.toString(properties.matchTtl().toSeconds()));
        return set != null && set == 1;
    }

    /** Releases PENDING markers after a failed game creation, so a re-seek queues at once. */
    public void releasePending(UUID first, UUID second) {
        valkey.execute(COMPARE_AND_DELETE,
                List.of(MATCH_PREFIX + first, MATCH_PREFIX + second), PENDING_VALUE);
    }

    /** Forgets a delivered match — only that match, never a newer one. */
    public void acknowledge(UUID userId, UUID gameId) {
        valkey.execute(COMPARE_AND_DELETE, List.of(MATCH_PREFIX + userId), gameId.toString());
    }

    static String ratingKey(String queue) {
        return QUEUE_PREFIX + queue + ":rating";
    }

    static String sinceKey(String queue) {
        return QUEUE_PREFIX + queue + ":since";
    }

    private static Outcome outcome(List<?> result) {
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("unexpected script result: " + result);
        }
        return new Outcome(result.get(0).toString(), result.get(1).toString());
    }

    /**
     * Loaded once. Spring sends EVALSHA and falls back to EVAL on NOSCRIPT, so after the
     * first call per Valkey node the script body is not re-sent — and a Valkey restart,
     * which empties its script cache, costs one extra round trip rather than a failure.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> RedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("matchmaking/" + name + ".lua"));
        script.setResultType(resultType);
        return script;
    }
}
