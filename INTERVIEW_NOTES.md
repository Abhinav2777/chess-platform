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

## Milestone 3.3 — Phase 3 closeout

### "Your rules engine is stateless. How do you detect threefold repetition?"
The history is already in the database — each move's resulting FEN is persisted. The
game service passes the relevant earlier positions into a stateless rules function. The
window is bounded by the FEN halfmove clock: captures and pawn moves are irreversible, so
nothing before the last one can recur — at most 100 rows, one primary-key range scan, and
no query at all below 8 reversible plies. Full answer in ADR-015.

### "What counts as the same position?" (the follow-up that separates people)
FIDE: placement, side to move, castling rights, en-passant *possibilities*. chesslib
writes an en-passant square after every double push even when no capture exists, so a
FEN-prefix comparison misses genuine repetitions. I normalise it away unless a legal
en-passant capture exists — found by running the library, not reading about it.

### "How do you know your clock is really server-authoritative?"
Two measurements, not an argument. A test context runs with the application `Clock` ten
minutes fast and plays a game: moves are charged milliseconds, nothing expires. And I
`kill -9`'d the server mid-game, restarted it, reconnected: the side to move had lost
31,769 ms over 31,798 ms of wall time, the other side 0 ms. The clock ran while no server
existed. Honest limit: a skewed bean can't catch a bare `Instant.now()` — which is how
the one real leak (REST display values) hid until I made `now` a parameter.

### "Your client gets a 409. What does it do?"
Re-subscribes on the same socket and adopts the fresh snapshot. It does *not* resubmit
the move: that move was chosen against a board the player no longer sees, and replaying
it on the new position could play something they never intended. Automatic retry is only
safe when the retried operation means the same thing in the new state — here it doesn't.

### "How many times did you run the concurrency test?"
100 rounds in one context — 1,600 contending submissions, exactly one winner per game,
1.5 s — with the round count published into the test report so the number is evidence.
CI runs 10 rounds; `-Pchess.concurrency.rounds=100` reproduces the soak.

---

## Milestone 4.1 — matchmaking, and a test that checked nothing

### "Tell me about a test that was lying to you." (strongest story in the project)
My ArchUnit module-boundary test had been green since Phase 1 — and checking nothing. The
library version couldn't parse Java 25 bytecode; it logged a warning per class, imported
zero, and every rule had `allowEmptyShould(true)` so the empty project could go green in
Phase 0. I found it because a new import looked like it should have failed the build and
didn't, so I wrote a probe that counted what ArchUnit saw: zero. After upgrading, three of
four rules failed — internal packages used across modules, an entity crossing a boundary,
and a four-module cycle. I fixed them by moving types to their owning modules and widening
two facades, then added a guard that the importer sees the codebase, and mutation-checked
the rule by planting a violation. Lesson: a test you have never seen fail hasn't been shown
to test anything.

### "How do concurrent matchmakers avoid pairing a player twice?"
Pairing is one Lua script; Valkey runs scripts one at a time, so "choose two and remove
both" is atomic across instances. No lock, no leader. Tested with four matchmakers ticking
concurrently while 20 players each seek twice: exactly ten games — and cross-instance, with
two JVMs' schedulers racing over one queue.

### "Pub/sub is fire-and-forget. What if MATCH_FOUND is lost?"
The match is also stored for 60 s. A reconnecting socket is told after AUTH_OK, and a
re-seek returns it. Opening the game acknowledges it — tracked per socket, so an ordinary
subscribe never calls Valkey and can't be slowed by a Valkey outage. Push for latency, pull
for correctness: the same shape as snapshot-on-reconnect.

### "Why did you add REPEATABLE READ to a read?"
The snapshot reads the game row and the move log. Under READ COMMITTED those are two
snapshots, and a move committing between them gives a board and a move list that disagree.
One read-only REPEATABLE READ transaction makes PostgreSQL use one snapshot for both — and a
read-only transaction can't hit a serialisation failure, so it costs nothing.

---

## Milestone 4.2 — rate limiting

