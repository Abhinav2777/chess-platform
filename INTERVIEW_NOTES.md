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

## Phase 6 — what the first CI runs found

### "Tell me about a bug CI caught that your machine didn't."
A race in matchmaking. My 20-player concurrency test had passed every local run; on the second CI
run it failed with "finish your current game". A player's duplicate seek read "no match yet",
then a matchmaker paired them and committed the game, then the seek's active-game check found it.
I reproduced it deterministically — a Mockito spy that runs a pairing tick right after the seek's
first read — watched it fail with exactly CI's error, then fixed it by re-reading the match key
before refusing. That's sound because pairing writes the key before the game commits, so the key
is visible no later than the game. Different machine, different timing: that's what CI is for.

### "And the first run?"
The image job failed because image names must be lowercase and my GitHub username isn't. The
metadata action lowercased the tags; my scan step didn't. Nothing local could have caught it —
locally the image was just `chess-platform:dev`.

---

## Phase 10.3 — documentation

### "How do you keep architecture docs from going stale?"
I don't trust them to stay fresh; I check them. At the end I read the architecture document line by
line against the code and found about twenty-five statements that weren't true — mostly plans from
the first week that had changed during the build: a diagram showing a library I never used, a
pagination strategy I'd deliberately not chosen, an alarm that didn't exist. Now every number
links to a report with its date and environment, and anything not built says so.

### "Did checking the docs find anything in the code?"
A real bug. The column for how a game ended was 24 characters, and one of the values — draw by
insufficient material — is 26. So that draw could never be saved. Worse, the move code caught every
integrity error as "duplicate move", so the player was told their move was already submitted and to
retry — a retry that could never work, logged as a normal event. I fixed the column with a
migration and narrowed the catch to the one error code that really means duplicate. The lesson:
catching a broad exception type to mean one specific thing is how bugs become invisible.

---

## Phase 10.2 — failure drills

### "What happens when your database goes down mid-game?"
I drilled it rather than guessing: 20 live games, PostgreSQL frozen for 30 seconds. Every game came
out consistent — nothing a player saw acknowledged was lost — and players already in a game kept
their sockets. Moves were refused while the database was gone, and players carried on afterwards.
What I didn't expect: a request could wait the whole 30 seconds, because the JDBC driver's socket
timeout defaults to forever, and every refusal looked like a server bug — INTERNAL and an ERROR log
line, 127 of them. Now each request is bounded — 2.9 seconds worst case — and the client gets a
retryable "service unavailable", logged as a warning. 127 ERROR lines became one.

### "Why does a socket timeout matter if the database restarts in seconds?"
Because the bad case isn't a restart — it's a silent partition, like a failover that leaves old
connections pointing at nothing. No packet ever says "closed", so without a timeout a request waits
for TCP to give up, which is around fifteen minutes. A crash is the easy failure; a hang is the one
that takes systems down.

### "Your readiness probe checks the database. Isn't that a cascading-failure anti-pattern?"
It does cascade, and I measured it: in a database outage every pod goes unready and new visitors
get the ingress's 503 for about twenty seconds. I kept it anyway, because it's what stops a new
version that can't reach the database from ever taking traffic — the rollout just stalls. I removed
Valkey from readiness for the opposite reason: the app works without it. The rule I use: readiness
lists what the instance cannot serve without.

### "Anything go wrong in the drills themselves?"
Two things worth telling. My first database "freeze" didn't freeze it: PostgreSQL's main process is
PID 1 inside its container, and Linux ignores SIGSTOP sent to a namespace's init from inside it — I
caught it because my own monitoring query kept getting answers. And the database's storage was an
emptyDir, so the obvious way to "stop" it — scaling to zero — would have deleted the data and I'd
have reported data loss I caused myself.

---

## Phase 10.1 — security review

### "How did you review your API's security?"
Against the OWASP API Top 10, in three passes: first I checked every security claim in my own
architecture document against the code — five were untrue, including a CSP and a CSRF token that
didn't exist. Then I probed the running app. Then I fixed each finding with a test over real HTTP,
and checked each test fails when the fix is removed.

### "What was the worst thing you found?"
No limit on request body size. Spring parses the JSON body before the controller runs, so my login
rate limit never saw it. I measured it: four 100 MB login requests took the heap from 58 MB to
2.4 GB. On AWS a task has a 512 MB heap — one unauthenticated request would have killed it. The fix
is a filter at the very front: reject a declared oversize body before reading a byte, and cap
chunked bodies while reading.

### "Anything subtle?"
Two. The deployed signing key: the config had a development default, so if the secret mapping in
the task definition ever broke, the service would have started signing tokens with a key that's
public on GitHub. Now the AWS profile has no default — a missing secret stops startup. And
authorisation: the WebSocket refused to show a game to a non-player, but the REST endpoint served
it. Same data, two rules. That's what broken object-level authorisation usually looks like — not a
missing check everywhere, a missing check in one of two places.

