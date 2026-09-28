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

### "Tell me about a bug that a green build was hiding."

The timeout sweeper. It ran every second, claimed expired games with
`FOR UPDATE SKIP LOCKED`, and reported how many it found — and finalised none of them. It
called its own `@Transactional` method via `this`, which bypasses Spring's proxy, so there
was no transaction at all; the entity was mutated while detached and never saved.

The method returned the right number throughout. What caught it was a test asserting on the
reloaded game rather than on the return value. And fixing it created a new problem: the now
working background job started racing the tests, which it had never done because it had
never done anything.

### "Is calling a @Transactional method on `this` always a bug?"

No, and the precise answer is more useful than the rule. The annotation is ignored on
self-invocation. That is harmless when the caller already holds a transaction the callee
would have joined anyway — in this codebase, `requireGame()` is called from inside
`submitMove()`'s transaction, and that's fine. It's a bug when the annotation was supposed to
*change* something: open a transaction the caller doesn't have, or start an independent one
with `REQUIRES_NEW`.

### "When would REQUIRES_NEW hurt you?"

When the caller holds a lock on the row the inner transaction wants. The inner transaction
runs on a separate connection, waits for the outer transaction's lock, and the outer
transaction is waiting for the inner call to return. PostgreSQL won't flag it as a deadlock
because one side is blocked in application code, not on a lock — it just hangs until the
lock timeout. That's why this project uses `REQUIRES_NEW` to persist a timeout from the move
path, which holds no row lock, but not in the sweeper, which does.

---

## Milestone 3.2 — aborts and the client clock

### "How do you stop someone farming rating with a second account?"

The cheapest loop — challenge, resign instantly, repeat — is closed structurally. Until
both players have made a move, a game can only be aborted, and an aborted game has no
result. Resigning at ply 0 aborts rather than awarding a win. And the database already had
a check constraint forbidding a result on any game that is not FINISHED, so no code path,
including a future bug or a manual fix, can produce a rated abort. Farming by actually
playing games is anomaly detection — a different problem, deliberately out of scope.

### "You added a new way for a game to end. What did it cost?"

Structurally almost nothing, which was the point. The sweeper finds work through one stored
deadline with a partial index. I redefined it from "the mover's flag-fall" to "the next
instant this game needs attention" and stored the minimum of the flag-fall and the
first-move window. Same index, same `SKIP LOCKED` query, same job. *What* to do moved into
one pure method, `expiryAt(now) → NONE | ABORT | FLAG`, with thirteen boundary tests.

The real cost was elsewhere: every consumer written when all endings had a result. The
WebSocket broadcaster called `result().name()`, which would have thrown on the first abort
— inside an after-commit listener, so the database would have been right and every client
silently wrong. The fix was an explicit `status` on the event rather than making consumers
infer "aborted" from a null.

### "With a 10-second game, White flags before the 30-second window. Timeout or abort?"

Abort. Until both players have moved, any expiry aborts. Otherwise Black wins a rated game
White never played a move in — exactly the outcome the rule exists to prevent. The
boundary matches flag-fall: reaching the window exactly aborts, just as reaching zero
exactly flags.

### "How does the browser show a ticking clock if the server never sends ticks?"

It doesn't tick either. Every server message anchors it — the two clock values plus the
local `performance.now()` when the message arrived — and the display is recomputed from the
anchor on every render. A decrementing `setInterval` counter drifts slow (timers fire late,
never early), freezes in background tabs (browsers throttle them), and accumulates both
errors. Recomputing means a late or throttled timer costs smoothness, never accuracy, and
the next move replaces the anchor outright. `performance.now()` rather than `Date.now()`
because it's monotonic — an NTP correction mid-game can't add or remove seconds. And it's
advisory: the client never decides a flag-fall; it waits for the server.

### "Tell me about a flaky test you prevented rather than fixed."

Spring caches test contexts, and a cached context's `@Scheduled` jobs keep running after
its test class finishes. I'd disabled the timeout sweeper in the clock tests, but the
context built for a different test class was still alive, sweeping the same database every
second while the clock tests pushed deadlines into the past. It passed because the window
was milliseconds wide. It would have failed one CI run in a few hundred and nobody would
have reproduced it. The fix is to turn the scheduler off for every context sharing that
database, which also collapsed two cached contexts into one.

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
