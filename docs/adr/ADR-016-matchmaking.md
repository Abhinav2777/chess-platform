# ADR-016: Matchmaking — Valkey queue, Lua pairing, push with pull recovery

**Status:** Accepted · **Date:** 2026-09-28
**Builds on:** ADR-004 (PostgreSQL is the source of truth), ADR-005 (no distributed locks),
ADR-007 (snapshot recovery), ARCHITECTURE.md §8.

## Context

Phase 4 adds "play a stranger of similar strength". The spec asks for rating windows that
widen with waiting, duplicate-request and cancellation handling, stale entries, concurrent
matchmaking workers, and a discussion at 10 / 10,000 / 1M players. The roadmap's done-when:
*20 simulated players paired correctly, no duplicates, no lost entries.*

The hard part is not choosing an opponent. It is that several instances pair
concurrently, players double-click, cancel mid-pairing, close tabs, and instances die
between "chose two players" and "the game exists".

## Decision

### State: a Valkey queue per preset time control

| Key | Type | Holds | TTL |
|---|---|---|---|
| `mm:q:{tc}:rating` | ZSET | userId → rating | — |
| `mm:q:{tc}:since` | ZSET | userId → joined (ms, Valkey `TIME`) | — |
| `mm:seek:{userId}` | STRING | tc | `seek-ttl` 45 s |
| `mm:match:{userId}` | STRING | `PENDING` or gameId | `pending-ttl` 30 s / `match-ttl` 60 s |

Queues exist only for the four client presets. Two sorted sets because pairing needs two
orders — oldest first (who is served) and by rating (nearest opponent).

### Every state change is a Lua script

`seek`, `cancel`, `pair`, `compare-and-set`, `compare-and-delete`. Valkey executes one
script at a time, so no two operations interleave. **That is the concurrency design: no
lock, no leader.** A matchmaker runs on every instance; two pairing the same queue at the
same instant are serialised by Valkey, and a player can be claimed once.

### Pairing

Oldest waiter first; window `min(base + growth × seconds_waited, max)` (100 + 10/s, cap
400); nearest-rated live neighbour within it (5 inspected each side). One pair per script
call; the Java loop calls again. Bounded work per call, because while a script runs Valkey
serves nobody else.

### Liveness and recovery: an idempotent, repeated seek

A seek re-asserted for the same time control refreshes its TTL, keeps the original join
time (`ZADD NX`), and re-adds a lost entry. The client re-seeks every 15 s while waiting,
so **one message is the heartbeat, the stale-entry detector, and the crash recovery**:

- Tab closed / client gone → seek key expires → `pair.lua` evicts the entry as it passes.
- Instance dies after claiming a pair, before the game commits → `PENDING` expires → the
  players' next re-seek queues them again.
- Valkey flushed → the next re-seek re-creates the entry (the accumulated wait is lost).

The commit point is the game row in PostgreSQL. Valkey never holds the only copy of
anything that matters (ADR-004).

### Delivery: push over WebSocket, pull on reconnect (Milestone 4.1b)

The matchmaker publishes `MatchFound` (an application event — `realtime` depends on
`matchmaking`, so a direct call back would be a module cycle). `realtime` fans it out
across instances and sends `MATCH_FOUND` to the player's sockets. Pub/sub is
fire-and-forget, so the match is also kept in `mm:match:{userId}` for 60 s: a re-seek
returns it, and a reconnecting socket is told it after `AUTH_OK`. Same shape as ADR-007 —
push for latency, pull for correctness. Chosen over status polling by the project owner for
UX and real-time depth, at ~2–3 h extra.

### Game state read cache: not built

