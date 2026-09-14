# INTERVIEW NOTES

Questions this project should let you answer, with the answer you should be able to give
unprompted. Grows every phase. The detailed reasoning lives in `docs/adr/` — this file
is the rehearsal script.

**Rule:** if you cannot answer a question here without reading it, that part of the
system is not finished, regardless of whether the code works.

---

## Phase 0 — architecture and decisions

### "Walk me through the architecture."

Modular monolith in Spring Boot: identity, chess rules, game, realtime, matchmaking, and
rating modules with boundaries enforced by ArchUnit rather than convention. PostgreSQL is
the only source of truth. Valkey does three jobs — cross-instance pub/sub fanout for
WebSocket messages, atomic matchmaking via a Lua script over a sorted set, and short-TTL
presence — none of which involve holding authoritative game state. Finished games emit a
domain event through a transactional outbox to SQS, where an idempotent consumer updates
Elo ratings. No game state lives in any pod's memory, which is what lets any player
reconnect to any instance and lets rolling deployments happen during live games.

### "What's the hardest problem in this system?"

The clock, because it decides outcomes and therefore cannot be approximately right. The
naive design is a timer per game, which dies with the pod, can double-fire after a
rebalance, and can't be restored. My clock doesn't tick — remaining time is computed from
each side's stored milliseconds, the timestamp of the last move, and the current time. So
it's identical from any pod, survives reconnection to a different instance, and needs no
timers at all. The timestamp comes from PostgreSQL rather than the application server, so
inter-pod clock skew can't accumulate into a player getting free time.

### "How do you prevent two moves being applied at the same ply?"

Three layers, all in the database. A unique constraint on `(game_id, client_move_id)`
makes retries idempotent — a retried move returns the original result rather than
applying twice. A version column on the game row catches genuine write races via
optimistic locking. And the moves table's primary key is `(game_id, ply)`, so double
application is structurally impossible even if the first two layers had bugs.

### "Why not a Redis distributed lock for that?"

Because it wouldn't remove any constraint I already need and would add a failure mode.
Redis locks are TTL-based, so a GC pause longer than the TTL gives you two holders. The
standard fix is a fencing token validated at the storage layer — which is optimistic
locking with extra steps, and I'd still need the database constraint. Adding it would
make my data correctness depend on a cache being available.

### "Why Redis at all, then?"

Three things Postgres is bad at: pub/sub fanout across instances, atomic pop-two-players
matchmaking via a Lua script, and short-TTL presence. Not game state.

### "What happens if Redis dies mid-game?"

Moves still commit, because the database path doesn't touch Redis. What stops is
real-time fanout, so clients fall back to polling the game endpoint on a backoff.
Matchmaking and presence go down. No game is lost and no move is lost. This is tested by
stopping the container during an integration test.

### "What happens if Postgres dies?"

The system stops accepting moves and returns 503. There is no graceful path, and I
document that rather than pretending otherwise. The uncomfortable part is that clocks are
wall-clock-derived, so they keep running during the outage — a player could return to
find they'd flagged. Fixing that properly means a maintenance-mode flag that freezes
deadlines, which I've scoped out but can describe.

### "Why a monolith?"

Extraction is justified by an independent scaling need, an independent deploy cadence, or
independent team ownership. I have one deploy cadence and one owner. The only component
with a different scaling profile is the rating consumer, and that already deploys
separately from the same artifact under a different profile. I enforced module boundaries
with ArchUnit so extraction stays cheap when a real reason appears — `rating` first.

---

### "Tell me about a subtle bug you found."

Refresh-token reuse detection. When an already-used token comes back, I revoke the whole
token family and throw to return 401. Spring rolls back on RuntimeException, so the
revocation was being undone by the very exception that signalled it. The victim still got
a correct 401, so from outside it looked like it worked — the attacker's token just quietly
kept working forever.

I found it because the test asserts the *attacker's* token stops working, not just that the
victim's replay is rejected. Checking only the victim would have passed.

The fix is a separate bean with `REQUIRES_NEW`, so the revocation commits independently.
Two details matter: it has to be a separate bean because self-invocation bypasses Spring's
transaction proxy, and the method has to be public because CGLIB can't proxy non-public
methods and Spring ignores `@Transactional` on them silently. Either mistake silently
restores the original bug.

I didn't use `noRollbackFor` because the method joins an outer transaction, so the outer
boundary decides what commits — I'd have had to annotate every layer, and it would break
the first time someone wrapped the call in another transactional method.

The general rule I took from it: **write-then-throw inside a transaction is a bug unless
the write has its own transaction.** Same trap for audit logging, security events, and
failed-login counters — all the things you most want recorded when something goes wrong.

---

## To be added

Phase 1 — Spring Security internals, JPA mapping and `@Version`, transaction boundaries,
bcrypt cost, keyset pagination.
Phase 2 — WebSocket lifecycle, why publishing inside a transaction is a bug, backpressure.
Phase 3 — isolation levels, `SKIP LOCKED`, the concurrency test.
Phase 4 — Lua atomicity, TTL and eviction, cache invalidation.
Phase 5 — outbox pattern, at-least-once, DLQ.
Phase 6–8 — Docker layering, CI gates, VPC design, IAM, probes, graceful shutdown.
Phase 9 — trace propagation across queues, reading a p99, finding the real bottleneck.