### "Design a distributed rate limiter."
Token bucket per key in Valkey, one Lua script for refill-and-take so it's atomic across
instances; two numbers per key, lazy refill from Valkey's clock, TTL of one full refill.
Fixed windows allow 2× at the boundary; sliding logs cost memory per request. Keys by IP
and by username for login (each covers the other's blind spot), by user for moves — one
bucket for REST and WebSocket so switching transport buys nothing. ADR-017.

### "Your limiter depends on Redis. Redis goes down."
Fail open — it's a guard, not a dependency. The subtle part: naive fail-open still waits
out the timeout on every request. A five-second circuit means one slow request per five
seconds per instance. Measured: first move after the outage ~1 s, the rest unaffected.

### "Why didn't you use Bucket4j?"
I evaluated it: built against Lettuce 6 while Boot 4.1 ships 7, and it wants its own
native connection outside Spring's pool. Twenty lines of Lua on existing infrastructure was
lower risk, behind one method so the library can replace it.

### "What's X-Forwarded-For got to do with it?"
Behind a load balancer the remote address is the balancer's. Trusting the header blindly
lets any client pick its own bucket. Trust it only from the ALB (forward-headers strategy).

---

## Milestone 4.3 — degrading when the fanout dies

### "Your pub/sub goes down mid-game. What do players see?"
The socket stays up — sending needs only the instance — but nothing arrives. The client
notices two ways: the server echoes a mover's own move through the same fanout, so a missing
echo after 1.5 s is a precise signal; and the waiting player, who has nothing to echo, polls
once after 10 s of silence on the opponent's turn. Then it polls the same snapshot the socket
would have sent, every 2 s, until an event arrives again. Nothing is lost because Postgres
holds every move; Valkey only carries notifications. I verified it by pausing Valkey
mid-game in a headless-browser test: steady-state opponent latency about two seconds,
immediate recovery.

### "Why pause the container instead of stopping it?"
Two reasons. A paused server holds connections and never answers, so every call waits out
its timeout — the harder failure than connection refused. And it comes back on the same port,
so the test can check recovery.

### "Tell me about something you measured and couldn't explain."
The first degraded move took 4.8 s, not ~2 s. I recorded it as unexplained, then traced it:
WebSocket frames and polls from the browser aligned with server log timestamps. The move
hadn't committed when the first poll ran, and every Valkey call was taking 2 s — a local
profile override from the first commit, doubling the fail-fast timeout. And only one of five
Valkey callers had a circuit. One shared circuit plus one deleted line: 4.8 s → 1.9 s. My
first suspect (the publisher alone) was half right; the trace found the other half.
Cost of the fix, also measured: up to 5 s of recovery lag after Valkey returns. ADR-018.

---

## Phase 5 — outbox and at-least-once

### "How do you publish an event reliably when a game ends?"
A transactional outbox. The event row is written in the same database transaction as the
game's final state — so either both commit or neither does. A relay then claims unpublished
rows with FOR UPDATE SKIP LOCKED, sends them to SQS and marks them published. If it dies
after sending but before marking, the event is sent again; consumers deduplicate on the event
id. What it can never do is lose one. A direct SQS call after commit can: the process can die
between the two.

### "Why is the listener synchronous here, when your broadcaster is AFTER_COMMIT?"
Same principle, opposite direction. A broadcast must not happen for a change that might roll
back, so it waits for commit. The outbox row must commit with the change, so it's written
before. MANDATORY propagation makes a caller without a transaction fail instead of writing an
orphan event.

### "What would you alert on?"
Not the backlog size — the age of the oldest unpublished event. One event ten minutes old is
an outage; fifty events a second old is a busy second.

---

## Milestone 5.2 — exactly-once effects on at-least-once delivery

### "SQS delivers a message twice. How is a rating not applied twice?"
The consumer claims the event id in a processed_events table in the same transaction as the
rating change, with INSERT … ON CONFLICT DO NOTHING — zero rows means it's a duplicate. Not a
caught unique violation: in Postgres a failed statement aborts the transaction. It also works
for two deliveries at the same instant: the second insert waits on the first's index entry,
then sees the conflict. And rating_history has a primary key on (game, user) as a backstop, so
even a second event id for the same game can't rate it twice — it fails into the DLQ instead.

### "When do you delete the message?"
After commit, explicitly. Delete before commit and a crash in between loses the rating; commit
before delete and a crash in between just redelivers it, which the dedupe absorbs. I use manual
acknowledgement so that ordering is written in my code, not implied by a framework default.

### "Two of a player's games finish at once."
Elo reads both ratings first, so without a lock both transactions read the same starting
rating and one result is lost. SELECT FOR UPDATE on both players, always in id order so two
transactions can't deadlock. And I proved the test meant something: my first concurrency test
passed with the lock removed, because the natural race window is milliseconds. I made it
deterministic — two threads on a latch, and a trigger that holds each transaction 300 ms
between reading and committing — and then it failed without the lock: expected 1216, was 1200.

### "How did you test a crash in the middle of the transaction?"
A trigger that raises an exception on the first rating_history insert — after the ratings were
already updated in that transaction. It counts attempts with a sequence, because nextval isn't
rolled back; a marker table would roll back with the failure and fire forever. First attempt
rolls back, redelivery applies it once.

---

## Milestone 5.3 — async result, realtime feedback

### "How does a player see their rating change if rating is asynchronous?"
The worker publishes an application event inside the rating transaction; an AFTER_COMMIT
listener sends RATING_UPDATED through the Valkey user channel — because the worker is usually
not the instance holding the player's socket. Duplicates publish nothing, so the dedupe also
dedupes notifications. It's fire-and-forget; the lobby reads the rating from the database, so
a lost push costs a moment, not correctness. Measured in a browser: 0.8 s from resignation to
"Rating 1216 (+16)" on both screens — outbox, relay, queue, worker and push included.

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