The roadmap listed a `game:{id}:state` cache. Game reads are primary-key lookups (sub-ms
at this scale), the move pipeline must read PostgreSQL anyway for the optimistic lock, and
a cache adds invalidation risk to the most correctness-critical path. Revisit only if Phase
9 load tests show read pressure. Decided with the project owner, 2026-09-28.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Distributed lock around pairing** (Redlock) | ADR-005's argument applies: a TTL lock admits two holders after a pause, and still needs a check at the storage layer. The script *is* the critical section, executed atomically by the only store involved. |
| **Queue in PostgreSQL** (`SELECT … FOR UPDATE SKIP LOCKED`) | Works, and would make the queue durable. But a queue is ephemeral by nature (a waiting player is present, or not), nearest-rating lookup is what a sorted set is for, and it would add write load to the source of truth for state nobody needs after 30 s. |
| **Single leader matchmaker** | Needs election and failover for no benefit — the script already makes concurrent matchmakers safe. |
| **Pair synchronously inside the seek request** | Saves ≤1 s of latency. Window expansion needs a periodic pass anyway, so it would be a second code path to the same script. |
| **Status polling instead of push** | Simpler and stateless; rejected for UX (see Delivery). The repeated seek kept its role as the heartbeat. |
| **Per-user pub/sub channels** for delivery | One channel every instance hears is cheaper to run at this scale: match events are one per game started, orders of magnitude rarer than moves. Per-user channels are the step when instance count × match rate makes the broadcast wasteful. |
| **One queue per arbitrary time control** | Splits a small player base into queues of one, and lets clients create unbounded keys. |

## Consequences

- Matchmaking is unavailable while Valkey is down (`503 MATCHMAKING_UNAVAILABLE`); direct
  challenges and all gameplay continue.
- The one-active-game check is a PostgreSQL read, not atomic with the queue: accepting a
  direct challenge in the same instant as being paired can yield two games. The abort
  window cleans up the unplayed one. Not worth a cross-store transaction.
- `cancel.lua` and `pair.lua` derive keys at runtime (`mm:seek:` + member). Legal on one
  node; **Valkey Cluster requires every key a script touches to be declared and to hash to
  one slot**. Sharding would mean hash-tagging per queue (`mm:{300+3}:…`) and moving the
  liveness flag into the queue's slot (e.g. a per-queue hash of heartbeats). Recorded, not
  built.

## Scale

- **10 players:** one queue, one script call per tick finds everyone. Everything here is
  overkill, and harmless.
- **10,000 seekers:** still one node. `ZRANGEBYSCORE … LIMIT` is O(log N + k); the scan is
  bounded by `scan-limit` × `neighbours`, so a script stays well under a millisecond. More
  instances means more concurrent matchmakers, which the script already permits. The limit
  is Valkey's single thread: script time × pairs per second.
- **1M seekers:** shard queues by time control and rating band across nodes (the cluster
  constraint above), accept that cross-band pairs need a second pass, or move matchmaking to
  a dedicated in-memory service with a durable log. Discussed, not built.

## Tests

`MatchmakingIntegrationTest` (13, real Valkey + PostgreSQL): idempotent seek keeps the join
time; one queue at a time; unsupported time control; already in a game; cancel; pairing and
`MatchFound`; acknowledge; queues separate; window expansion (by back-dating join times, not
sleeping); nearest rating wins; lapsed heartbeat evicted; crash between claim and game
recovered; and **20 players × 2 concurrent seeks against 4 concurrent matchmakers → exactly
10 games, every player in exactly one, nothing left in Valkey.**

## Interview angle

**Q:** "Two matchmaker instances run at once. How do you stop a player being paired twice?"
**A:** Pairing is a Lua script. Valkey runs scripts one at a time, so "pick two players and
remove them" is atomic across every instance — no lock, no leader. I test it with four
matchmakers ticking concurrently while 20 players each seek twice: exactly ten games.

**Q:** "What if the instance dies after pairing but before creating the game?"
**A:** The script marks both players PENDING with a 30-second TTL. The game row in Postgres
is the commit point. If the instance dies, the marker expires and the players' next
periodic re-seek puts them back in the queue. Valkey never holds the only copy of anything
that matters.

**Q:** "How do you detect a player who closed the tab?"
**A:** The seek is a key with a TTL that the client refreshes by re-seeking every 15
seconds. The same idempotent message is the heartbeat and the recovery path. The pairing
script evicts entries whose key has expired as it walks the queue.
