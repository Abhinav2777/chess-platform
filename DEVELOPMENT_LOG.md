# DEVELOPMENT LOG

Chronological record. Newest entries at the top. Each entry: what was done, what was
decided, what was learned, what went wrong.

---

## 2026-09-30 — Phase 6 complete

PR #7 (the image-name fix and the matchmaking race fix) merged; the run on `main`
(36616824363) was green end to end and published `ghcr.io/abhinav2777/chess-platform` tagged
`main` and the commit SHA. The owner made the package public and protected `main` with the
three checks. Verified anonymously through GitHub's API and the GHCR registry API.

Done-when: a push to `main` → a scanned, tagged image with no manual steps ✅; a failing test
turned a PR red ✅ (a real race, not a staged one — the owner chose not to stage another).
Protection went on after that PR merged, so blocking a red PR is configured but not yet seen.

Dependabot's first run opened 6 PRs, four of them major versions — triage before Phase 7.

**Hours:** Phase 6 ~6.5 of 8–10.

---

## 2026-09-30 — 6.2: the second CI run found a real race

The fix PR's run (99154576839): `frontend` green, `backend` red — the 20-player matchmaking test,
`ALREADY_IN_GAME`, after passing every local run and CI's first. Not flaky test code: a real race.
A seek read "no match yet"; a matchmaker paired the player and committed the game; the seek's
active-game check then found it and refused a player who had just been matched.

**Fixed** by re-reading the match key before refusing (sound by write order: key before commit).
**Reproduced deterministically first** — a Mockito spy on `MatchQueue.matchOf` runs a tick right
after the seek's first read — and seen failing with CI's exact error. A first hook, on
`GameFacade.hasActiveGame`, failed for the wrong reason (the forced tick joined that read-only
transaction and could not insert); caught by reading the failure message instead of trusting red.
20-player test then passed 5 more runs; full suite 90 + 127 green. ADR-016 correction.

**Lessons:** a concurrency test on one machine explores one machine's timing. And a test written
to reproduce a bug must fail *with that bug's message*, or it has not reproduced anything.

---

## 2026-09-30 — 6.2: the first CI run ever

The owner created `main` and made it the default branch; CI ran for the first time in the
project's life (run 99141810504).

- **`backend` green** — wrapper validation, pinned Gradle, unit (48 s) and the full
  Testcontainers integration suite (2 m 21 s): its first run on any machine but the dev box.
- **`frontend` green** — type-check and production build.
- **`image` red** — Trivy exited in 5 s: `failed to parse the image name`. The job's `IMAGE` was
  `ghcr.io/${{ github.repository_owner }}/…` = `ghcr.io/Abhinav2777/…`; docker/metadata-action
  lowercases on its own, so the image had been built as `ghcr.io/abhinav2777/…`. Image names
  must be lowercase. Invisible locally, where the image was `chess-platform:dev`.
  **Fix:** a first step sets `IMAGE=ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/chess-platform`
  (GitHub expressions have no `lower()`).

**Lesson:** the pipeline's first run found a bug no local run could have: identity-dependent
values (owner, repository, ref) only exist in the real environment. Expected — which is why the
"done when" is a real run, not a lint.

---

## 2026-09-29 — Milestone 6.2: the pipeline

**Built:** `ci.yml` (backend, frontend, image with Trivy-before-push to GHCR), `e2e.yml`
(nightly + manual browser checks against the real stack), `dependabot.yml`, and branch
protection as a JSON file. ADR-022.

**Decisions worth remembering**
- **Scan before push** — build, load, scan, then push the same layers from cache.
- **SHA-pinned actions** — every action moved one or more majors since Phase 0 (checkout
  v4 → v7); tags are mutable, SHAs are not; Dependabot maintains them.
- **The frontend enters CI** — it never was; its production build once stayed broken for two
  milestones.
- **Protection as code**, applied with `gh api`, enforced for admins.

**Verified:** `actionlint` + shellcheck clean. **Not verified: a single run on GitHub** — CI
needs `main`, which needs the owner's credentials. 6.3 is that run, and the failing-test PR.

**Hours:** ~2. Phase 6 at ~5 of 8–10.

---

## 2026-09-29 — Milestone 6.1: the container image

**Decided (with the owner):** `main` + PRs; GHCR now, ECR in Phase 7; browser checks nightly +
manual.

**Found before building:** CI has never run. The workflow triggers on `main` and PRs; GitHub
has one branch (`users/Abhinav/initial`, the default), no PRs, zero workflow runs. Phase 0's
"CI confirmed working" was never backed by a run — the third green claim without evidence
(after ArchUnit and the first concurrency test).

**Built:** `backend/Dockerfile` + allowlist context, `resolveDependencies`, `worker` and
`migrate` profiles, `MigrateAndExit`, compose `--profile app`. ADR-021.

**Verified:** see ADR-021 — cold 142 s / cached 17.7 s, app layer 565 kB, uid 10001, migrate
exit 0, Postman + `e2e:lobby` against containers (rating across containers in 799 ms), graceful
stop with exit 143.

**The scan did its job on the first run:** 3 CRITICAL in Tomcat 11.0.24, 2 HIGH in Jackson,
all in Boot 4.1.1's managed versions, no Boot patch available. Overridden through Boot's own
properties, suite green, rescan clean. The CI gate would have been red from day one — as it
should.