### "Why no CSRF token?"
The API authenticates with a header, which a cross-site page can't set. The one cookie — the refresh
token — is SameSite=Strict, HttpOnly and scoped to /api/auth, so the browser never sends it
cross-site. A token would add nothing there. The residual risk is sibling subdomains, which SameSite
treats as same-site — I wrote that down rather than pretending it doesn't exist.

---

## Phase 9.4 — the AWS load test

### "What happened when you load-tested on AWS?"
Password hashing took the service down, twice, for two different reasons, and neither showed on my
16-core laptop. First, sign-up hashed the password inside a database transaction, so every bcrypt
held a pooled connection idle; about three sign-ups a second emptied the pool. I moved the hash out
of the transaction, with a test that checks no connection is held while hashing. The next run still
failed, which was the interesting part.

### "Why did it still fail?"
Virtual threads. They run on a few carrier threads, and they are never preempted — they give up the
carrier only when they block. bcrypt never blocks. So a burst of hashes occupied every carrier, and a
request that held a connection, got its query result back, and only needed a moment of CPU to finish
and release it, couldn't run. The clue was in the pool's own error: it said it waited 4.8 seconds
with a 3-second timeout — the thread doing the waiting wasn't being scheduled either. I reproduced it
before fixing it: pinned to one CPU, unrelated requests were late by 1.9 s at the median with hashing
on virtual threads, and 0 ms with hashing on a platform thread.

### "How did you fix it?"
Hashing runs on a small pool of platform threads — the OS preempts those, so requests keep getting
CPU — with a bounded queue. When the queue is full the request gets a 503 with Retry-After
immediately instead of waiting longer than the client will. Same burst afterwards: 50 of 50 games, 0
errors, CPU still at 100 % — saturated but not failing. It's the general rule: CPU-heavy work doesn't
belong on virtual threads.

### "Why not just lower the bcrypt cost?"
That trades password security to pass a load test. The capacity answer is CPU: on 0.5 vCPU a sign-up
costs roughly 0.9 vCPU-seconds — about one sign-up a second for the whole deployment. That is a
sizing fact to plan around — bigger tasks, scaling on CPU, or auth as its own service at real scale.

### "What scale did you actually reach on AWS?"
About 100 concurrent sockets, measured — and I'll be precise about why it isn't more: one run I
labelled 250 games used a long ramp, so only about 50 games overlapped. I caught that from the
arithmetic, not the dashboard. A thousand sockets is measured on kind, not on Fargate. Another
correction: I predicted the JVM would see one processor on a half-vCPU task; I logged it, and it
sees two.

### "Tell me about an incident with your infrastructure tooling."
A Ctrl+C during a Terraform apply crashed Terraform while it was saving state. The lock stayed, and
two running ECS services weren't in state — destroy would never have removed them. I checked no
Terraform process was alive, compared the state file with the declared resources and with AWS, found
exactly the two missing, force-unlocked, imported them, and destroyed. Lesson: once an apply
starts, let it finish.

---

## Phase 9.2–9.3 — load testing

### "What was your bottleneck?"
Two, in sequence. At about 1,460 moves a second the connection pool saturated — 10 per pod, up to
170 moves waiting. That alone just slows things down. What turned it into an outage was memory: the
container limit didn't cover the JVM's worst case — heap max plus 212 MB of measured non-heap plus
native — so once the heap had grown, the kernel OOM-killed both pods within three seconds. It was
history-dependent; the same test on fresh pods survived. I made it deterministic by pre-touching the
heap: the old budget couldn't even start. Then I sized it from the measurement, and the same stress
gave zero restarts.

### "Did your optimisation make it faster?"
No, and I don't claim it did — throughput was identical, because the pool is still the limit. It
removed a crash mode. The pool is the next change, measured separately so each result is
attributable.

### "How do you know k6 wasn't the bottleneck?"
I sample its process: under one core of sixteen and 445 MB at a thousand sockets. And I compare k6's
latency with the server's own histogram — they differ by a millisecond or two.

---

## Phase 9.1 — tracing

### "How do you trace across a message queue — and an outbox?"
The producer puts the W3C traceparent in the message attributes; the consumer continues it. The
outbox is the twist: the message is sent later by a scheduled relay in its own trace, so I store the
traceparent in the outbox row and the relay forwards it. One trace now runs from the resign click to
the rating update — and showed a full second of that is the relay waiting for its next poll.

### "Agent or library instrumentation?"
Library, through Spring Boot 4's Observation support: no bytecode agent adding startup time to a JVM
that already takes a minute on small Fargate tasks, and nothing hidden. The cost is that anything the
libraries don't cover is mine — WebSocket frames, which I wrap in an observation per message.

