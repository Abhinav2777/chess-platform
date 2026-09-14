# ADR-005: Optimistic locking + idempotency keys, not distributed locks

**Status:** Accepted · **Date:** 2026-09-06

## Context

The invariant to protect: **for a given `(game_id, ply)`, exactly one move is ever
committed.** Threats include both players submitting simultaneously, client retries
after a timeout, two pods handling the same game, and the timeout sweeper finalising a
game at the instant a move lands.

## Decision

Three overlapping layers, all inside PostgreSQL. **No distributed lock.**

1. **Idempotency:** `UNIQUE (game_id, client_move_id)` on `moves`. A retry hits the
   constraint; we catch it and return the *original* result, so the client cannot
   distinguish a retry from a first attempt.
2. **Optimistic locking:** `games.version` via JPA `@Version`. Concurrent writers race;
   one commits, the other gets `OptimisticLockException`. We re-read and re-validate
   rather than blind-retrying — the loser's move is almost always illegal now anyway,
   so it correctly fails with `NOT_YOUR_TURN`.
3. **Structural:** `PRIMARY KEY (game_id, ply)`. Even with both layers above broken,
   two moves at the same ply are physically unstorable.

## Alternatives considered

**Redis distributed lock (Redlock or `SET NX PX`).** Rejected, and this is the decision
worth defending most carefully. A Redis lock has a TTL. If the holder hits a 3-second GC
pause while holding a 2-second lock, the lock expires, a second process acquires it, and
two processes both believe they hold it. The accepted mitigation is a fencing token
validated at the storage layer — but a monotonically-increasing token checked on write
*is* optimistic locking, and you still need the database constraint. So the lock adds a
network round-trip, a new dependency on Valkey availability for correctness, and an
expiry failure mode, while removing nothing.

**Pessimistic locking (`SELECT … FOR UPDATE`).** Correct, and a legitimate choice. It
serialises writers and avoids retry logic entirely. Rejected as the default because it
holds a row lock across the chess-rules computation and because contention here is
genuinely rare: chess alternates turns, so at any instant only one player *can* legally
move. Optimistic locking is designed exactly for low-contention scenarios. We **do** use
`FOR UPDATE SKIP LOCKED` in the timeout sweeper, where multiple workers really do
compete for the same rows.

**`SERIALIZABLE` isolation.** Rejected — it moves the problem to serialisation-failure
retries at higher cost, for a conflict already precisely expressible with a version
column.

## Consequences

- Correctness does not depend on Valkey. If Valkey is down, moves are still safe.
- We must handle `OptimisticLockException` explicitly, and we must *not* auto-retry
  blindly — re-validation is mandatory or a retry could apply a move to a changed board.
- `chess_move_conflicts_total` and `chess_move_idempotent_replays_total` are exported so
  we can show these paths actually execute rather than asserting they would.
- A concurrency integration test (N threads, one ply, real Postgres) proves exactly one
  commit. This test is the evidence for the whole ADR.

## Interview angle

**Q:** "How do you handle two players moving at the same time?"
**A:** In chess only one side can legally move at a time, so simultaneous submissions
are almost always one legal move and one illegal one — turn validation rejects the
second. The real concurrency problem isn't simultaneity, it's *retries*: a client
whose ACK is lost doesn't know whether its move landed. So each move carries a
client-generated UUID with a unique constraint on `(game_id, client_move_id)`, and a
retry returns the original outcome instead of applying twice. On top of that, a version
column on the game row catches genuine write races, and the moves table's primary key
on `(game_id, ply)` makes double-application structurally impossible.

**Q:** "Why not a Redis distributed lock?"
**A:** Because it wouldn't remove any of the constraints I already need, and it would
add a failure mode. Redis locks are TTL-based — a GC pause longer than the TTL means
two holders. The standard fix is a fencing token checked at the storage layer, which is
optimistic locking with extra steps, and I'd still need the database constraint for
safety. The database already gives me the guarantee atomically; adding Redis to the
correctness path would make me depend on a cache being up for my data to be correct.

**Q:** "When would you use pessimistic locking instead?"
**A:** When contention is high enough that retries dominate, or when the critical
section is long. I do use `SELECT … FOR UPDATE SKIP LOCKED` in the timeout sweeper —
there, several workers genuinely compete for the same expired games, and `SKIP LOCKED`
lets them partition the work with zero coordination and no double-finalisation.
