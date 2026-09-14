# ADR-004: PostgreSQL is the sole source of truth; Valkey is never authoritative

**Status:** Accepted · **Date:** 2026-09-06

## Context

Active game state is hot: read and written on every move. The obvious optimisation is
to hold the position and clocks in Valkey and write to PostgreSQL lazily or on game
completion. Many real-time systems do exactly this.

## Decision

Every move is committed to PostgreSQL **synchronously** before it is acknowledged.
Valkey stores only data that is either (a) rebuildable from PostgreSQL, or (b)
inherently ephemeral and safe to lose.

| Valkey key | Contents | TTL | Loss impact |
|---|---|---|---|
| `game:{id}:state` | serialised snapshot (read cache) | 300s | Cache miss → read PG |
| `presence:{gameId}:{userId}` | online/offline | 60s | Presence shows stale, self-heals |
| `mm:{timeControl}` | ZSET of waiting players | — | Queue lost, players re-enqueue |
| `mm:entry:{userId}` | dedupe guard | 120s | Duplicate enqueue possible briefly |
| `ratelimit:{userId}:{bucket}` | Bucket4j token state | short | Limits reset, no correctness issue |

Additionally, `games.fen` is itself a denormalisation of `moves` — the position of every
game is recomputable by replaying its move log. Even corruption of that column is
recoverable.

## Alternatives considered

**Valkey as primary for active games, PG on completion.** Rejected. A Valkey failover
or eviction loses the position and clocks of every game in progress. There is no
recovery: the moves were never persisted. For a *rated* game this is unacceptable, and
the mitigation (AOF `appendfsync always`) removes most of the performance advantage
that motivated the design.

**Write-behind with an in-memory buffer.** Rejected for the same reason with an extra
failure mode: the buffer dies with the pod.

**No cache at all.** Reasonable, and what Phases 1–3 actually do. Valkey is introduced
in Phase 4 for pub/sub and matchmaking — where it is *load-bearing* — and the read
cache is a minor addition on top.

## Consequences

- Every move costs one write transaction. At the measured target (300 active games,
  realistic move rates) this is nowhere near a bottleneck. If it became one, the fix is
  a larger instance or read replicas for history, not moving truth into cache.
- Valkey can be restarted at any moment. Games keep working; only fanout and
  matchmaking pause. This is testable and will be tested.
- We accept a hard dependency on PostgreSQL availability (see ARCHITECTURE.md §13).

## Interview angle

**Q:** "Why use Redis at all if PostgreSQL is already there?"
**A:** For three things PostgreSQL is bad at: cross-instance pub/sub fanout, atomic
matchmaking pairing via a Lua script over a sorted set, and short-TTL ephemeral
presence. Not for the game state — that stays in Postgres.

**Q:** "What happens if Redis loses the game state?"
**A:** Nothing, because it isn't there. The cache holds a rebuildable snapshot with a
5-minute TTL; a miss is a Postgres read. What we do lose is real-time fanout, so
clients fall back to polling the game endpoint until Valkey returns.

**Q:** "Why not make Redis the source of truth? It's faster."
**A:** Because these are rated games and losing one is unacceptable. Redis persistence
is asynchronous by default, so a failover loses the last window of writes — every game
in progress. Making it safe means `appendfsync always`, which surrenders the latency
advantage that motivated the idea. And I don't have a throughput problem: at my measured
load the write transaction is not the bottleneck, the connection pool is. Optimising
the wrong thing at the cost of durability is a bad trade.

**Q:** "How would that change at 500,000 concurrent games?"
**A:** Then the write rate genuinely matters and I'd revisit it — but I'd shard first,
because a chess game involves exactly two players with no cross-game consistency
requirement, so it partitions perfectly by game ID into independent cells each with
their own Postgres. That keeps durability and scales linearly. I'd only put state in
Redis if sharding ran out, and then with synchronous persistence and an accepted,
documented data-loss window.