---

## Phase 8.3 — proving the deploy

### "How do you know a deploy doesn't lose games?"
I measured it. A k6 script plays 40 real games over WebSockets; mid-run I restart the deployment.
Every client that's cut gets a 1001, reconnects with jitter, and resyncs. Afterwards every move any
player saw acknowledged is compared against the server, and clocks are checked live — they can
only go down. 40 of 40 games consistent, zero clock anomalies, move round trip p99 13 ms, reconnect
p99 about half a second, which is the client's deliberate jitter.

### "And if a pod crashes instead?"
I tested that too — SIGKILL to the JVM, no drain. Clients see an abnormal 1006 instead of 1001, and
still zero games lost, because moves commit to Postgres before they're acknowledged and clients
resync from the server. The drain is for a clean handoff, not for correctness. One surprise:
kubectl delete with grace period zero and force isn't a crash — the kubelet still sends SIGTERM.

### "What didn't your test prove?"
The idempotent resend never fired — no move happened to be in flight at a cut — so that guarantee
rests on my integration tests, not this run. And the cluster and load generator shared one laptop.

---

## Phase 8.2 — Kubernetes manifests

### "Readiness versus liveness — what's in each of yours?"
Liveness is only "is the JVM alive" — never a dependency, or a database blip restarts every pod.
Readiness is the app's own verdict plus the database. Not Valkey: the app keeps games going without
it, and putting it in readiness would make every pod unready at once — the load balancer turns a
degraded feature into a full outage. I found that, and that my drain wasn't reaching the probe at
all, because listing readiness members replaces Spring's default one.

### "Why no CPU limit?"
CPU limits are enforced by CFS throttling in 100 ms periods. A JVM's GC and JIT threads burst; they
get throttled and latency spikes while the node has idle cores. I set a CPU request for scheduling
and the HPA, and memory limit equal to request, because memory isn't compressible.

### "What's the preStop sleep for?"
Deleting a pod starts endpoint removal and termination at the same time. Without a pause the app
can stop accepting while the ingress still routes to it. Ten seconds of sleep, then SIGTERM, then
my drain closes the sockets with 1001 and the clients reconnect to pods that are already ready.

---

## Phase 8.1 — graceful drain

### "What happens to open WebSockets when you deploy?"
At first: an abnormal 1006 for every client, because Spring's graceful shutdown waits for HTTP
requests and an upgraded socket isn't one. Now a lifecycle step runs before the web server stops —
readiness false, a 1001 "going away, please reconnect" on every socket, newcomers bounced, seeks
kept. The client already had jittered reconnect and snapshot resync from earlier phases; the gap was
the server's. I proved the ordering matters by moving the step after the web server: back to 1006.

---

## Phase 7.5 — the first deploy

### "What broke because you deployed on HTTP?"
Moves. The client generated its idempotency key with crypto.randomUUID, which browsers only expose
in secure contexts — HTTPS or localhost. Every test ran on localhost, so it always worked; on the
load balancer's plain-HTTP origin it was undefined and the click handler threw. No server log, no
console message. I found it by capturing the WebSocket frames — no MOVE was ever sent — and fixed it
with getRandomValues, which works everywhere. Lesson: HTTP-only had a cost I hadn't priced in, and
my browser checks now record uncaught page errors.

### "What happened the first time you deployed?"
The migration step failed — after the migrations had run. The migrate task also started a Valkey
subscriber it didn't need, and Valkey's connection handshake timed out at one second. Two lessons:
a migration shouldn't depend on the cache, and one timeout was doing two jobs. Lettuce bounds the
TLS handshake by the command timeout, and on a quarter-vCPU Fargate task the first handshake took
longer than a second. I reproduced it locally with a proxy that answers two seconds late, then gave
the handshake its own ten-second budget while commands keep one second. And the services never
started on the failed deploy, because they depend on the migrate step.

---

## Phase 7.4 — compute

### "Execution role versus task role?"
The execution role is what ECS needs to start my container: pull the image, read the secrets it
injects, write logs. The task role is what my code can do once it's running. My API has no task
role at all — it calls no AWS API — so a remote-code-execution bug in it gets no AWS credentials.
The worker can use two SQS queues and nothing else.

### "How do you run database migrations on ECS?"
As a one-off task from the same image, before the services roll. Terraform runs it and waits for
exit 0, and both services depend on that step, so a failed migration stops the deploy with the
old version still serving. Migrations have to stay compatible with the running version for the
length of the rollout — expand, deploy, contract.

### "What did you expose that you shouldn't have?"
Actuator on the public port: health details, and Prometheus metrics readable by any logged-in
user. In AWS it's on a separate port now; the load balancer health-checks that port and never
forwards it.