**Found along the way**
- **This machine's containers have no internet:** daemon DNS points at an absent resolver on
  172.17.0.1, and bridge egress is blocked. Not changed (system config, the owner's call);
  verified with a temporary dnsmasq forwarder + `--network=host`, then removed.
- **Production rate limits in a dev stack** refused the third browser-check registration — the
  limiter right, the stack mis-sized; dev limits via `SPRING_APPLICATION_JSON` (dashed map keys
  cannot be env var names).
- **Exit 137** — the OOM killer, from the test suite running beside the app containers.

**Hours:** ~3. Phase 6 at ~3 of 8–10.

---

## 2026-09-29 — Milestone 5.3: rating changes pushed — Phase 5 complete

**Built:** `RatingsChanged` (rating's public event, published in the rating transaction) →
`RatingAnnouncer` (`AFTER_COMMIT`) → `UserNotifier` → `RATING_UPDATED`. Client shows the change
on the finished-game panel; the lobby shows the current rating from `/api/users/me`.

**Decided:** a separate `RATING_UPDATED` rather than a `ratingDelta` on `GAME_FINISHED` (the
Phase 0 plan). The result is known at commit; the rating a second later, from another process.
One message per fact.

**Verified:** realtime integration test (both players, after commit; duplicate sends nothing);
browser — `e2e:lobby` resigns the game and waits for both ratings: **0.8 s** end to end through
outbox, relay, ElasticMQ, worker and push.

**Minor:** an edit script (Python triple quotes around a Java text block) applied nothing —
caught by the compiler, redone with direct edits. The browser check's 5 s sign-in wait failed
once against a just-restarted backend; raised to 15 s. And PROJECT_STATE's cumulative-hours
line had gone stale at ~67 h since Phase 4's follow-up — corrected to ~76.5 h.

**Hours:** ~1.5. **Phase 5 complete at ~9 h** (10–14 planned).

---

## 2026-09-29 — Milestone 5.2: the rating consumer

**Built:** V7 (`processed_events`, `rating_history`); `rating` module (Elo, `RatingService`,
`@SqsListener` consumer with manual ack); identity's `lockRatings`/`setRating`; messaging's
`QueueBootstrap`, visibility timeout and `QueueDepthMonitor`.

**Design points**

- **Claim with `ON CONFLICT DO NOTHING`, not a caught PK violation** — a failed statement
  aborts a PostgreSQL transaction. Concurrent duplicates serialise on the index entry.
- **Ack after commit, written out** — the correctness argument lives in our code.
- **Lock order by id** — no deadlocks between two games sharing a player.
- **Backstop fails loudly** — a second rating of one game is a DLQ entry, not a silent no-op.
- **Unknown schema version → DLQ; unknown event type → ack and count.** A newer producer's
  message waits for a consumer that understands it; another producer's is not ours to fail on.

**Found**

- **A concurrency test that proved nothing.** Two messages through the listener: green —
  and still green with the lock removed. The race window (read → commit) is milliseconds.
  Rewritten deterministic: two threads on a latch, a trigger sleeping 300 ms between read and
  commit. Mutation check: without the lock it fails, 1200 instead of 1216. **Second time this
  project has had a green test that could not fail (ArchUnit, 4.1b); mutation-checking new
  concurrency and architecture tests is now habit, not an afterthought.**
- **Container config validated at startup:** `maxMessagesPerPoll` (10) > `maxConcurrentMessages`
  (5) refused. Tied together.
- **Crash injection needs a non-transactional counter** — a sequence, since a marker row
  would roll back with the failure it records.

**Verified:** unit 90, integration 125; locally, 7 backlogged events rated 80 ms after start,
Postman games rated live, DLQ empty.

**Hours:** ~3.5. Phase 5 at ~7.5 of ~12–15.

---

## 2026-09-29 — 5.1 migrated to Spring Cloud AWS (ADR-020)

**Request:** use Spring Cloud AWS for SQS, e.g. `spring-cloud-aws-starter-sqs:3.4.0`. This
reversed ADR-011, so it was checked before changing anything:

- **3.4.0 is the Boot 3.5 line** (Spring Cloud Commons 4.3) — it would have resolved on Boot 4.1
  and then failed or silently not configured. The Boot 4 line is 4.x; latest 4.1.1, built
  against Boot 4.0.8.
- The starter does not pull `spring-cloud-commons`, so Spring Cloud's Boot-version verifier
  never runs. The SDK resolves to our 2.55.6 via BOM order. The AWS Netty client (4.1.138
  requested) runs on Boot's Netty 4.2.17.

**Changed:** hand-built `SqsConfig` deleted; `SqsAsyncClient` auto-configured from
`spring.cloud.aws.*`; `SqsSetup` adds a timeout customizer and our own `SqsTemplate` with
`QueueNotFoundStrategy.FAIL` — the default would create queues without a redrive policy; relay
on `sendMany` with an `eventId` header to map per-message results.

**Verified:** all outbox/relay tests pass on the new stack, plus a new test that the template
never creates a queue; unit 82, integration 120; local stack end to end; startup time
unchanged within noise (I misremembered a 3.5 s baseline — the logs say 7–8 s throughout).

**New cost, found in the startup log:** a Java 25 warning that Netty's native loading will be
blocked in a future release. `--enable-native-access=ALL-UNNAMED` added to `bootRun` and
tests; the Phase 6 Dockerfile must carry it.

**Hours:** ~1. Phase 5 at ~4 of ~12–15.

---

## 2026-09-28 — Milestone 5.1: outbox, relay, SQS

**Checked before building:** LocalStack — the roadmap's plan — has required an account and auth
token since March 2026. ElasticMQ instead (ADR-019), chosen with the project owner. The owner
also chose to push rating changes to players (5.3), previously SKIP.

**Built:** `V6__outbox.sql`; a `messaging` module (outbox API, relay, SQS client, queue
bootstrap); the `GAME_FINISHED` contract and the listener that writes it.

**Decisions worth remembering**

- **One listener covers every ending.** Four code paths end games; all already publish
  `GameEnded` in-transaction. A `MANDATORY` synchronous listener writes the outbox row — the
  mirror image of the `AFTER_COMMIT` broadcaster, for the mirror-image reason.
- **At-least-once by construction.** Claim, send and mark in one transaction; a crash
  between send and commit resends. Duplicates are the consumer's job; losses are impossible.
- **API instances never need SQS to start** — queue URLs resolve on first use.
- **One HTTP client.** The SDK drags in Netty *and* Apache clients by default; both excluded
  for the URL-connection client, which also avoids a second Netty beside Lettuce's.
- **Alarm on age, not count** — `chess.outbox.oldest_age_seconds`.

**Verified:** 11 new integration tests, including every ending path (the move-path flag, where
the event must survive the move's rollback), two concurrent relays sending 30 events exactly
once, and SQS down leaving the event in place. SDK 2.55.6's JSON protocol works against
ElasticMQ 1.7.1 — the plan's main risk, retired in the first test. On the local stack:
Postman's games → 3 events → 3 messages queued.

**Corrected:** ARCHITECTURE §9 said producers write events *after* the game transaction
commits — the opposite of an outbox.

**Hours:** ~3. Phase 5 at ~3 of ~12–15.

---

## 2026-09-28 — Phase 4 follow-up: the unexplained 4.8 s, traced and fixed

**Method.** A diagnostic browser script recorded every WebSocket frame and poll for the first
move after pausing Valkey, with wall-clock times, aligned against the server log. Hypotheses
tested, in order:

1. *A PING queued ahead of the MOVE on the session* — **rejected**: no PING in the window.
2. *The fanout publisher blocking* — **half right.**
3. The trace showed the move **not committed** at the first poll (1.6 s), and the server log
   showed the rate limiter failing open **1.99 s** after the MOVE and the publish failing 2 s
   after that. Every Valkey call took 2 s. Tests measured 1 s. The difference was
   `application-local.yml`: `spring.data.redis.timeout: 2000ms`, there since the first
   commit, silently overriding the fail-fast 1 s.

**Fix (ADR-018).** Override removed. `ValkeyGuard`: one circuit per instance, used by every
degradable Valkey caller; the rate limiter's private circuit folded in.

**Result:** mover's first degraded move 4.8 → 1.9 s; steady state 0.9–2.0 s.

**What the fix cost — found by a failing test.** The outage test's recovery step broke: the
first move after unpause was not broadcast. The circuit, still open, did not yet know Valkey
was back. Recovery now lags up to one window (5 s). That is the trade, documented rather than
tuned away; the test and the browser check wait out the window and then require live events.

**And a flaky test, caught.** The WS flood test asserted <= 22 of 25 moves reach the
pipeline, then failed at 23, then at 25. At 20/s a token refills every 50 ms, so the count
depends on processing speed; on a cold JVM nobody exceeded the rate at all. I first suspected
the new circuit (a spurious trip) — the logs showed no trip, so that was wrong. The test's
context now uses 5 per 10 s, where refill during the burst is nil: exactly 5 through, 20
refused.

**Lessons**
- A development profile must not change failure timing (checklist item added).
- "Unexplained" was the right thing to write down; the trace found a cause I had not guessed.
- A resilience mechanism has a recovery cost. Measure both directions.

**Hours:** ~1.5. Phase 4 total ~13.5 of 12–16.

---

## 2026-09-28 — Milestone 4.3: Valkey outage end to end — Phase 4 complete

**Design question first:** in the `local` profile (in-JVM fanout) Valkey carries no moves, so
"stop Valkey" changes nothing there. The scenario that matters is `fanout=valkey` with players
on different instances: the socket stays up, sending works, and nothing arrives.

**Built**

- `ValkeyOutageIntegrationTest`: Valkey **paused**, not stopped — a hung server (every call
  waits for its timeout) is the harsher failure, and unpausing keeps the port so recovery is
  testable. A full scholar's mate with Valkey down: 7/7 moves, correct result; seek refused
  cleanly; live events after unpause.
- Client fallback in `useGame`: two detectors (own-echo timeout 1.5 s; for the waiting player,
  10 s of silence on the opponent's turn), then 2 s polling of the same snapshot the socket
  sends, until an event arrives. Forward-only apply, ERROR clears the pending echo, resign
  gets a follow-up poll. "Live · updates delayed".
- `frontend/e2e/lobby.mjs` and `outage.mjs` committed as documented manual checks.

**Decided:** reconcile at 10 s rather than 25 s. The waiting player has no echo to miss, so the
reconcile interval *is* their detection time; 10 s costs ~100 req/s at 1,000 concurrent
players. The better long-term signal is server-side (an instance sees its own subscription
drop) — recorded, not built.

**Measured in a browser** (PROJECT_STATE §12): healthy 0.13 s; first outage move 4.8 s for the
mover and 10.6 s for the waiting opponent; steady state 0.8–3.9 s; < 0.1 s after unpause.

**Not explained:** the mover's first degraded move (4.8 s vs ~1.5–2.5 s expected). The fanout
publisher has no circuit, so each broadcast blocks its socket thread for the 1 s Redis timeout
during an outage — the leading suspect, not verified. Recorded as the first thing to measure.

**Also fixed:** PROJECT_STATE §3 had been left with a broken bullet by a 4.2 edit.

**Hours:** ~2. Phase 4 complete at ~12 of 12–16.

---

## 2026-09-28 — Milestone 4.2: rate limiting

**Decided (with the project owner):** a Lua token bucket rather than Bucket4j, and fail open
for login as well as moves. Bucket4j 8.20's Lettuce module is compiled against Lettuce 6.1;
Boot 4.1 ships 7.5 — an untested major-version gap — and it wants its own native connection
outside Spring's factory. ADR-017.

**Built:** `RateLimiter` in `common` (not `platform`, which would recreate the 4.1b cycle),
`token-bucket.lua`, five policies, `429` + `Retry-After`, socket `RATE_LIMITED`, a 5 s
circuit after a Valkey failure, metrics `chess.ratelimit.rejected{limit}` and
`.unavailable`.

**Found while building**

- **Environment-dependent tests.** `AuthApiIntegrationTest`'s refresh tests failed locally
  with 429. The context sets no Valkey host, so it used `localhost:6379` — my compose Valkey —
  and real limits applied. In CI nothing listens there and the limiter fails open: green in
  CI, red on a laptop. Contexts not about rate limiting now disable it; the checklist has
  the general rule. It also restores `IntegrationTestBase`'s own claim that nothing in it
  touches Valkey.
- **The outage test measured what fail-open costs.** It slept a fixed second and failed:
  the first move after Valkey dies waits out the 1 s command timeout before the limiter
  fails open. Now it polls with a deadline and prints the delay — 1,037 ms — and the
  circuit makes later moves free. A bounded, documented cost instead of a hidden one.

**Verified:** unit 77, integration 107, Postman 54 / 132 against the live app with limits
on, browser matchmaking check still green, `rl:*` buckets visible in Valkey.

**Hours:** ~2.5. Phase 4 at ~10 of 12–16.

---

## 2026-09-28 — Milestone 4.1c: lobby UI — Milestone 4.1 complete

**Built:** "Play online" buttons per preset; `useSeek` (lobby socket, seek, re-seek every 15 s
and on every reconnect, elapsed time, Cancel with a 3 s fallback to closing the socket);
`MATCH_FOUND` → the game view, reading the new game from `GET /api/games` (which carries
usernames) rather than adding an endpoint.

**Decided:** one socket class, not two. `GameSocket` takes an optional `gameId` — without one
it authenticates and does not subscribe — and its game handlers are optional. A separate lobby
socket would have been a second copy of reconnect, backoff, heartbeat and first-frame auth.

**Verified in a real browser, for the first time in the project.** Headless Chromium via
playwright-core, two isolated contexts as two users: register → click 5+3 → both on the same
game with opposite colours 0.3–0.8 s after the second seek → White plays e4 by clicking squares
→ Black sees it, "Your move", clock ticking 4:59 → 4:57 → third user seeks and cancels.

**Found by looking:** the board was not square. With only `grid-template-columns`, rows were
implicit and sized by content, so empty ranks were visibly shorter. Fixed with explicit
`minmax(0, 1fr)` tracks on both axes. It had been like that since Phase 2; no test checks
pixels, and nobody had opened the page in a normal browser window since.

**Hours:** ~1. Milestone 4.1 total ~7.5 h (incl. ~1.5 h ArchUnit fix). Phase 4 at ~7.5 of 12–16.

---

## 2026-09-28 — Milestone 4.1b: matchmaking over WebSocket — and a test that checked nothing

**Built:** `SEEK` / `CANCEL_SEEK` / `SEEK_STATUS` / `MATCH_FOUND`; `UserNotifier` (local and
Valkey `user:{id}` with a `user:*` pattern subscription); `MatchAnnouncer`; sockets indexed by
user; unseen match re-sent after `AUTH_OK`, acknowledged on subscribe; seek cancelled on close.

**The finding: `ModuleBoundaryTest` had never checked anything.**

Writing the handler, the import list showed `realtime` using `game.internal.GameService` and
`identity.internal.JwtService` — exactly what `internalPackagesAreModulePrivate` forbids — and
the test was green. A probe test printed what ArchUnit imported: **zero classes**, with a
`Couldn't import class` warning for each. ArchUnit 1.3.0 cannot parse Java 25 class files
(major version 69). Every rule carried `allowEmptyShould(true)`, added in Phase 0 so the empty
project could go green, and that turned "checked nothing" into "passed".

After upgrading to 1.5.1, three of four rules failed:

- `identity.internal.JwtService` used by `platform` (HTTP filter) and `realtime` (socket auth).
- `game.internal` (`GameService`, `ServerClock`, `SubmitMoveCommand`) and the `MoveRecord`
  entity used by `realtime`.
- A cycle game → identity → platform → realtime → game, via `AuthProperties` and
  `AuthenticatedUser` (identity concepts living in `platform`) and `WebSocketConfig`
  (realtime wiring living in `platform`).

Fixed by moving each type to the module that owns it and widening two facades
(`IdentityFacade.verifyAccessToken`; `GameFacade.state`, `submitMove`, `resign`). Then a guard
test that the importer sees a class from every module, and a mutation check — a planted
`realtime → game.internal` reference turned the rule red, then was removed.

**The refactor surfaced a real latent bug.** Both REST `GET /games/{id}` and `GAME_SNAPSHOT`
read the game row and the move log as separate statements under READ COMMITTED. A move
committing between them yields a board at ply N and a log of N+1 moves. `GameFacade.state`
now does both reads, and `SELECT now()`, in one read-only `REPEATABLE READ` transaction —
one PostgreSQL snapshot, and read-only so it cannot fail on serialisation.

**Lessons**
- A test you have never seen fail has not been shown to test anything. Mutation-check new
  architecture and security rules once.
- A permissive escape hatch added for bootstrapping (`allowEmptyShould`) outlives its reason.
  Guard the input, not just the rule.
- Anything that parses bytecode lags the JDK and may degrade to warnings.

**Verified:** unit 76 (guard added), integration 101 — incl. matchmaking over real sockets
with the live scheduler, reconnect recovery and acknowledgement, cancel on close, and a
cross-instance match with two JVMs' matchmakers racing over one queue.

**Hours:** ~3.5 (≈1.5 of it the boundary fix). Phase 4 at ~6.5 of 12–16.

---

## 2026-09-28 — Milestone 4.1a: matchmaking core

**Decided before building (with the project owner)**

- **The `game:{id}:state` read cache is dropped.** It was on the roadmap's must-implement
  list. Game reads are PK lookups; the move pipeline must hit PostgreSQL anyway; a cache would
  add invalidation risk to the correctness-critical path for no measured gain. Revisit on
  Phase 9 evidence.
- **Matches are pushed over WebSocket** rather than polled (owner's call, for UX and real-time
  depth; ~2–3 h). Push needs a pull fallback because pub/sub is fire-and-forget, so matches
  stay readable for 60 s — the ADR-007 pattern again.

**Built** — ADR-016: two ZSETs per preset queue, a TTL'd seek key, a PENDING/gameId match key,
five Lua scripts, a leaderless `Matchmaker` on every instance, `MatchmakingFacade`, `GameFacade
.startGame`/`hasActiveGame`, `503` via a new `DomainException.Unavailable`, four metrics.

**Design points worth remembering**

- **The idempotent re-seek carries three jobs**: heartbeat (refreshes the TTL), repair
  (`ZADD NX` re-adds a lost entry without resetting the join time), and crash recovery (after
  a PENDING claim lapses, the next re-seek queues the player again). One mechanism instead of
  three is less to get wrong.
- **Queue time comes from Valkey `TIME`** inside the scripts — one clock for waiting, as
  PostgreSQL `now()` is one clock for games.
- **The commit point is the game row.** Everything before it in Valkey is disposable.
- **Oldest-first makes the window effectively symmetric**: anyone older than the current seeker
  was visited first, with a wider window, and found nobody — so no player is paired outside
  what their own window would allow.

**Known limit, recorded:** `pair.lua` and `cancel.lua` build key names at runtime. Fine on one
node; Valkey Cluster requires declared, same-slot keys. ADR-016 describes the re-keying.

**Verified:** `MatchmakingIntegrationTest` 13/13 (real Valkey + PostgreSQL), including 20
players × 2 concurrent seeks against 4 concurrent matchmakers → exactly 10 games, every player
in one, no queue or seek key left. Full suite on `--rerun-tasks`: unit 75, integration 95.

**Hours:** ~3. Phase 4 at ~3 of 12–16.

---

## 2026-09-28 — Milestone 3.3: Phase 3 closeout

**Built**

- **Threefold repetition (ADR-015).** The game module reads the positions since the last
  capture or pawn move — the FEN halfmove clock says how many, never more than 100 — and
  hands them to a stateless `ChessRules.isThreefoldRepetition`. The starting position (ply
  0, not a row in `moves`) is added explicitly. Automatic draw on the third occurrence.
- **REST clocks from `ServerClock`.** `GameSummary` takes `now` as a parameter.
- **`GAME_SNAPSHOT.moves`** — the SAN log, from rows the snapshot was already loading.
- **Client resync on `CONFLICT`** — re-`SUBSCRIBE` on the same socket; the move is not
  resubmitted.
- Cleanups: Boot's default in-memory user excluded; local SQL logging opt-in; unused `Clock`
  removed from `GameService`.

**Found while building**

- **The planned repetition key was wrong.** Phase 1 recorded the fix as "count over
  `fen_after` keyed on the first four FEN fields". Running chesslib showed it writes an
  en-passant square after *every* double push (`...b KQkq e3 0 1` after 1.e4, no black pawn
  in reach). The position right after a double push would never have matched its later
  recurrences, silently missing real draws. The key now keeps the square only if a legal
  en-passant capture exists (FIDE 9.2.3). **Lesson: a plan written down months earlier is a
  hypothesis; check the library's actual output before building on it.**
- **A dead branch with a wrong label.** `outcomeOf` mapped any unexplained `isDraw()` to
  `DRAW_REPETITION`. Unreachable today; wrong the day it fired. Removed.
- **Auto-resync would have made a known gap worse.** Every resync replaces client state
  from the snapshot, and the snapshot had no move list — so each conflict would have wiped
  the list. That is why `moves` joined the snapshot in this milestone rather than later.
- **Bind-parameter TRACE logging printed credential hashes** in the local profile. Now
  opt-in, with a warning.

**Phase 3 "done when" — measured (PROJECT_STATE §12)**

- *Concurrency test 100×:* a rounds test runs the 16-thread race on fresh games in one
  context. 100 rounds, one winner each, 1.49 s. The round count is published into the test
  report via `TestReporter`, because a 1.5 s runtime looked too fast to take on trust. CI
  runs 10.
- *Skewed JVM clock:* `ClockSkewIntegrationTest` runs with the `Clock` bean 10 min fast and
  plays a game — charged milliseconds, nothing expired, REST clocks full. Honest limit,
  recorded in the test and ADR-006: a skewed bean cannot catch a bare `Instant.now()`, which
  is exactly how the REST leak hid. That class of bug is closed by the `now` parameter.
- *Server killed mid-game:* `kill -9` on the application JVM with White on move, cold
  restart, reconnect by WebSocket. White's clock dropped 31,769 ms over 31,798 ms of wall
  time; Black's by 0 ms; board and move list intact. (Stand-in for the roadmap's `docker
  kill`: the app is not containerised until Phase 6. Same property — no graceful shutdown,
  no state carried over.)

**Verified:** unit 75/75 (8 new), integration 82/82 (4 new), `npm run build`, Postman 54
requests / 132 assertions (folder 6 new), Boot 4 checklist greps clean.
**Not verified:** the React half of conflict-resync and the clock rendering in a real
browser (PROJECT_STATE §4).

**Hours:** ~4. Phase 3 complete at ~18 of 16–20.

---

## 2026-09-28 — Milestone 3.2: games nobody started, and the clock in the browser

**Built**

- **First-move abort (ADR-014).** Each player has 30 s from when their clock starts to make a
  first move. Until both have moved, any expiry aborts: `status ABORTED`, `termination
  ABANDONED`, no result. One method, `Game.expiryAt(now) → NONE | ABORT | FLAG`, used by the
  move pipeline and the sweeper alike.
- **No new machinery.** `turn_deadline` now means "the next instant this game needs
  attention" — `min(flag-fall, window end)` while `ply < 2`. The partial index, the
  `SKIP LOCKED` query and the sweeper are unchanged. `V5` backfills in-progress games.
- **Early resignation aborts.** Resigning at ply 0 or 1 used to be a rated win for the
  opponent — the whole rating-farming loop in one request. The UI button reads "Abort".
- **`GameEnded.status`** and a `status` field on `GAME_FINISHED`, so consumers never infer
  "aborted" from a null result.
- **Client clock.** Anchored on every `GAME_SNAPSHOT`/`MOVE_MADE`, computed from
  `performance.now()`, never decremented (ARCHITECTURE.md §6.6). Time-control presets in the
  lobby. Readable endings ("Black wins on time", "Game aborted — no rating change").
- Postman folder 5: time controls, validation, early-resignation abort. 44 requests.

**Found by review, before anything ran**

- **The broadcaster would have thrown on the first abort.** `event.result().name()` was
  unconditional — correct for every ending that existed, and a `NullPointerException` the
  moment one had no result. It runs in an `AFTER_COMMIT` listener, so the abort would have
  committed and *no client would have been told*: database right, every screen wrong, no
  error anywhere a user could see. Now null-safe, and covered by an end-to-end test in which
  the real scheduled sweeper aborts a game and both sockets must hear about it.
  **General lesson: adding a state is cheap; the cost is in every consumer that assumed the
  old states were all there were.** Grep for accessors of the fields whose invariants you
  just loosened.
- **A latent test race left over from 3.1.** 3.1 disabled the scheduled sweeper in
  `ClockIntegrationTest` only. But Spring caches test contexts, and a cached context's
  `@Scheduled` tasks keep running: the context built for `GameplayIntegrationTest` stayed
  alive and swept the *same* database every second while `ClockIntegrationTest` pushed
  clocks into the past. It passed only because the window between `expire()` and the
  explicit sweep is a few milliseconds. Now disabled for every `IntegrationTestBase`
  context — which also means they share one cached context instead of two.
- **`npm run build` had never passed.** No `vite-env.d.ts`, so `import.meta.env` was untyped
  and `tsc -b` failed. `npm run dev` does not type-check, which is how it survived Phase 2.
  First found now because this is the first milestone where the production build was run.

**Changed expectations in existing tests** — the rule working, not regressions:
- Flag-fall tests in `ClockIntegrationTest` expired games at ply 0, which is now an abort.
  They play 1.e4 e5 first so they still test timeouts.
- Three resignation tests resigned at ply 0/1. `GameplayIntegrationTest` and
  `RealtimeGameplayIntegrationTest` now move first; `ValkeyFanoutIntegrationTest`
  deliberately keeps the early resignation and asserts the abort, because a null `result`
  crossing Valkey and back is the harder fanout case.

**How this was verified here, and what was not**

This workspace cannot reach Maven Central or the Gradle distribution server, so Gradle
could not run. Instead: every changed main class (and the classes they depend on) compiled
with `javac --release 21 -Xlint:all,-serial,-processing -Werror` against minimal stubs of
the Spring/JPA/Micrometer types; `GameExpiryTest` ran through a stub harness, 13/13; the
client clock module's arithmetic ran under Node with assertions; `tsc -b` and `vite build`
passed. **The integration tests and folder 5 of the Postman collection have not run.** Their
first run is on the development machine.

**Verified on the development machine (2026-09-28)**

- `./gradlew :backend:test` — 67/67. `./gradlew :backend:integrationTest` — 78/78, including
  `ClockIntegrationTest$Abort`, the realtime end-to-end abort and the Valkey fanout of a
  null result.
- `bootRun` (local profile): Flyway migrated 4 → 5; `flyway_schema_history` lists V1–V5, all
  successful. The V5 backfill was a no-op here — no ACTIVE games existed — so its `UPDATE`
  is correct by inspection, not by observation.
- `npm run build` — passes for the first time (`tsc -b` + `vite build`).
- Postman via newman: **44 requests, 110 assertions, 0 failures**, folders 0–5.
- Live abort check, scripted instead of two browsers (Node `WebSocket` + `fetch` against the
  running server, real scheduled sweeper). An untouched game: both players' sockets received
  `GAME_FINISHED {status: ABORTED, result: null, termination: ABANDONED}` at 30.0 s. A game
  where only White moved: Black's socket received the same at 31.0 s (window starts at
  White's move, plus up to one sweep interval). Black's late move afterwards → `422
  GAME_NOT_ACTIVE` — not `GAME_ABORTED`, correctly, because the sweeper got there first;
  `GAME_ABORTED` is the move-path race, covered by the integration test.
- **Not verified:** the clock *rendering* in a browser (countdown, low-time red). The
  server-side data it renders from was checked in the snapshots above.

**Noticed while verifying, not fixed:** the `local` profile logs every SQL statement at
DEBUG, so the once-a-second sweep produces ~100k tokens of log in a few minutes and buries
everything else; and Spring Boot still auto-configures an unused in-memory
`UserDetailsService` ("Using generated security password" at startup). Both are small
cleanups for 3.3.

**Deliberately not built:** a live countdown of the abort window in the UI (the server does
not send the window's end, and hard-coding 30 s twice to animate it is poor value — the hint
text says it), and configurable window length (no second value has ever been wanted).

**Hours:** ~5. Phase 3 at ~14 of 16–20.

---

## 2026-09-14 — Milestone 3.1 fixes: two rules I had already written down

Getting 3.1 green took five rounds. The failures fall into three groups, and the third is
the one that matters.

**1. A static initialisation order bug.** `TimeControl.BLITZ_5_3` was declared above the
limits its constructor validated against. Static fields initialise top to bottom, so
`MAX_INITIAL_MS` was still `0` when the constructor ran, and five minutes was rejected as
out of range. The class failed to initialise, became permanently unloadable, and — because
the `Game` entity references it — Hibernate could not build the persistence unit. **One
line produced every integration failure in the suite.** Fixed by the project owner by
declaring the constants first.

**2. Three compile failures from scripted edits.** Missing `@Min`/`@Max` imports (the
replacement was anchored on a line that lives in a different file, so it silently did
nothing), two stacked javadoc blocks, an unreferenced try-with-resources variable, and
`assertThat((List<?>) x).contains(...)`. Common cause: **a string replacement that finds no
match is a no-op that reports success.** The edit helper now aborts if an anchor is not
found exactly once.

**3. Two bugs that were rules already written in this repository.**

- **Write-then-throw.** On flag-fall, `submitMove` finalised the game and then threw
  `OUT_OF_TIME`. The transaction rolled back and took the finalisation with it — the game
  stayed ACTIVE forever. That is the refresh-token bug from ADR-013, documented in
  TROUBLESHOOTING as a general rule.
- **Self-invocation.** The sweeper called its own `@Transactional` method via `this`,
  bypassing Spring's proxy. There was no transaction; `flagOnTime` mutated a detached entity
  that was never saved. That rule is in `TokenFamilyRevoker`'s javadoc.

**The production sweeper never finalised a single game.** It claimed rows and persisted
nothing, and a green build concealed it — the tests only caught it because they assert on
the reloaded database state, not on return values. `finaliseExpiredBatch()` returned `1`
throughout; the game was simply never written.

Both paths now live in `GameTimeouts`, reached only through other beans, which makes the two
bugs structurally impossible rather than something to remember.

**The detail worth keeping: the two paths need opposite transaction shapes.** The move path
uses `REQUIRES_NEW` so the timeout survives the caller's exception — safe because the move
pipeline holds no row lock. The sweeper must *not*: it claims rows with
`FOR UPDATE SKIP LOCKED`, and an inner `REQUIRES_NEW` transaction updating the same row would
wait for a lock held by its own caller, which is waiting for the inner call to return.
PostgreSQL cannot detect that as a deadlock — one side is waiting in application code, not on
a lock — so it hangs until the lock timeout.

**A consequence of fixing it.** Once the sweeper worked, it raced the tests: the background
job could finalise a game between `expireClock()` and the assertion. The broken sweeper had
never raced anything, because it did nothing. `ClockIntegrationTest` now disables the
scheduler and drives sweeps directly.

**Lesson, stated honestly.** Writing a rule down clearly is not the same as applying it.
Both rules were documented, in plain English, in files I wrote — and I wrote both bugs again
in the same milestone. The protection that actually worked was structural (one bean, reached
through the proxy) and a test style (assert on persisted state). Documentation alone did not.

**Also: my workspace was reset mid-milestone.** The repository existed only on the project
owner's machine until it was re-uploaded, which is exactly the case `PROJECT_STATE.md` §0
anticipates. Four documentation updates had been written into the lost workspace and never
delivered; they are restored in this entry and its siblings.

---

## 2026-09-14 — Milestone 3.1: the server-authoritative clock

Phase 2 closed after a full game was played by hand through the UI. Phase 3 begins with the
piece the whole architecture was arranged around.

**The clock does not tick.** No timer, no scheduled decrement, no in-memory counter.
Remaining time is computed from three persisted values and the current time, every read.
The design is ADR-006 and it was written in Phase 0; implementing it changed nothing about
it, which is the first time that has happened in this project.

**The payoff arrived exactly where predicted.** Reconnecting to a different instance
mid-game required *no code*. There is nothing to restore because nothing was ever held.
Everything that made Phases 1 and 2 more work — stateless handlers, no game state in
memory, PostgreSQL as the only source of truth — is what made this milestone small.

**Decisions**

- **Time comes from PostgreSQL, not the JVM.** Two hosts disagree by tens of milliseconds
  under NTP; measuring successive moves of one game against different clocks accumulates
  error and can go negative. `now()` rather than `clock_timestamp()`, because `now()` is
  transaction start time — the player is charged until the server *began* processing, not
  for our own database writes.
- **`turn_deadline` is stored, not computed.** A derived expression cannot be usefully
  indexed, so the sweeper would be a full scan every second. Stored plus a partial index
  makes it O(expired) instead of O(all games ever).
- **The clock is checked before legality.** A player whose time is gone does not get to
  play a legal move: the game ended when their time did, and only nobody looking kept it
  ACTIVE.
- **`FOR UPDATE SKIP LOCKED` in the sweeper.** Replicas take disjoint batches with no
  leader election and no distributed lock — ADR-005's argument applied to background work.
- **The sweeper never propagates exceptions.** An exception out of a `@Scheduled` method
  with `fixedDelay` cancels the schedule for the life of the process, so a transient
  database blip would silently stop all timeout handling until a restart.
- **`fixedDelay`, not `fixedRate`.** Fixed rate schedules from the previous *start*, so a
  slow sweep overlaps itself and the backlog compounds.
- **No column defaults in the migration.** A default would let a bug that forgets to set
  the clock produce a silently playable game rather than failing.
- **Exactly zero is a flag.** The off-by-one that would decide a real game and be
  impossible to argue about afterwards, so it has its own test.

**On testing something time-dependent.** Nothing in this milestone sleeps. The arithmetic
is a pure function taking instants as arguments, so 14 unit tests cover flag-fall at the
boundary, increments, and clock regression with no database at all. The database tests push
`turn_deadline` into the past with SQL. A suite that waits for real time is a suite nobody
runs — and making the clock stateless is what made that avoidable.

---

## 2026-09-14 — Milestone 2.3: React client. Phase 2 complete.

Deliberately thin, per the roadmap's 6-hour frontend cap. Came in around 4.

**Chose not to use `react-chessboard`.** Its v5 API moved to a single `options` prop with
`onPieceDrop` taking an object, which most search results still show in the old positional
form — I could confirm the shape but not every option name. Given how many rounds this
project has lost to half-verified APIs, betting the UI on one was not worth it.

The better justification is that it was not needed. A board is an 8×8 grid and a FEN
parser, about eighty lines. ADR-002's principle is about not reimplementing a *rules
engine* — genuinely hard, wrong in subtle ways — and a grid of divs is not that. Swapping
a library in later is a single component change.

**The client has no chess rules at all.** `Board.tsx` cannot distinguish a legal move from
an illegal one; every square it accepts comes from the server's `legalMoves`. Promotion is
detected without rules knowledge: the plain move is absent from the list while the
five-character forms are present. This makes "the client is never authoritative"
structural rather than a convention.

**Decisions worth keeping**

- **Backoff resets on `AUTH_OK`, not on socket open.** A server that accepts TCP
  connections but rejects every token would otherwise look healthy and be hammered at full
  rate indefinitely.
- **Full jitter on reconnect.** A restart drops every connection at the same instant;
  without randomisation clients retry in lockstep and the storm can prevent the server
  coming back at all.
- **`AUTH_FAILED` is not retryable** — closes rather than backs off, or it is an infinite
  loop with a dead token.
- **Unknown message types are ignored**, so the server can add types without breaking
  deployed clients. Unknown protocol *versions* are not ignored — those are reported.
- **Access token in a module variable, never `localStorage`.** Web storage is readable by
  any script on the page. It also does not survive a reload, which forces a refresh through
  the httpOnly cookie the browser will not hand to script.
- **Connection state always visible.** A user who cannot see the socket is live assumes a
  quiet board means a broken app and reloads — the worst response in a real-time app,
  since it drops the connection.

## 2026-09-14 — Two bugs found by actually playing a game

**Black could not move.** `useGame` cleared `legalMoves` on every `MOVE_MADE`, with a
comment reasoning that the next legal moves were "the server's to supply". Nothing supplied
them: `MOVE_MADE` did not carry them and no new snapshot was sent. So after one move a
player's board had zero legal destinations and accepted no input at all.

The comment is the interesting part — it stated a correct principle (the client has no
rules engine, so it must not invent legality) and then implemented the opposite of what
the principle requires. **A justification is not a verification.** The fix is to carry
`legalMoves` on the event, which is a few hundred bytes per move and means a client is
never holding a board it cannot play on.

Both clients receive the same list even though only the side to move can use it, keeping
every subscriber's frame byte-identical. Two regression tests: a move event carries 20
legal replies, and a terminal move ends the game.

**Pieces rendered as tofu boxes.** The board used U+2654–2659 for white and U+265A–265F
for black. The reporting machine had the outline set and not the filled one — partial
coverage of a Unicode block is normal and cannot be feature-detected.

Now six glyphs instead of twelve, used for both colours and distinguished with `color` plus
`-webkit-text-stroke`, on a symbol-capable font stack. Halves the surface area, and a white
piece reads as genuinely white rather than hollow, which is easier to see on a light
square.

**Both were only findable by running it.** The integration suite plays complete games
through the service and the socket and passes — because it asserts on server state, which
was correct throughout. Neither bug existed on the server. A green backend suite says
nothing about whether a human can play a game.

---

**A reported "opponent offline" that was not a bug at all.** The Valkey keys showed two
*different* game ids with one player each: both users had clicked Challenge, creating two
separate games. Each was alone in their own, so the badge was literally correct.

Two process failures worth recording. I asked for the diagnostic output and then shipped a
fix in the same message rather than waiting for it — so the reference-count fix below,
while a genuine bug, was not the reported problem. And the lobby rendered games as
`as white · ply 0`, which cannot distinguish one game from another: the UI made the mistake
easy and then hid it.

Fixed by returning opponent usernames from the list endpoint — resolved through
`IdentityFacade.findAllById` in one query, which is precisely what that batch method was
built for — and by saying plainly on the lobby that challenging back creates a second game.

**Bug found by running it: presence flapped offline.** Reported as "opponent offline" with
both clients connected.

`PresenceTracker` stored one flag per (game, user) and wrote it from per-socket events.
React StrictMode mounts, unmounts and remounts effects in development, so every page load
performed subscribe → close → subscribe; the close is handled on a different thread and
when it landed last it marked a connected player offline.

**StrictMode did not cause this — it made a production race deterministic.** The same
sequence happens on every reconnect after a network blip. Worth keeping StrictMode on for
precisely that reason.

Fixed by storing a **set of session ids** per (game, user): online means non-empty, and only
the transition to or from empty is announced. A Redis set rather than a local counter,
because a per-instance count fixes the reconnect case and reintroduces the identical bug
across instances.

Generalisable: **state that several connections can assert is a reference count, not a
boolean.** The boolean version is correct until the first overlap, which is also the first
reconnect.

**Known gap, recorded not hidden:** the move list resets on reconnect. The snapshot carries
the position but not the log, and showing a stale list that contradicts the board would be
worse than showing none. `GET /api/games/{id}` returns the full log; wiring it in is a
small follow-up.

**Phase 2 complete** at roughly 17 hours against a 15–18 hour budget.

---

## 2026-09-14 — Milestone 2.2: cross-instance fanout and presence

**ADR-003 is now verified rather than argued.** `ValkeyFanoutIntegrationTest` starts a
second Spring context by hand — its own Tomcat, its own session registry, its own Valkey
subscriptions, sharing only the database and Valkey — connects one player to each instance,
and asserts a move on one reaches the other. Flipping `chess.realtime.fanout` back to
`local` fails that test while the rest of the suite still passes.

That is the whole point. The bug an in-JVM broker causes is silent: nothing throws, nothing
logs, and one player's board simply stops updating. A test is the only way to hold a claim
like that honest.

**Design decisions**

- **Subscribe per instance, not per socket.** One upstream subscription serves every local
  socket watching a game. Per-socket would make subscriptions scale with connections rather
  than with games in play.
- **Unsubscribing matters as much as subscribing.** Without it an instance accumulates a
  subscription for every game it has ever seen and keeps receiving traffic for games it has
  no sockets for — invisible in testing, obvious after a week of uptime.
- **The publisher receives its own message.** Redis delivers to every subscriber including
  the publishing instance, so the mover is served through the same path as the opponent
  rather than a local shortcut. One delivery path, one set of bugs, byte-identical frames.
- **Presence: explicit delete plus a TTL.** The delete covers ordinary disconnects; the TTL
  covers the case it cannot — the instance itself dying, which runs no cleanup. Accepted
  cost: up to 60 s where a player whose pod died still shows as online. Eventually correct,
  never blocking, wrong only in the direction of optimism.
- **Unknown presence reads as offline.** Failing toward "we don't know" is less misleading
  than asserting a connection that may not exist.
- **Fail-fast Redis timeouts (1 s / 500 ms).** With the defaults, a Valkey outage would
  stall every presence lookup for seconds and convert a degraded *feature* into a degraded
  *application*.

**Two breakages caught before shipping, both caused by adding presence**

1. The test client's `await()` failed on *any* unexpected frame. Presence announcements now
   interleave legitimately between a subscribe and a move, so every 2.1 test would have
   broken. Now it skips other types but still fails fast on `ERROR`/`AUTH_FAILED` — those
   are almost always the real problem, and "ILLEGAL_MOVE: e2e5" beats "timed out waiting
   for MOVE_MADE".
2. The realtime tests had no Valkey container, and subscribing now writes a presence key.
   Added one there; `IntegrationTestBase` still has none, because nothing it reaches
   touches Valkey and a container per run would buy nothing.

**The cross-instance test was silently testing nothing.** The second instance was
configured with `SpringApplicationBuilder.properties(...)`, which maps to
`setDefaultProperties` — the *lowest*-precedence source, below `application.yml`. So
`fanout: local` from the yaml won, and the "cross-instance" test ran two instances that
shared a database and nothing else.

The datasource and Redis settings applied correctly, because `application.yml` does not
define them. Only the one key the application already had a value for was ignored — which
is exactly the shape that makes this hard to spot.

Fixed by passing command-line arguments, the highest-precedence source, plus an assertion
in `@BeforeAll` that the override actually took. Without that assertion the test would go
green the day someone changes how the instance is configured, while proving nothing. **A
test that cannot fail for the reason it exists is worse than no test**, because it also
consumes the attention that would have gone to writing a real one.

**Also fixed: the presence assertion read the wrong frame.** A client receives its own
presence echo — pub/sub delivers to every subscriber including the publishing instance —
so white's first `PLAYER_PRESENCE` was about white. Filtering by user id is what a real
client does too, so the test now does the same.

**Wrong assertion, caught by the build.** `ValkeyFanoutConfig` declared its own
`RedisMessageListenerContainer` with a comment stating that Spring Boot does not
auto-configure one. It does. Two candidates, context refused to start. Class deleted; the
auto-configured container is used instead, which also hands us its lifecycle and shutdown
handling.

This is the second Boot fact I asserted confidently and wrongly in Phase 2 — the first was
`@ConditionalOnMissingBean` working on a component-scanned bean. Both were of the form "the
framework doesn't do X". The cheap check is the **condition evaluation report**
(`--debug`), which lists every auto-configuration that applied and every one that did not,
with reasons. Reasoning about what Boot provides is not a substitute for reading it.

**Ordering detail worth keeping.** Disconnect announces presence *before* unsubscribing
upstream. Reversed, the instance publishes to a channel it has just stopped listening to —
and the opponent, who may be on the other instance, never hears that their opponent left.

---

## 2026-09-14 — Milestone 2.1: WebSocket protocol and handler

Phase 2 split so the fanout mechanism is a seam rather than an assumption: 2.1 builds the
protocol against a `GameEventPublisher` port with an in-JVM implementation; 2.2 swaps in
Valkey. That makes ADR-003's central claim — that an in-JVM broker breaks across instances
— demonstrable instead of merely argued.

**Verified before writing.** Boot 4 auto-configures `tools.jackson.databind.json.JsonMapper`;
`JacksonAutoConfiguration` is `@ConditionalOnClass(JsonMapper.class)`. A trap worth
recording: `JsonMapper` extends `ObjectMapper` and the auto-configuration backs off only
for a `JsonMapper` bean, so declaring `@Bean ObjectMapper` leaves both in the context with
the auto-configured one primary and the customisation silently ignored. Annotations stayed
on `com.fasterxml.jackson.core`; only the engine moved.

**Design decisions**

- **`seq` dropped from the envelope.** ARCHITECTURE specified it. TCP already orders frames
  within a connection, so `seq` only helps detect a *gap* — which `ply` does, monotonically
  and meaningfully. Keeping it would put a distributed counter on the hot path in 2.2 to
  duplicate what the payload carries. Revision recorded in ARCHITECTURE rather than made
  quietly.
- **`AFTER_COMMIT`, not `@EventListener`.** Broadcasting inside the transaction means a
  rollback leaves every client showing a move that never happened — and because the clients
  agree with each other, nothing looks wrong until a reload. The rollback path is the
  optimistic-lock conflict, i.e. exactly the case the system is built for. The converse is
  accepted: commit-then-fail-to-broadcast leaves a stale board that reconnection repairs.
  **Committed-but-not-broadcast is recoverable; broadcast-but-not-committed is not.**
- **The mover gets no private reply.** It learns the outcome from the same `MOVE_MADE`
  broadcast as its opponent, so both observe identical state by construction rather than
  the mover trusting a reply the opponent may never have received.
- **One pipeline, two transports.** WebSocket MOVE calls the same `GameService.submitMove`
  as REST. A second validation path would be a second place for the rules to drift.

**Bug caught while writing, worth keeping.** The first `WebSocketHandlerDecorator` wrapped
the session only in `afterConnectionEstablished`. The container hands every *later* callback
the original session, so the handler's own sends would have bypassed the concurrency
decorator entirely — half the writes protected and half not, which is worse than none
because it looks correct. Fixed by remembering the wrapper for the life of the connection.
Interleaved writes produce a corrupt frame that surfaces on the client as an unparseable
message, under load, intermittently, and never in a test.

**`@ConditionalOnMissingBean` on a `@Component` produced no bean at all.** That annotation
is only reliable inside auto-configuration classes — Spring Boot's docs say so — because on
a component-scanned bean the condition is evaluated during scanning in an order undefined
relative to other user beans. Replaced with `@ConditionalOnProperty` on
`chess.realtime.fanout` (`local` | `valkey`).

The better framing is that the original was the wrong design, not just the wrong
annotation. "Use this unless something else is present" makes deployment topology an
emergent property of the classpath; a named property makes it a decision an operator takes
and can inspect. Silently selecting in-JVM fanout behind a load balancer would
desynchronise games with no configuration to point at.

**Also fixed:** allowed origins were hardcoded in Java. They differ per environment, so a
rebuild to deploy would have been required. Now configuration.

---

## 2026-09-14 — Milestone 1.3b: game lifecycle and the move pipeline

Completes Phase 1. A game can now be created, played to checkmate or resignation, and
persisted — with the concurrency guarantees the project was designed around.

**The checklist paid for itself before a line was written.** Auditing V1 against the type
table in ARCHITECTURE.md 4.2.1 found `side_to_move CHAR(1)` — the identical bpchar trap
that cost a round trip on `token_hash`. Caught pre-emptively, fixed forward in V3 rather
than by editing an applied migration. That is the first time a process artifact in this
repository prevented a failure instead of explaining one after the fact.

**The move pipeline.** Ordering is deliberate: idempotency lookup, load, authorise, active,
turn, stale ply, legality, write. Cheapest and most specific first, so a request that was
always going to be refused never reaches the rules engine or the database write.

Two exception paths handled explicitly rather than allowed to become 500s:

- `OptimisticLockingFailureException` — another transaction advanced the game between our
  read and our write. ADR-005 prescribes re-read and re-validate rather than blind retry,
  and re-validation would reject the move anyway because the turn has flipped. So the
  honest response is "resync and decide", not a silent retry.
- `DataIntegrityViolationException` — the idempotency key or the composite primary key
  fired. **We cannot read the original record here**: PostgreSQL has aborted the
  transaction, so any query in it fails. The client retries and the pre-check serves it.
  One extra round trip on a rare path, chosen over opening a second transaction.

**Detail worth keeping: who wins a checkmate.** The mover is whoever was to move *before*
the move — the opponent of whoever is to move now. Getting it backwards awards the game to
the player who was mated, and no compiler catches it and no symmetric test notices, because
the two sides are identical in every other respect. There is now an explicit assertion.

**`saveAndFlush`, not `save`** — the same reasoning as `UserRegistrar`. `save` defers the
write to commit, where both exceptions above escape the method and surface as opaque 500s.

**The flagship test.** Sixteen threads submit a move for the same ply, released together,
each with a distinct idempotency key so none is a retry. Exactly one may commit. The losers
fail in whichever of three ways the race resolves — wrong turn, stale ply, optimistic lock —
and which one is non-deterministic, so the assertion is about the invariant rather than the
mechanism. Remove the `@Version` column and this test produces duplicate moves and a
corrupted ply, which is what makes it evidence rather than decoration.

**Phase 1 is complete**, at roughly 14 of a 16–20 hour budget.

---

## 2026-09-14 — Milestone 1.3a: chess rules port

Milestone 1.2 verified green. 1.3 split in two: the rules port first, alone.

**Why split.** chesslib was the largest unverified API surface in the project — the one
dependency never seen to compile — but it is also pure Java with no Spring and no
database. Isolating it means an API mismatch surfaces in a seconds-long unit run rather
than buried inside a fifteen-file milestone with a database and a servlet stack in the
way. Sequence the risky, cheaply-testable part first.

The API was verified against the project's own README before writing anything:
`loadFromFen`, `legalMoves`, `doMove`, `undoMove`, `isMated`, `isStaleMate`, `isDraw`,
`isInsufficientMaterial`, `getSideToMove`, `getFen`, `new Move(uci, side)`.

**Decisions**

- **Perft is the verification, not trust.** 17 published node counts across the five
  standard positions. A single missing or extra move at any depth changes the total, so
  this catches a wrong castling right or en passant rule — bugs that otherwise produce
  games that look normal and are invalid. It is also what would catch a bad library
  upgrade.
- **A `Board` per call, never cached.** The adapter constructs, uses, discards. This is
  the constraint ADR-002's wrapper exists to enforce in one place, and there is now a
  16-thread test that fails if someone later "optimises" it into a field.
- **SAN is computed before the move is applied**, since notation depends on what else
  could have reached the square and on whether the move gives check.
- **Explicit promotion piece, no queen default.** Underpromotion to a knight is a real
  tactic; defaulting would be silently wrong occasionally, which is worse than loudly
  wrong.
- **`DomainException.Rejected` → 422, not 400.** 400 means "I could not parse that"; an
  illegal move is syntactically impeccable and semantically refused. The client's correct
  response is to resync its board, not to change its serialisation.
- **Draw reasons distinguished**, though chesslib collapses them into one `isDraw()`
  boolean. A game history that records only "draw" is a worse artifact.

**Known gap, recorded rather than half-implemented: threefold repetition.** A position
loaded from FEN has no history, so repetition cannot be detected. This is a direct
consequence of the stateless design, not an oversight. The fix is cheap and keeps
statelessness — every move's `fen_after` is already persisted, so repetition is a count
query over `moves` keyed on the position fields of the FEN. Deferred to Phase 3 and
listed in the technical-debt register.

---

## 2026-09-14 — Reuse detection was rolling back its own revocation

First genuine logic bug of the project, and a good one.

`RefreshTokenService.rotate` is `@Transactional`. On detecting reuse it revoked the token
family and then threw `DomainException.Unauthorized` to produce the 401. Spring rolls back
on `RuntimeException`, so **the revocation was discarded by the exception that signalled
it**. The victim's replay was correctly rejected, so the response looked right — while the
attacker's token kept working indefinitely. Family revocation defeated itself.

**Caught only because the test asserts the attacker's token dies too**, not merely that the
victim's replay 401s. A test that checked the obvious half would have passed and the
vulnerability would have shipped. Worth remembering when writing security tests: assert
the property you actually care about, which is usually about the *attacker*, not the
victim.

**Fix:** `TokenFamilyRevoker` with `REQUIRES_NEW` in a separate bean. Two non-obvious
details — a separate bean because self-invocation bypasses the transaction proxy, and a
public method because CGLIB cannot proxy non-public ones and Spring ignores
`@Transactional` there without complaint. Both failure modes would have silently
reintroduced the original bug.

**Rejected `noRollbackFor`:** `rotate` joins the outer transaction from
`AuthenticationService.refresh`, so the outer boundary's rules govern the commit.
Correct suppression would need annotating every layer, and the guarantee would vanish the
first time someone wrapped the call in another `@Transactional` method.

**Generalisable rule, now in TROUBLESHOOTING:** *write-then-throw inside a transaction is
a bug unless the write has its own transaction.* Same trap for audit logs, security
events, and failed-login counters.

---

## 2026-09-14 — Jackson 2 type injected under Boot 4; added a pre-delivery checklist

`SecurityConfig` and `AuthApiIntegrationTest` both asked for
`com.fasterxml.jackson.databind.ObjectMapper`. Boot 4 defaults to **Jackson 3**
(`tools.jackson.databind.JsonMapper`), so there is no such bean. Compiles cleanly, fails
at startup.

**This one was not an environment limitation.** The fact was recorded in ADR-011 — *"Jackson
3 is the default in Boot 4"* — and again in this log when Security 7 was checked
(`SecurityJackson2Modules` -> `SecurityJacksonModules`). I wrote it down and then did not
consult it. The project accumulated hard-won facts in ADRs and this log, and I was not
reading my own notes before writing code.

**Fix:** removed Jackson from both. `SecurityConfig` now writes its two fixed RFC 7807
bodies as strings — four fields, all from constants it controls, no user input, nothing to
escape. `ApiExceptionHandler` still returns `ProblemDetail` and lets Spring's message
converters serialise it, which is the distinction worth drawing: **depend on the
framework's abstraction, not on the library behind it.** The test uses
`com.jayway.jsonpath.JsonPath`, already on the classpath.

**Process change — `docs/BOOT4_CHECKLIST.md`.** Every Boot 4 trap this project has hit,
plus four grep commands that would have caught this in seconds. To be run before shipping
framework-touching code. Promising more care is worthless; a checklist and a grep are not.

An audit of every framework import in Milestone 1.2 found Jackson 2 in exactly three
places and nothing else — the compile had already proven the rest resolve. That audit is
one command and should have preceded delivery.

---

## 2026-09-14 — `@Positive` does not apply to `Duration`

`AuthProperties` used `@Positive` on two `Duration` fields. Compiles fine; fails at
startup with `HV000030: No validator could be found`. Bean Validation's comparison
constraints only cover numeric types.

Moved both checks into the record's compact constructor, alongside the existing secret
length check — one place, and an error message that names the offending property.

Fixed a second, quieter bug while there: the secret length check used
`getBytes()` with the platform default charset while `JwtService` derives the key with
UTF-8. On a non-UTF-8 JVM the check would have disagreed with the actual key length — a
validation passing while the thing it validates is wrong. Now explicit UTF-8 on both
sides.

**Worth keeping:** a validation annotation that compiles is not one that applies.
Constraints are matched to types at runtime.

---

## 2026-09-14 — Second schema-validation mismatch; added a type-mapping reference

`token_hash` was `CHAR(64)` in V2 and `String length = 64` in the entity. PostgreSQL
stores `char(n)` as blank-padded `bpchar`; Hibernate expects `varchar`. Fixed to
`VARCHAR(64)`, which was the correct type anyway — `CHAR(n)` has no storage or speed
advantage in PostgreSQL and the padding leaks into comparisons. Fixed-width types are a
habit from databases where the width means something.

**Systemic, not incidental.** This is the second entity/migration disagreement in two
milestones. It is precisely the class of defect I cannot catch: I can neither compile nor
run here, and the two sides are written in different files in different languages.
`ddl-auto: validate` catches it at startup — the design working as intended — but each
instance costs a round trip.

Mitigation: a type-mapping table in **ARCHITECTURE.md §4.2.1**, to be used when writing
both sides. Phase 1.3 adds `Game` and `Move` with far more columns than `RefreshToken`,
so the payoff is immediate.

**Blast radius worth remembering:** one entity's mismatch fails the entire persistence
unit, so a brand-new entity broke every pre-existing identity test. 25 failures, one
wrong word, and none of the failures named the new code.

---

## 2026-09-14 — Milestone 1.2: authentication over HTTP

Delivered as a git patch rather than an archive — first use of the new handoff workflow.

**Decisions worth defending**

- **Reuse detection is the point of rotation** (ADR-013). Rotation alone only shortens
  the window; it is what makes theft *detectable*, because a used token reappearing
  cannot happen legitimately. Revoking the whole family is what makes detection useful —
  otherwise the attacker keeps the token they just minted.
- **Conditional UPDATE for the rotation claim.** `WHERE used_at IS NULL` rather than
  `if (!token.isUsed())`. Third instance of the same principle in this codebase, after
  `uq_users_username` and `PRIMARY KEY (game_id, ply)`: enforce the invariant where
  writes are serialised.
- **SHA-256 for tokens, bcrypt for passwords.** bcrypt's cost defends low-entropy
  secrets; a 256-bit CSPRNG token has no dictionary. bcrypt here would be ~250ms of CPU
  per refresh — a DoS lever, not a security gain.
- **Custom JWT filter over the OAuth2 resource-server DSL.** Phase 2 needs
  `JwtService.verify` callable with no servlet filter chain (WebSocket first-message
  auth), so the service exists regardless; a 30-line filter over it beats two auth paths.
  Crypto is still Nimbus — never hand-rolled.
- **Access token in memory, refresh token in an httpOnly SameSite=Strict cookie.**
  Different threats, different storage: XSS cannot read the refresh token, CSRF cannot
  use it, and the token XSS *could* steal expires in 15 minutes.
- **`/users/me`, not `/users/{id}`.** Identity comes from the token, so IDOR is
  unrepresentable rather than merely guarded against.

**Accepted cost, recorded so it is not later mistaken for a bug:** a genuine double-click
on refresh logs the user out, because the second request is indistinguishable from reuse.
A grace window would fix it and would also be a hole an attacker can aim at.

**Unverified:** none of this has been compiled. The one new dependency is
`spring-security-oauth2-jose`; if it fails to resolve, that is the first thing to check.

---

## 2026-09-14 — Milestone 1.1 complete; starter coordinates resolved properly

**Milestone 1.1 is green.** All identity integration tests pass, and Flyway is confirmed
applying V1 against both the Testcontainers database and the Compose database.

Incidentally, the empty Compose database was a third independent confirmation that Flyway
had genuinely never run — the integration suite uses its own throwaway container, so a
green suite says nothing about the state of the local dev database. Worth remembering as
a general point: **test isolation means test success is not environment verification.**

**Starter coordinates settled from the source, not by diffing.** Rather than delegating
the `start.spring.io` check, the Boot 4.0 migration guide was read directly. Findings:

| Was | Now | Why it mattered |
|---|---|---|
| `org.flywaydb:flyway-core` | `spring-boot-starter-flyway` | raw coordinate carries no auto-config — silent no-op |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | old name is a deprecated alias, so it compiled and gave no signal |
| — | `spring-boot-starter-webmvc-test` | `@WebMvcTest` / `@AutoConfigureMockMvc` moved to `o.s.boot.webmvc.test.autoconfigure` |
| `org.springframework.security:spring-security-test` | `spring-boot-starter-security-test` | `@WithMockUser` / `@WithUserDetails` need it to function |

The last two would have broken Milestone 1.2 on its first test, and — like Flyway —
would have failed in a way that blamed the test code rather than the dependency.

**The rule that generalises.** Boot 4 modularisation creates two distinct traps, and
neither produces a compile error:

1. A raw third-party coordinate resolves and compiles but ships no auto-configuration.
2. A renamed starter still resolves as a deprecated alias.

In both cases the build is green and the behaviour is absent. **A coordinate that
resolves is not a coordinate that works** — the only reliable check is the vendor's own
starter list.

**Still open:** whether the `Instant`/`TIMESTAMPTZ` fix needed the `@JdbcTypeCode`
annotation or whether the global `preferred_instant_jdbc_type` property was sufficient.
Untested either way; two-minute experiment described in PROJECT_STATE next-tasks.

---

## 2026-09-14 — Root cause: Flyway was never auto-configured

**The actual error**, once `testLogging.exceptionFormat = FULL` made it visible:

```
org.hibernate.tool.schema.spi.SchemaManagementException: Schema validation: missing table [users]
```

Not a column type mismatch. The table did not exist.

**Cause.** Spring Boot 4 modularised auto-configuration into per-technology jars.
`FlywayAutoConfiguration` now ships in `spring-boot-flyway`, published via
`spring-boot-starter-flyway`. The build declared raw `org.flywaydb:flyway-core`, which
puts Flyway on the classpath with no auto-configuration behind it. The application starts
cleanly, accepts every `spring.flyway.*` property, and migrates nothing.

**Flyway had therefore never run — including during Phase 0.** `/actuator/health` was
green because the `db` indicator validates a connection, and an empty database has a
perfectly good connection. Phase 0 step 9 (`\dt` should list users/games/moves) was the
check that would have caught it.

**Fix:** `org.springframework.boot:spring-boot-starter-flyway` + `flyway-database-postgresql`.

**Three lessons, in descending order of value**

1. **An absent component is more dangerous than a broken one.** A broken migration tool
   fails loudly at startup. A missing one hands you an empty database and a green health
   check, and the failure surfaces phases later, somewhere else, blaming something else.
   Anything whose absence is indistinguishable from success needs an explicit assertion —
   hence the new `SchemaMigrationIntegrationTest`.
2. **A risk written down but not gated on is not managed.** `PROJECT_STATE.md` §4 has said
   since Phase 0: *"Starter coordinates may have changed. Boot 4 modularised the codebase.
   Generate a project at start.spring.io and diff the build file. That tool is ground
   truth; this repo is not."* That verification was Phase 0 step 5. It was not confirmed
   done, and I proceeded to Milestone 1.1 anyway. The risk register was accurate and
   useless. **It is now a hard gate in ROADMAP Phase 0's definition of done.**
3. **The condition evaluation report is the tool for "why didn't my auto-configuration
   apply".** `--debug` prints which conditions matched. Absent auto-configuration does not
   appear even in negative matches, which is itself the diagnosis.

**Still unverified:** the `Instant`/`TIMESTAMPTZ` fix from the previous round. Validation
never reached column types because the table was missing, so both the
`preferred_instant_jdbc_type` property and the `@JdbcTypeCode` annotation are untested.
Once the suite is green, remove the annotation and re-run — if it still passes, the global
property works and the annotation was redundant.

---

## 2026-09-14 — Schema validation failure on the first entity

**Symptom:** all 12 identity integration tests failed. Eleven of them were noise —
Spring caches a failed application context and replays the failure, so only the first
stack trace was real.

**Cause:** `ddl-auto: validate` rejected `User`. Hibernate maps `java.time.Instant` to
plain `TIMESTAMP` by default; `users.created_at` is `TIMESTAMPTZ`. Mismatch, refuse to
start.

**Fix:** `hibernate.type.preferred_instant_jdbc_type: TIMESTAMP_UTC` — global, so it
covers `games.last_move_at`, `finished_at` and every future timestamp. The alternatives
were worse: changing columns to `TIMESTAMP` discards the offset and makes timestamps
depend on server zone, which is unacceptable in a system whose clock decides game
outcomes; `@JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)` per field works but has to be
remembered every time, and forgetting once is a startup failure.

**The lesson worth keeping.** This setting was in `application.yml` throughout Phase 0
and Phase 0 passed — because there were no entities, so `validate` had nothing to
validate. **A check that passes over an empty subject set has told you nothing.** The
same trap applies to the ArchUnit rules (`allowEmptyShould(true)` until Phase 1 gave them
classes to inspect) and to `-Werror`, which only started failing once real code existed.
Phase 0's green build was weaker evidence than it looked.

**Also worth keeping:** when many tests fail at once with
`DefaultCacheAwareContextLoaderDelegate`, that is one bug wearing N costumes. Read the
first trace; the rest are cache replays.

**Follow-up — the build was configured to hide the answer.** The first fix attempt did
not resolve it, and the diagnostic `grep` returned nothing, because Gradle's default
`testLogging.exceptionFormat` is `SHORT`: it prints the exception class and line number
and discards the message. Hibernate's validation errors name the exact column and both
types, so the message was the entire diagnosis and it was being thrown away by my own
build config. Set `exceptionFormat = FULL` and `showCauses = true`.

Generalisable and worth more than the bug itself: **when a second diagnostic attempt
produces no new information, stop hypothesising and fix the observability.** Two rounds
of guessing cost more than the one-line logging change would have.

---

## 2026-09-14 — Handoff workflow changed; wrapper added to the repository

**Problem being solved.** Producing an archive after every milestone meant downloading,
extracting and replacing the tree constantly, and it twice caused work to be done against
a stale copy — once because three different archives shared the filename
`chess-platform-phase0.tar.gz`, and once because two different files named
`build.gradle.kts` collided in a flat output directory. Both times the file *contents*
were correct and the *delivery* made them useless.

**New workflow** (recorded in `PROJECT_STATE.md` §0): changes are delivered in chat and
applied in place. An archive is produced only at a major phase boundary, on explicit
request, or when technically necessary. Milestone size is unchanged — fewer handoffs
means larger coherent units, not shallower ones.

**Wrapper.** `gradlew` and `gradle/wrapper/gradle-wrapper.properties` (pinned 9.7.1) are
now committed. `gradle-wrapper.jar` still is not, and deliberately: it is a binary that
can only come from a real Gradle distribution, and handing over an executable of
unverified provenance would contradict the wrapper-checksum validation added to CI for
exactly that reason. `./gradlew wrapper` generates it once, locally, after which it lives
in git history and never travels again. Same for `gradlew.bat`.

**Standing recommendation: initialise git.** Every delivery failure in this project so far
is one `git status` away from being a non-event.

---

## 2026-09-06 — Phase 0 verified complete; Phase 1 Milestone 1.1 (identity domain)

**Phase 0 closed.** Compile, compose stack, health check, Flyway V1, integration tests
and CI all green on the dev machine. Estimated ~5 hours against a 3–5 hour budget.

**Milestone 1.1 built:** `common/error`, `common/id/Uuid7`, `identity` domain +
persistence + registration + authentication, `IdentityFacade`, password and clock config,
and tests. No HTTP layer — that is 1.2. Building the domain first meant the registration
race got solved in the domain rather than papered over in a controller.

**Spring Security 7 surface verified before writing any of it.** The hard removals are
`.and()` chaining, `authorizeRequests`, `AntPathRequestMatcher`/`MvcRequestMatcher`, and
`AccessDecisionManager` (moved to a separate module). The lambda DSL and
`authorizeHttpRequests` survive. Also relevant for 1.2: `SecurityJackson2Modules` is
replaced by `SecurityJacksonModules` on a Jackson 3 `JsonMapper.Builder`.

**Design decisions worth remembering**

- **The registration pre-check is not a correctness mechanism.** Two threads can both
  read "username free" before either writes. Only `uq_users_username` serialises them.
  The pre-check exists solely to produce a precise error in the common case; the
  constraint violation is the real guard. Same shape as move idempotency (ADR-005), and
  now proven by a 16-thread race test asserting exactly one winner.
- **`saveAndFlush`, not `save`.** `save` defers the INSERT to commit, which throws the
  constraint violation outside the try block and surfaces as a 500 instead of a 409.
- **Timing-attack defence in authentication.** Identical messages for "no such user" and
  "wrong password" close the enumeration oracle in the response body but not in the
  clock: returning early when no user exists is ~100 ms faster than hashing. So we hash
  against a dummy hash regardless. Deliberately no short-circuit boolean.
- **`Locale.ROOT` on `toLowerCase`.** Under a Turkish locale `"I".toLowerCase()` is a
  dotless `"ı"`, so a user registering as `IVAN` on a Turkish-locale server becomes
  unfindable everywhere else.
- **UUIDv7 hand-written.** ~25 lines of exactly-specified bit layout with its own tests
  is a smaller risk than another unverifiable coordinate, and the monotonicity behaviour
  is something we wanted to control rather than inherit. Clock regression holds the
  previous timestamp rather than emitting a smaller one.

**Two defects in my own Phase 0 scaffolding, found by using it**

1. The `entitiesDoNotLeak` ArchUnit rule forbade any class outside `..domain..` or
   `..internal..` from touching an entity — which rejects a module's own facade mapping
   its own entity to a DTO, the exact pattern the rule was written to encourage.
   Rescoped to forbid entities crossing *module* boundaries. **An architecture rule that
   fires on correct code gets suppressed within a week and then guards nothing.**
2. `-Xlint:all -Werror` fails on every exception class, because `RuntimeException` is
   `Serializable` and lacks a `serialVersionUID`. Excluded `-serial` and `-processing`
   rather than adding four lines of version UID ceremony to classes that are never
   serialised.

Both were only findable by writing real code against the scaffolding. Worth noting as a
pattern: **Phase 0 deliverables are hypotheses until Phase 1 exercises them.**

**Next:** Milestone 1.2 — Security filter chain, JWT issuance, rotating refresh tokens
(needs a `V2__refresh_tokens.sql` migration), auth endpoints, RFC 7807 problem details.

---

## 2026-09-06 (later) — chesslib resolution failure; toolchain gap found

**Symptom**

`./gradlew :backend:test` failed with `Could not find com.github.bhlangonijr:chesslib:1.3.4`.
Everything else on the compile classpath resolved.

**Root cause — two problems in one error**

The version was wrong (1.3.4 was invented; current is **1.3.7**), but that was not the
cause. **chesslib is not published to Maven Central under this coordinate at all.** It is
distributed via JitPack, per the project's own README. No version would have resolved
from `mavenCentral()`.

The tell was in the coordinate the whole time. `com.github.<github-user>` is JitPack's
naming convention — it is derived from the GitHub repository path, not a reverse-domain
namespace the author proved they control. A groupId of that shape should trigger a
repository check before a version check.

Worse: `backend/build.gradle.kts` carried a comment asserting *"chesslib publishes to
Maven Central."* A confidently wrong comment is more expensive than no comment, because
it redirects the next person away from the actual cause.

**Fixed**

- chesslib → 1.3.7 (licence also corrected: Apache 2.0, not MIT as previously recorded).
- JitPack added as `exclusiveContent` scoped to `com.github.bhlangonijr`. **ADR-012**
  records why the scoping is the decision and adding the repository is just the
  mechanism: an unfiltered JitPack entry retries every dependency miss against it, which
  is a dependency-confusion vector, since a coordinate there is claimable by whoever owns
  the matching GitHub repo name.

**Second defect, found while investigating**

`./gradlew javaToolchains` reported only JDK 21. `settings.gradle.kts` had **no toolchain
resolver**, so the Java 25 toolchain declaration was a requirement nothing could satisfy:
the build would have failed with `No matching toolchains found` the moment chesslib was
fixed. Added `foojay-resolver-convention` 1.0.0 (a settings plugin, not a project plugin;
versions before 1.0.0 cap out at Gradle 8.14.x, so the 1.x line is required on 9.7.1).

**The ordering fact worth keeping**

Dependency resolution runs *before* compilation. A build that dies on a missing
dependency has proven nothing about its toolchain, its compiler flags, or its source. The
report that this run "got past the Java 25 toolchain issue" was optimistic — the
toolchain was never reached. Generally: **a failing build only tells you about the first
gate it hit.**

**Version errors so far: four** (Boot 3.5, Boot 4.0, Gradle unpinned, chesslib). Worth
being precise about the remedy, because the obvious one is wrong: **Renovate would not
have caught this.** Renovate updates coordinates that already resolve; it cannot fix a
coordinate pointing at the wrong repository. The actual root cause is that dependency
resolution cannot be exercised in the environment where these files are written — every
coordinate is unverified until a real build runs. That is a standing limitation, not a
lapse of care, and the mitigation is sequencing: **`./gradlew :backend:test` is the gate
that must pass before any further scaffolding is trusted.**

**Next:** step 4 of PROJECT_STATE §10 — first successful compile. Then the compose stack.

---

## 2026-09-06 (evening) — Gradle version pinned; two scaffolding defects fixed

**The defect**

The Phase 0 scaffolding shipped with **no Gradle version anywhere**. `libs.versions.toml`
had no entry and SETUP.md said `gradle wrapper --gradle-version <current>` with a literal
placeholder. Caught on export to the Arch machine, when the wrapper turned out not to
exist and there was nothing to tell you what version to generate.

This is the same failure mode as the Spring Boot version, twice over: a decision that
*looked* made because it was mentioned, but was never actually fixed to a value.

**Resolved: Gradle 9.7.1.** The intersection of four constraints — Java 25 as the daemon
JVM needs >= 9.1.0; Spring Boot 4.1's plugin accepts 8.14+ or 9.x; 9.7.0 has a Kotlin DSL
regression that Gradle itself says to skip; 9.3.0 carried repository-handling CVE fixes.
Full table in ADR-011.

**The trap worth remembering:** Java 25 binds harder than Spring Boot here. Reading only
the Spring docs suggests Gradle 8.14 is fine. On JDK 25 that fails with
`Unsupported class file major version 69` inside `_BuildScript_` — an error that blames
the build script and Groovy, not the JDK, so it sends you debugging the wrong thing.
Gradle 8 on Java 25 was requested upstream and closed as not planned.

**Made the pin enforceable rather than documentary**

Pinning a version in a file nobody checks is how this went wrong in the first place. So:
the root `wrapper` task reads the version from the catalog (no `--gradle-version` flag to
get wrong), and a `verifyGradleVersion` task fails CI if `gradle-wrapper.properties` has
drifted from it. The match uses a trailing hyphen (`gradle-9.7.1-`) so `9.7.11` cannot
satisfy a `9.7.1` pin — a prefix collision a naive `contains` would miss.

**Two defects found while fixing this**

1. `.gitignore` was still Maven-era: it ignored `target/` and `maven-wrapper.jar` and had
   no `build/` or `.gradle/`. The first build would have produced hundreds of untracked
   class files. Rewritten, with explicit negations so `gradle-wrapper.jar` is committed —
   a clone without it cannot build, and a global gitignore or stray `*.jar` rule would
   otherwise drop it silently, failing only on someone else's machine.
2. CI did not validate the wrapper jar. It is a committed binary that executes before any
   of our own code compiles, which makes it a supply-chain surface. Now checksummed
   against Gradle's published releases, before setup-gradle runs.

**Learned**

A version that is "recommended" in prose is not pinned. Three times now the same shape:
Boot 3.5 from memory, Boot 4.0 without checking the support window, Gradle not at all.
The fix is not more care — it is making the pin machine-checkable, which is what
`verifyGradleVersion` does and what the Boot version still lacks. **Open follow-up:
Renovate or Dependabot on the catalog would close the remaining gap.** Worth ~30 minutes
in Phase 6 alongside the rest of CI.

**Next:** bootstrap the wrapper on the Arch machine, verify the build boots, then Phase 1
Milestone 1 (`identity`).

---

## 2026-09-06 (later) — Phase 0: build tooling, framework version, scaffolding

**Done**
- Switched Maven → **Gradle** (Kotlin DSL, version catalog, Java toolchain).
- Corrected the Spring Boot target **again**, to 4.1.x.
- Wrote the scaffolding: build files, compose stack, Spring config, structured logging,
  `V1__baseline.sql`, application entry point, ArchUnit boundary test, CI workflow.

**The Boot 4 question, and why the framing was wrong**

The question asked was: *does Boot 4 improve learning/interview value enough to justify
migration friction?* The honest answer to that question is **no — essentially zero
improvement.** Nothing this project teaches touches version-specific framework surface.

But checking the support timeline before answering showed the question didn't apply.
**Spring Boot 3.5 reached OSS end-of-life on 30 June 2026, and 3.5 was the last of the
3.x line.** Every 3.x branch is now unsupported. And 4.0.x support ends December 2026 —
inside this project's own timeline — so even the version chosen yesterday was wrong.

So the real choice was never "new vs stable", it was "supported vs unsupported", and the
right target is 4.1.x. No amount of reasoning about migration friction would have
surfaced that. **Checking the support timeline should be step one in any framework
version decision, not a detail.**

The friction concern also turned out to be overstated, for an instructive reason. The
alarming migration estimates in circulation are for moving *large existing estates*
across Jakarta EE 11 and Jackson 3 — removed deprecations, custom auto-configuration
touching internal APIs. Greenfield pays almost none of that. What remains is that older
tutorials target 3.x and will be wrong, which is a documentation problem.

**Gradle over Maven**

Draft 1 of ADR-011 argued Maven on portfolio legibility — an interviewer can scan a
`pom.xml` quickly. Real, but small, and it loses to the owner's existing fluency. Sixteen
weeks of friction against your own build tool is a genuine cost with nothing on the other
side. The version catalog recovers most of the legibility argument anyway.

**Deliberate choices in the scaffolding worth remembering**

- `ddl-auto: validate`, never `update`. Flyway owns the schema in every environment. The
  app refuses to start if entities and migrations have drifted — which is the failure you
  want at boot, not during a query at 3am.
- Hikari pool at 10, not a bigger number. A pool larger than the database can serve turns
  connection starvation into database thrashing, which is harder to diagnose. `pods x
  pool_size` against RDS `max_connections` is the real ceiling. Measure in Phase 9.
- `test` and `integrationTest` split. A suite that takes four minutes stops being run
  locally, and a feedback loop nobody uses is worse than none.
- Valkey runs with persistence **off** locally. It holds nothing unrecoverable
  (ADR-004), and a cache surviving restarts would let us accidentally depend on that.
- `ck_games_result_consistency` in the schema: a finished game must have a result, an
  active one must not. Enforced in the database so no code path — including a future bug
  or a manual fix — can produce a half-finished game.
- The ArchUnit test was written **before** any module exists. Retrofitting boundary rules
  onto real code means starting with violations to grandfather in, and a rule with
  exceptions is not a rule.

**Not done — and this matters**

None of it has been built. The environment it was written in has no access to Maven
Central or the Gradle distribution server, so no dependency was resolved and nothing
compiled. The starter coordinates in particular are conventional but unverified against
Boot 4's modularised jar layout. `PROJECT_STATE.md` §4 lists the risks in order and
`start.spring.io` is the ground truth to check them against.

**Next:** verify the build, then Phase 1 Milestone 1 (`identity`).

---

## 2026-09-06 — Phase 0: specification review and architecture

**Done**
- Reviewed `MASTER_PROJECT_SPEC.md` in full.
- Wrote `ARCHITECTURE.md`, `ROADMAP.md`, `PROJECT_STATE.md`, ADRs 001–011.
- Defined the repository structure.

**Contradictions found in the specification**

1. *Observability is scheduled after the deadline that requires it.* The phase plan puts
   observability in Phase 9 (weeks 13–16), while the HARD PORTFOLIO DEADLINE requires it
   by week 12 — and a separate section says "design proper observability from early
   stages." **Resolution:** logging and metrics become cross-cutting from Phase 1;
   Phase 9 keeps tracing, dashboards, and load testing.

2. *"Game Service → SQS → Rating/Notification/Analytics workers" implies microservices*
   while the spec elsewhere mandates a modular monolith. **Resolution:** workers are the
   same artifact under a `worker` Spring profile, deployed separately. Independent
   scaling without a second codebase (ADR-001).

3. *Phase 7 (AWS) followed by Phase 8 (Kubernetes) implies deploying the system twice*,
   costing ~15 hours and ~$73/month for an EKS control plane, against a project budget
   under $50. **Resolution:** ECS Fargate for the production path, `kind` for Kubernetes,
   EKS for a 2–3 day proving window (ADR-010).

4. *The phase budgets sum correctly (128–180 h) but contain no learning time.* For an
   engineer new to Terraform and Kubernetes this understates Phases 7–8 substantially.
   **Resolution:** roadmap re-costed at 135–175 h with an explicit cut order.

**Decided**
- No distributed lock anywhere in the move path. The database provides the guarantee;
  Redlock would add a TTL-expiry failure mode without removing the need for the
  constraint (ADR-005).
- The clock is computed, not ticked, and timestamped by PostgreSQL rather than the
  application server — eliminating inter-pod clock skew (ADR-006).
- Raw WebSocket over STOMP, because Spring's simple broker does not fan out across
  instances and fixing that means adding RabbitMQ purely as a relay (ADR-003).
- Snapshot-on-reconnect rather than delta replay, which is what makes unguaranteed
  Pub/Sub acceptable (ADR-007).

**Corrected**
- ADR-011 was initially drafted as Java 21 + Spring Boot 3.5.x from memory. Checking the
  official sources showed Spring Boot 4.0 has since gone GA on Spring Framework 7 /
  Jakarta EE 11, with 4.1 in RC, and that Java 25 is now an LTS with first-class Boot 4
  support. ADR-011 was rewritten. **Lesson: never write a framework version into a
  decision record without verifying it against the vendor's own documentation on the
  day.** Every version number in this repository carries the date it was checked.
- The correction changed two other things. Java 25 rather than 21 because JDK 24's
  JEP 491 removed virtual-thread pinning on `synchronized` blocks — a failure mode that
  presents as throughput collapse at idle CPU, which is exactly what this workload would
  have hit. And Spring Cloud AWS was dropped in favour of the AWS SDK v2 `SqsClient`
  directly, to remove the one dependency most likely to lag a new Spring major.

**Learned / worth remembering**
- ADR-005 and ADR-007 are the two decisions most likely to be challenged in an
  interview, and both have a clean answer that turns the challenge into a strength.
- The `turn_deadline` column exists solely so the timeout sweeper is an index scan
  rather than a table scan. Derived values cannot be indexed usefully.

**Next:** Phase 0 scaffolding — Maven project, compose stack, booting app, CI.