---

## Phase 7.3 — the data tier

### "Why not ElastiCache Serverless? It's cheaper."
It is — about $0.004 an hour cheaper at our size. But Serverless is cluster mode, and my
matchmaking Lua scripts touch keys in several hash slots, so they'd fail with CROSSSLOT — and only
in AWS, because my tests run a single-node Valkey. Making them cluster-safe means putting all
matchmaking keys under one hash tag, switching the client to cluster mode and testing against a
cluster: several hours to save about fifty cents over the project. If the cache were long-lived
I'd do it, starting with the tests.

---

## Phase 7.2 — Terraform bootstrap and CI credentials

### "How does your CI authenticate to AWS?"
It doesn't hold credentials. The job asks GitHub for an OIDC token naming the repository and
branch; AWS STS exchanges it for one-hour credentials of a role whose trust policy requires that
exact repository and `main` — StringEquals, not a wildcard over the owner's repositories. The role
can push to one ECR repository and nothing else. There is no key to leak or rotate.

### "Your OIDC role was refused on the first run. How did you debug it?"
The error only says "not authorized". CloudTrail records the failed AssumeRoleWithWebIdentity
with the token's subject, and it wasn't the one I trusted: GitHub was sending the immutable
format with numeric owner and repository IDs. I changed the trust to that exact string rather
than a wildcard — it's the stronger form, because a name can be re-registered by someone else
after a rename or delete, and an ID can't.

### "Where is your Terraform state, and how do you stop two applies colliding?"
S3 — versioned, encrypted, TLS-only, public access blocked, and prevent_destroy on the bucket.
Locking is S3's native lock file; the DynamoDB table everyone still writes about is deprecated
for the S3 backend. The chicken-and-egg: the bootstrap stack creates the bucket with local
state, then migrates its own state into it.

### "Tell me about a Terraform plan you didn't apply."
Importing my hand-made budget, the first plan said "1 to change". The change was removing a
filter that excludes credits — with credits counted, the budget would show zero while a
forgotten stack spent, and the alarm would never fire. I put the filter in code and re-planned
until the only diff was tags. An import's first plan is a list of places where your code
disagrees with reality.

---

## Phase 7.1 — the app behind a load balancer

### "Your rate limit is per IP. What does it see behind a load balancer?"
The load balancer's address — one bucket for every user. I enable Tomcat's forwarded-header
handling, trust only the VPC's addresses as proxies, and it reads X-Forwarded-For from the
right, so the address used is the one the ALB appended rather than anything the client wrote.
Tested against a real Tomcat both ways, and I watched it fail with the setting off.

### "How do you serve the frontend?"
Baked into the backend image, so page, API and WebSocket share one origin. No CORS in
production, the SameSite=Strict refresh cookie just works, and the socket's origin check passes
without configuring a hostname that doesn't exist until apply. The trade-off is that frontend
and backend deploy together — fine for one team; CloudFront + S3 when they need to diverge.

### "Tell me about a bug that had been there since the start."
Every client error was a 500. My catch-all exception handler caught Spring's own exceptions —
malformed JSON, a bad UUID in a path — and reported them as internal errors with ERROR logs,
which in production would page someone for a client's typo. No test sent a malformed request, so
nothing noticed until a Phase 7 test expected a 404 for a missing file. I wrote four failing
tests first, then fixed it by extending Spring's ResponseEntityExceptionHandler.

---

## Dependency updates — the pins that fought the BOM

### "How do you keep dependencies up to date?"
Dependabot weekly, minors and patches grouped, majors one per PR because those are the ones to
read. CI is the first filter; a Trivy scan catches what Dependabot can't see. But its first run
taught me more than that: five of six PRs were red, and triaging them found a real bug. We
pinned Testcontainers at 1.20.4 while Spring Boot's BOM managed 2.0.5, so the classpath had core
at 2.x and the modules at 1.x — it happened to work. And our AssertJ pin was quietly *downgrading*
Boot's version. The fix wasn't a bump, it was deleting both pins. My rule now: pin only what the
framework doesn't manage, and read `dependencyInsight` for arrows that point down.

### "Did any update need more than a version change?"
Three. Testcontainers 2 renamed its modules and moved classes. Vite 8 and its React plugin
could only move together — each PR failed alone — so I grouped them in Dependabot. And the log
encoder moved to Jackson 3, which took Jackson 2 out of the image entirely and let me delete a
CVE override. I verified that one by running the jar and parsing the log output, because a
logging library can compile fine and break at runtime.

### "Your type-check + build now takes half a second. How do you know it still type-checks?"
I didn't assume it. I planted a type error and watched the build fail with TS2322.

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
