# PROJECT_STATE

> **This is the primary recovery document.** If development resumes in another
> environment, with another engineer, or with another AI assistant, read this file
> first, then `ARCHITECTURE.md`, then `docs/adr/`.
>
> Update this file at the end of every milestone. A stale PROJECT_STATE is worse than
> none, because it will be trusted.

**SNAPSHOT: M6 (2026-09-30)** — see the `SNAPSHOT` file at the repository root.
If a build fails in a way that contradicts this document, check that file first: you may
be building an older extracted copy.

**Last updated:** 2026-09-30 · **Updated at:** Phase 6 complete


---

## 0. Handoff workflow (established 2026-09-14)

**Two rules, both established after being broken:**

1. **Work through `docs/BOOT4_CHECKLIST.md` and run its greps before shipping any
   framework-touching code.** Four separate round trips in Milestone 1.2 were caused by
   Boot 4 changes already recorded in this repository's own ADRs and logs, but not
   consulted.
2. **Documentation ships with the code that changed it — in the same turn, every turn.**
   Not "doc-only, it'll land with the export." Every deferral has produced a divergence:
   a patch that would not apply, and then a set of markdown files silently a week out of
   date. If a fix updates an ADR, `TROUBLESHOOTING.md` or the log, those files go out
   with the fix.


**No archive is produced per milestone.** Archives are created only at a **major phase
boundary**, or when explicitly requested, or when there is a technical reason one is
necessary. Downloading and re-extracting the tree for every small change was costing more
than it delivered, and twice caused work to be done against a stale copy.

### How changes reach the repository

| | |
|---|---|
| **Source of truth** | The git repository on the development machine. Not any archive, and not Claude's workspace. |
| **During a session** | Claude holds a working copy and delivers changes as file contents in chat — new files in full, edits as precise instructions. |
| **Applying them** | Save them into the tree and commit. The commit is what makes "what changed" answerable. |
| **Phase boundary** | One archive, uniquely named `chess-platform-<snapshot>-<date>.tar.gz`, plus a written summary (files changed, tests, commands, env requirements, ADRs, verification list). |

### Claude's workspace is ephemeral

It resets between sessions. If a session starts cold, either upload the current tree or
point Claude at this file — `PROJECT_STATE.md` plus `docs/adr/` is designed to be enough
to reconstruct intent, though not the code.

### Git is not optional for this project

Every delivery problem so far — stale extracted trees, a file silently overwritten by a
same-named file, "which version am I running" — is a problem git already solves.
`git status` after applying changes answers in one second what has otherwise taken a
debugging round trip each time.

```bash
git init && git add -A && git commit -m "Phase 0 + Milestone 1.1"
```

### Rule this does not relax

**Milestone size is unchanged.** Fewer handoffs means larger coherent units of work, not
shallower ones. The repository must be internally consistent at every handoff — never
left half-migrated because a sub-milestone ended.

---

## 1. Current position

| | |
|---|---|
| **Current phase** | Phase 10 — hardening & documentation |
| **Phase status** | Phase 9 complete (9.1–9.4). Phase 10 not started. |
| **Hours used (estimated)** | Phase 0 ~5, Phase 1 ~14, Phase 2 ~18, Phase 3 ~18 (done). Phase 4: ~13.5 (done). Phase 5: ~9 (done). Phase 6: ~6.5 (done). Dependabot triage ~1.5. Phase 7: ~15.5 (done). Phase 8: ~10 (done). UI pass ~4 (done). Phase 9: ~14.5 (done; top of 10–14) |
| **Cumulative hours (estimated)** | ~128.5 of 135–175 |
| **Schedule status** | On track; Phase 3 finished inside budget, near the top |
| **Scope status** | On track — no P2 feature built (`ROADMAP.md` § Time checkpoint — end of Phase 3) |
| **Next milestone** | Phase 10 — scope it first (§10) |
| **Handoff mode** | In-place edits; archive only at phase boundaries (see §0) |

---

## 2. Completed

### Phase 9 — Milestone 9.4 (2026-10-01 → 10-06): the AWS session — **Phase 9 complete**

- k6 as a one-off ECS task inside the VPC (`loadgen.tf`, `aws-loadtest.sh`). Found: bcrypt inside
  transactions emptied the pool (fixed `08fe129`); then bcrypt on virtual threads starved the
  carriers (fixed PR #29: `BoundedPasswordEncoder`, 503 load shedding). Also WebSocket payload
  validation and a harness reconnect storm. `docs/perf/optimisation-02.md`.
- After: 50-game burst 50/50 with 0 error lines; 500 sign-ups 0 failures, move p99 120 ms. Fargate
  measured to ~100 concurrent sockets (owner stopped on cost). Unit 103, integration 151.
- Ops: Terraform crash on Ctrl+C recovered (force-unlock + import); tainted-but-healthy services
  untainted; `rds.force_ssl` perpetual diff fixed. AWS destroyed and verified empty.

### Phase 9 — Milestones 9.2–9.3 (2026-10-01): baseline and one optimisation

- `docs/perf/baseline.md` (flat to 1,000 sockets) and `docs/perf/optimisation-01.md` (pool saturates;
  memory budget OOM-killed both pods; resized from measurement, pre-touched heap; 0 restarts after).
- Actuator open on the isolated management port (scrapers); k8s/ECS memory settings updated.
  Unit 95, integration 148.


### Phase 9 — Milestone 9.1 (2026-10-01): tracing

- Boot 4 native OTel (ADR-026); WS frame observations; traceparent through outbox (V8) + SQS;
  Grafana LGTM locally. One trace from resign to rating, verified by test (mutation-checked) and
  live. Finding: ~1 s of the rating push is the relay's poll interval.


### Interlude — frontend UI pass (2026-10-01, owner's request, ADR-025)

- Dark, lichess-like redesign: app shell, sign-in, lobby, game screen, SVG pieces (Cburnett,
  CC BY-SA 3.0), highlights incl. check, game-over dialog, phone layout. No UI libraries.
- Browser checks green on dev, on the image via kind, and the outage check on bootRun.


### Phase 8 — Milestone 8.3 (2026-10-01): the demonstration — **Phase 8 complete**

- k6: 40 live games across `kubectl rollout restart` — 40/40 consistent, 0 clock anomalies,
  0 abnormal closes; crash contrast (SIGKILL): 1006s, still 0 games lost.
- Report: `docs/perf/2026-10-01-rolling-deploy-kind.md` (the first file in `docs/perf/`).


### Phase 8 — Milestone 8.2 (2026-10-01): kind + Kustomize

- `k8s/`: kind cluster, vendored add-ons, base/deps/overlay, `cluster-up.sh`, `deploy.sh`
  (migrate Job gates the rollout). Probes on 8081 (`k8s` profile), preStop, no CPU limit,
  maxUnavailable 0, PDB, HPA, hardened pods.
- Fixed: readiness now `readinessState,db` — the drain reaches the probe; a Valkey outage no
  longer makes every instance unready (also fixes ECS).
- Verified: browser flow through ingress; a live socket survives a rolling restart (1001 → new pod
  in 0.5 s, ≥ 2 ready throughout). Unit 90, integration 146.


### Phase 8 — Milestone 8.1 (2026-10-01): graceful WebSocket drain

- `SocketDrain`: readiness off → 1001 GOING_AWAY to every socket → newcomers bounced, seeks kept;
  runs before the web server's graceful shutdown. Baseline without it: 1006.
- Client unchanged (full-jitter reconnect + snapshot resync already sufficient).
- Unit 90, integration 145.


### Phase 7 — Milestone 7.5 (2026-10-01): applied, verified, destroyed — **Phase 7 complete**

- Three applies: (1) migrations applied, then the migrate task died on a Valkey handshake
  timeout — services never started; (2) up and healthy, but moves failed on the plain-HTTP
  origin (`crypto.randomUUID` needs a secure context); (3) full browser game flow green, after a
  rolling deploy with the circuit breaker armed.
- External checks: actuator not routed, 8081 and direct task IPs closed, malformed JSON 400,
  cookie without Secure; ALB split 25/25 across the two API tasks.
- Destroyed; every service queried directly returns zero; only the bootstrap stack remains.
- Measured numbers in §12 (apply/destroy times, Fargate start times, AWS latencies).


### Phase 7 — Milestone 7.4 (2026-10-01): compute, planned

- ECS (Fargate + Spot), three task definitions, narrow execution role, worker-only task role,
  migrate-before-rollout via `terraform_data`, ALB (health check on the management port).
- Actuator moved to 8081 with health details off in the `aws` profile (was public on 8080).
- Plan: 62 to add. Unit 90, integration 141.


### Phase 7 — Milestone 7.3 (2026-10-01): network + data tier, planned

- `infra/app`: VPC (public + isolated, 2 AZs, no NAT), security groups by reference, RDS
  PostgreSQL 16 (own parameter group, managed master password, TF-owned log group), Valkey 8.2
  (TLS required), SQS + DLQ, JWT secret (ephemeral, write-only — not in state).
- Plan: 42 to add, clean. Deliberately not applied until 7.4 (idle data tier ≈ $0.80/day est.).


### Phase 7 — Milestone 7.2 (2026-10-01): bootstrap stack — first AWS resources

- `infra/bootstrap` applied: budget adopted (credit/refund filter preserved — caught in plan
  review), state bucket (versioned, SSE, TLS-only, prevent_destroy, S3-native locking; own state
  migrated in), ECR (immutable, scan on push, keep 3), GitHub OIDC role scoped to `main`.
- CI pushes the SHA tag to ECR via OIDC when `AWS_ECR_PUSH_ROLE_ARN` is set.
- Owner removed the IAM user's access key; now `aws login`. MFA still to enable.


### Phase 7 — Milestone 7.1 (2026-09-30): deployable application

- SPA in the image (one origin); `aws` profile with forwarded headers trusted from the VPC
  only; configurable refresh-cookie `Secure` (default on, WARN when off); banner off.
- Bug fixed: client errors (malformed JSON, bad path variable, 405, missing file) were 500s.
- 11 new real-server integration tests; unit 90, integration 138. Browser check passes against
  the image on :8080. Decisions and scope change: ADR-023.


### Dependabot triage (2026-09-30)

- All six first-run PRs handled on one branch: Gradle 9.8.0, Testcontainers 2.0.5, AssertJ
  3.27.7, logstash-logback-encoder 9.0, Vite 8.3.1 + plugin-react 6.1.1, TypeScript 7.0.2.
- **Latent bug fixed:** the catalog's Testcontainers and AssertJ pins fought the Boot BOM
  (mixed 1.x/2.x Testcontainers classpath; AssertJ downgraded). Both pins deleted; Boot owns them.
- Jackson 2 databind no longer ships (encoder 9 is on Jackson 3); its CVE override removed.
- Dependabot groups Vite with its React plugin; wrapper bootstrap finished (`gradlew.bat`).
- Verified: unit 90, integration 127, frontend build (and a planted type error caught),
  migrate role with JSON logs, both browser checks; console errors `none` after a favicon fix.


### Phase 6 — closeout (2026-09-30) — **Phase 6 complete**

- `main` is the protected default branch (`backend`, `frontend`, `image` required, strict,
  enforced for admins). Work lands through PRs.
- Verified on GitHub (anonymously, public repo): run 36616824363 on `main` green;
  `ghcr.io/abhinav2777/chess-platform` public, tagged `main` and `270c330…`.
- The first CI runs found two real bugs: uppercase owner in the image name (run 1), and a
  matchmaking race — a seek told a just-matched player `ALREADY_IN_GAME` (PR #7) — fixed with a
  deterministic reproduction.
- Dependabot is live: 6 PRs opened on its first run.


### Phase 6 — Milestone 6.2: the pipeline (2026-09-29, written + linted; not yet run)

- `.github/workflows/ci.yml` (ADR-022): `backend`, `frontend` (new — the frontend was never in
  CI), `image` (build → Trivy fixable HIGH/CRITICAL gate → push to GHCR on `main` only, SHA +
  `main` tags). Least-privilege token; every action SHA-pinned (versions current as of
  2026-09-29: checkout v7.0.1, setup-java v6.0.1, setup-node v7.0.0, upload-artifact v7.0.1,
  gradle v6.4.0, buildx v4.4.1, build-push v7.4.0, login v4.6.0, metadata v6.2.0, trivy
  v0.36.0).
- `.github/workflows/e2e.yml`: nightly + manual; compose deps, bootRun, Vite, runner Chrome;
  `e2e:lobby`, then Valkey fanout + `e2e:outage`; screenshots and logs as artifacts.
- `.github/dependabot.yml`: gradle, npm, github-actions, docker.
- `.github/branch-protection.json`: the three checks, strict, enforced for admins.
- `actionlint` (with shellcheck): clean. **Not yet run on GitHub** — see §10.


### Phase 6 — Milestone 6.1: the container image (2026-09-29, verified)

- **Decided with the owner:** `main` + PR flow; GHCR now, ECR in Phase 7; browser checks
  nightly + manual.
- **Found:** CI has never run (0 workflow runs; no `main` on GitHub; default branch
  `users/Abhinav/initial`) — Phase 0's "CI confirmed working" had no run behind it.
- `backend/Dockerfile` (ADR-021): multi-stage, dependency layer, layered jar, JRE noble,
  uid 10001, `JDK_JAVA_OPTIONS`, exec entrypoint; allowlist `Dockerfile.dockerignore`;
  `resolveDependencies` Gradle task. Roles: `application-worker.yml`,
  `application-migrate.yml` + `MigrateAndExit`. Compose `--profile app` (migrate → api +
  worker, Valkey fanout, dev rate limits via `SPRING_APPLICATION_JSON`).
- **Verified:** cold build 142 s / code-only 17.7 s; app layer 565 kB of 400 MB; non-root;
  migrate exit 0; Postman 132/132 and `e2e:lobby` against the containers (rating across
  worker → API containers in 799 ms); graceful stop 2.5 s, exit 143.
- **Trivy:** 3 CRITICAL (Tomcat 11.0.24) + 2 HIGH (Jackson 3.1.5 / 2.21.5) in Boot 4.1.1's
  managed versions → overridden (`tomcat.version` 11.0.25, `jackson-bom.version` 3.1.6,
  `jackson-2-bom.version` 2.21.6) → suite green → rescan 0.
- **Environment:** this machine's containers cannot reach the internet (daemon DNS points at
  an absent resolver; bridge egress blocked). Verified via a temporary forwarder + host
  network, since removed (TROUBLESHOOTING). An OOM kill (137) came from running the suite
  beside the app containers.


### Phase 5 — Milestone 5.3: rating changes pushed to players (2026-09-29) — **Phase 5 complete**

- `rating.RatingsChanged` published inside the rating transaction; `realtime.RatingAnnouncer`
  (`AFTER_COMMIT`) sends `RATING_UPDATED {gameId, rating, delta}` via `UserNotifier` (the
  worker is rarely the instance with the player's socket). Nothing for duplicates.
- Client: "Rating 1216 (+16)" on the finished-game panel ("Updating rating…" until then);
  lobby heading shows the current rating from `/api/users/me` — the pull fallback.
- Tests: realtime — both players receive the update after commit, a duplicate apply sends
  nothing. Browser — `e2e:lobby` now resigns the game and waits for both ratings:
  **0.8 s from resignation to both screens**, the whole async pipeline included.
- `e2e` sign-in wait raised to 15 s after a cold-start failure (the app, not the check, was
  slow for its first requests).


### Phase 5 — Milestone 5.2: the rating consumer (2026-09-29, green)

- `V7__ratings.sql`: `processed_events (consumer, event_id)` PK; `rating_history (game_id,
  user_id)` PK + delta check + FKs (cascade).
- `rating` module: `Elo` (K = 32, Black = −White so no drift), `RatingService.apply` (one
  transaction: `ON CONFLICT DO NOTHING` claim → lock both players in id order → compute →
  ratings via `IdentityFacade.lockRatings/setRating` → history), `GameFinishedListener`
  (`@SqsListener`, **manual ack after commit**, worker role only, rejects unknown schema
  versions, ignores other event types).
- `messaging`: `QueueBootstrap` (creates queues + redrive before listeners start, phase 0);
  queue visibility timeout configurable; `QueueDepthMonitor` (`chess.sqs.messages{queue}`,
  error log when the DLQ becomes non-empty). `queue-not-found-strategy: fail` for listeners.
- **Done-when, proven** (`RatingConsumerIntegrationTest`, real ElasticMQ + listener): duplicate
  delivery rates once; poison → DLQ after 3 receives (3.5 s at 1 s visibility); crash
  mid-transaction (trigger, sequence-counted) → applied once on redelivery; concurrent games
  of one player → no lost update.
- **Mutation-checked:** the first concurrency test passed with the lock removed (race window
  of milliseconds). Rewritten deterministic (latch + 300 ms trigger); now fails without the
  lock (1200 instead of 1216).
- **Found at first start:** the listener container refuses `maxMessagesPerPoll` (default 10)
  above `maxConcurrentMessages` (5) — now tied together.
- **Local stack:** 7 backlogged events rated 80 ms after startup; Postman games rated live;
  both queues empty.


### Phase 5 — Milestone 5.1: outbox, relay, SQS (2026-09-28, green)

- **Decided with the owner:** ElasticMQ instead of LocalStack (ADR-019 — LocalStack needs an
  account since March 2026); ratings will be pushed (`RATING_UPDATED`, 5.3).
- `V6__outbox.sql`: `outbox` (UUIDv7 id = event id, jsonb payload, `published_at`,
  `attempts`, `last_error`), partial index on unpublished, `UNIQUE(event_type, aggregate_id)`.
- **Migrated to Spring Cloud AWS 4.1.1 at the owner's request (ADR-020)** — 3.4.0, as first
  proposed, is the Boot 3.5 line; 4.1.1 verified on Boot 4.1 + Netty 4.2 by the suite. Our
  `SqsTemplate` uses `QueueNotFoundStrategy.FAIL` (tested); `--enable-native-access` for Netty.
- New **`messaging` module**: `Outbox.append` (`MANDATORY`), `OutboxRelay` (SKIP LOCKED claim
  → `SqsTemplate.sendMany` with per-message results → mark, one transaction; 20-attempt cap),
  `OutboxRelayScheduler` (worker role, `chess.messaging.relay-enabled`), `SqsSetup` (timeout
  customizer, template bean),
  `SqsQueues` (lazy URLs — API instances never need SQS to start; creates queue + DLQ +
  redrive on ElasticMQ). Metrics: backlog, oldest age, stuck, published, send failures.
- `game.GameFinished` (the message contract, `schemaVersion` 1) and
  `GameFinishedOutboxWriter` — one `MANDATORY` listener on `GameEnded` covers every ending;
  aborts produce nothing.
- Compose: `elasticmq` (9324 API, 9325 UI). Local profile runs the relay in-process.
- Tests: `OutboxIntegrationTest` (7 — every ending path incl. move-path flag under rollback;
  aborts; MANDATORY; uniqueness), `OutboxRelayIntegrationTest` (4 — envelope, redrive policy,
  two concurrent relays × 30 events exactly once, SQS down keeps the event).
- **Verified locally:** V6 applied; Postman's games → 3 events (checkmate, resignation,
  repetition; the abort none) → all published → 3 messages on `game-events`.


### Phase 4 follow-up: the 4.8 s explained — ValkeyGuard (2026-09-28, green)

- **Traced, not guessed:** browser frames + polls aligned with server logs. Two causes:
  `application-local.yml` set a 2 s Redis timeout since the first commit (doubling the 1 s
  fail-fast), and only the rate limiter had a circuit — the move path paid two sequential
  Valkey timeouts on the socket thread.
- **Fix (ADR-018):** override removed; `common.resilience.ValkeyGuard`, one 5 s circuit per
  instance, used by rate limiter, fanout publisher, user notifier, presence, matchmaking.
- **Measured:** mover's first degraded move 4.8 → 1.9 s; steady state 0.9–2.0 s. **Cost:**
  recovery waits up to the 5 s window — caught by a failing recovery assertion first.
- Also fixed: the WS flood test was timing-dependent (20/s refills during the burst; on a
  cold JVM all 25 got through). Its context now uses a 5-per-10 s bucket: exactly 5 / 20.
- Tests: `ValkeyGuardTest` (5). Unit 82, integration 108. `e2e:outage` now waits out the
  window and fails unless both boards return to "Live".


### Phase 4 — Milestone 4.3: Valkey outage end to end (2026-09-28, green) — **Phase 4 complete**

- **Server:** `ValkeyOutageIntegrationTest` (fanout=valkey, Valkey **paused** so it hangs and
  can recover on the same port): a whole game to checkmate with Valkey down, 7/7 moves, seek →
  `MATCHMAKING_UNAVAILABLE`, live events resume after unpause.
- **Client fallback** (`useGame`): degraded when our own `MOVE_MADE` echo is missing after
  1.5 s, or — for the waiting player — after 10 s of socket silence on the opponent's turn;
  then polls `GET /api/games/{id}` every 2 s until a socket event arrives. Forward-only apply;
  refused moves (ERROR) never count as a missing echo; a follow-up poll after resigning.
  Indicator: "Live · updates delayed".
- **Browser-verified** (`npm run e2e:outage`): play continues through a Valkey pause; latency
  table in §12; recovery < 100 ms.
- `frontend/e2e/` — both browser checks committed (`e2e:lobby`, `e2e:outage`) with a README.
- First degraded move took 4.8 s — **attributed and fixed in the follow-up below (ADR-018).**


### Phase 4 — Milestone 4.2: rate limiting (2026-09-28, green)

- **ADR-017.** `common.ratelimit.RateLimiter.enforce(limit, subject)`; `token-bucket.lua`
  (lazy refill, Valkey `TIME`, TTL = one full refill). Bucket4j evaluated and rejected
  (Lettuce 6 vs 7, separate native connection); decided with the owner.
- Limits: login 10/min per IP + 5/min per username; register 5/min per IP; moves 20/s per
  user (REST and WS share the bucket); seeks 10/30 s. `local` profile raises auth limits.
- `429` + `Retry-After` (`DomainException.RateLimited`); socket `ERROR RATE_LIMITED`.
- **Fail open behind a 5 s circuit** (owner chose fail-open for login too). Measured: first
  move after a Valkey outage 1,037 ms, then no cost.
- **Found:** test contexts without Valkey configured were talking to the dev machine's
  compose Valkey — pass in CI, fail locally. Limiter disabled in `IntegrationTestBase` and
  `AuthApiIntegrationTest`; checklist item added. Outage test now polls and reports.
- Tests: `RateLimiterFailOpenTest` (unit, dead port + controllable clock),
  `RateLimitIntegrationTest` (6, production limits, HTTP 429), WS move flood.


### Phase 4 — Milestone 4.1c: lobby UI (2026-09-28, verified in a browser)

- **Play online**: a button per preset → `useSeek` opens a lobby socket, seeks, re-seeks every
  15 s (and on every reconnect), shows elapsed wait and Cancel. `MATCH_FOUND` → the game view.
  A cancel that races a match loses to the match.
- `GameSocket` generalised rather than duplicated: `gameId` optional (lobby = no SUBSCRIBE),
  game handlers optional, `seek` / `cancelSeek`.
- **Board layout bug fixed**: ranks without pieces rendered shorter (implicit grid rows sized
  by content). Found the first time the board was looked at in a real browser.
- **Verified with headless Chromium** (playwright-core, two isolated users): both land in the
  same game with opposite colours ~0.3–0.8 s after the second seek; White's e4 reaches Black;
  Black's clock ticks (4:59 → 4:57); Cancel returns to idle. Script:
  scratchpad `lobby-e2e.mjs` — not yet in the repo (see §10).


### Phase 4 — Milestone 4.1b: matchmaking over WebSocket, and the ArchUnit fix (2026-09-28, green)

- **Protocol:** `SEEK` / `CANCEL_SEEK` → `SEEK_STATUS` | `MATCH_FOUND`. MATCHED is always
  `MATCH_FOUND`, so the client has one message that navigates to a game.
- **Delivery:** `MatchFound` event → `MatchAnnouncer` → `UserNotifier` port (local in-JVM;
  Valkey: publish `user:{id}`, every instance pattern-subscribes `user:*`). Registry indexes
  sockets by user.
- **Recovery:** unseen match re-sent after `AUTH_OK`; subscribing to the announced game
  acknowledges it (per-socket, so a plain subscribe makes no Valkey call). Closing the
  seeking socket cancels the seek.
- **ArchUnit was checking nothing** (1.3.0 could not read Java 25 bytecode; zero classes;
  `allowEmptyShould` hid it). Upgraded to 1.5.1; fixed every violation: `IdentityFacade
  .verifyAccessToken`; `AuthenticatedUser` → identity, `AuthProperties` → identity.internal,
  `WebSocketConfig` → realtime; `GameFacade.state/submitMove/resign`; `SubmitMoveCommand`
  public. New guard `importerSeesTheCodebase`; rule mutation-checked.
- **`GameFacade.state`** — one read-only `REPEATABLE READ` snapshot (game + log + `now()`)
  for both REST and `GAME_SNAPSHOT`; closes a latent board/move-list skew under READ COMMITTED.
- Tests: realtime matchmaking (5: pair over sockets with the live scheduler, seek/cancel,
  close cancels, reconnect recovery + ack, validation) and cross-instance match with two
  JVMs' matchmakers racing.


### Phase 4 — Milestone 4.1a: matchmaking core (2026-09-28, green)

- **ADR-016.** Valkey queue per preset time control (`mm:q:{tc}:rating` + `:since` ZSETs),
  `mm:seek:{user}` liveness key (TTL 45 s), `mm:match:{user}` = `PENDING` | gameId.
- **Every state change is a Lua script** (`resources/matchmaking/*.lua`): seek, cancel, pair,
  compare-and-set, compare-and-delete. Queue time from Valkey `TIME`.
- `pair.lua`: oldest first, window 100 + 10/s capped at 400, nearest live neighbour (5 each
  side), evicts lapsed seekers, marks the pair PENDING. One pair per call.
- `Matchmaker.tick()` on every instance, no leader: claim → `GameFacade.startGame` (commit
  point) → record match → `MatchFound` event. `MatchmakerScheduler` 1 s, property-gated.
- `MatchmakingFacade`: idempotent `seek` (heartbeat + repair), `cancel`, `unseenMatch`,
  `acknowledge`. `503 MATCHMAKING_UNAVAILABLE` when Valkey is down (new
  `DomainException.Unavailable`). New codes: `UNSUPPORTED_TIME_CONTROL`, `ALREADY_SEEKING`,
  `ALREADY_IN_GAME`.
- `GameFacade` gains `startGame` and `hasActiveGame` — the first mutation on a facade, and why.
- Metrics: `chess.matchmaking.seeks`, `.matches`, `.pairing_failures`, `.wait` (histogram).
- **Not built, by decision:** `game:{id}:state` read cache (ADR-016).
- Tests: `MatchmakingIntegrationTest` (13), incl. **20 players × 2 seeks vs 4 concurrent
  matchmakers → exactly 10 games, no duplicates, nothing left in Valkey.**


### Phase 3 — Milestone 3.3: closeout (2026-09-28, green) — **Phase 3 complete**

- **Threefold repetition (ADR-015).** `ChessRules.isThreefoldRepetition(current, earlier)`;
  history from `moves.fen_after`, bounded by the FEN halfmove clock (≤100 rows, PK range
  scan, projection), skipped below 8 reversible plies. FIDE sameness incl. en-passant
  *possibility* — chesslib writes the square after every double push (verified). Automatic
  draw. Dead `isDraw() → DRAW_REPETITION` branch removed; naive `Position.repetitionKey()`
  replaced by `halfmoveClock()`.
- **REST clocks from `ServerClock`.** `GameSummary.from/withNames` take `now` as a parameter.
  ADR-006 gains "Scope of the time authority".
- **`GAME_SNAPSHOT.moves`** (SAN list, additive) — closes the "move list empty after
  reconnect" gap, which auto-resync would otherwise have made worse.
- **Client resync on `CONFLICT`:** re-`SUBSCRIBE` on the same socket, adopt the snapshot, do
  **not** resubmit the move.
- **Phase 3 "done when", measured:** 100-round concurrency soak; `ClockSkewIntegrationTest`
  (JVM `Clock` 10 min fast); `kill -9` mid-game → clocks correct after restart (§12).
- Cleanups: Boot's in-memory `UserDetailsService` excluded; local SQL/bind logging opt-in
  (bind TRACE printed credential hashes); unused `Clock` removed from `GameService`.
- Tests: `ChessRulesTest$Repetition` (8), repetition through the pipeline, conflict → resync
  on one socket, snapshot `moves` after reconnect, skew test, rounds test.
- Postman folder 6 (repetition) — 54 requests, 132 assertions.


### Phase 3 — Milestone 3.2: games nobody started, and the clock in the browser (2026-09-28, green)

- **First-move abort (ADR-014).** 30 s per player to make a first move; until both have
  moved, any expiry aborts (`ABORTED` / `ABANDONED` / no result). `Game.expiryAt(now)`
  returns `NONE | ABORT | FLAG` for both the move pipeline and the sweeper.
- `turn_deadline` = `min(flag-fall, window end)` while `ply < 2` — same index, query and
  sweeper as timeouts. `V5__first_move_abort_deadline.sql` backfills active games.
- A late first move is refused with **`422 GAME_ABORTED`** (new `ErrorCode`), the abort
  persisted in `REQUIRES_NEW`.
- **Resigning before both players have moved aborts** — closes instant-resignation rating
  farming.
- `GameEnded.status`; `GAME_FINISHED {gameId, status, result|null, termination}`. The
  broadcaster's unconditional `result().name()` would have thrown on the first abort.
- Metrics `chess.game.aborts`, `chess.move.late_first_moves`.
- **Client:** `clock.ts` (anchor + compute, `performance.now()`), `Clock.tsx`, time-control
  presets, readable endings, Abort/Resign label. `vite-env.d.ts` — `npm run build` had
  never passed.
- **Tests:** `GameExpiryTest` (13, pure); `ClockIntegrationTest` timeout tests now start
  the game first + new `Abort` group (5); realtime end-to-end abort via the live
  scheduler; fanout of a null result across Valkey. **Sweeper disabled for every
  `IntegrationTestBase` context** (cached-context race).
- Postman folder 5 — 44 requests, 110 assertions.

### Phase 3 — Milestone 3.1 fixes (green, 2026-09-14)

- `GameTimeouts` — the single place a timeout is written. `finaliseIfFlagged` runs
  `REQUIRES_NEW` so the move path's timeout survives the `OUT_OF_TIME` exception;
  `finaliseExpiredBatch` claims and finalises in **one** transaction, because
  `REQUIRES_NEW` against a `FOR UPDATE` row hangs.
- `TimeoutSweeper` reduced to scheduling only, calling `GameTimeouts` through the proxy.
  **The previous version never finalised a game in production** (self-invocation).
- `ClockIntegrationTest` disables the scheduler and drives sweeps explicitly, since a working
  sweeper races the assertions.
- `TimeControl` static-initialisation order fixed by the project owner.
- Workspace reset mid-milestone; restored from the owner's upload. Four lost documentation
  updates restored against the real files.

### Phase 3 (in progress) — Milestone 3.1: the server-authoritative clock

- `V4__game_clock.sql` — five columns plus a partial index on `turn_deadline` for ACTIVE
  games. **No column defaults**, so a bug that forgets the clock fails loudly instead of
  silently producing a playable 5+3 game.
- `ClockCalculator` — pure static functions, the clock never ticks (ADR-006). 14 unit
  tests, no database, no sleeping.
- `ServerClock` — `SELECT now()` from PostgreSQL. One time authority for every instance;
  `now()` rather than `clock_timestamp()` so a player is not billed for our processing.
- `Game` gains clock state, `hasFlagged`, `flagOnTime`, `remainingMs`.
- Move pipeline checks the clock **before** legality — a player already out of time does
  not get to play a legal move.
- `TimeoutSweeper` — `@Scheduled(fixedDelay)`, `FOR UPDATE SKIP LOCKED`, batch of 100,
  never propagates exceptions (that would cancel the schedule permanently).
- `TimeControl` value type with validation; `POST /api/games` accepts it, defaults to 5+3.
- Clocks flow through `GameView`, REST DTOs, `GAME_SNAPSHOT` and `MOVE_MADE`.
- Metric `chess.clock.timeouts`, `chess.move.flag_falls`.
- **The reconnect-to-another-instance case needed no code**, which is the result ADR-006
  was chosen for.

### Phase 2 — Milestone 2.3: React client

- Vite + React 19 + TypeScript. **No chess library, no chess rules on the client.**
- `GameSocket.ts` — protocol client: first-frame auth, 25 s heartbeat, reconnect with
  exponential backoff **plus full jitter**. Backoff resets on `AUTH_OK`, not socket open.
- `useGame.ts` — snapshot adopted unconditionally (ADR-007). `clientMoveId` generated
  client-side; `expectedPly` sent with every move.
- `Board.tsx` — hand-written 8×8 grid. Contains no chess logic: legal destinations are
  read from the server's `legalMoves`, and promotion is detected by the absence of the
  4-character move rather than by knowing about the eighth rank.
- `api.ts` — access token in a module variable, never web storage. Refresh token never
  touched by client code; `credentials: 'include'` throughout.
- Connection state always visible, so a quiet board is not mistaken for a broken app.
- **Known gap:** the move list resets on reconnect (the snapshot carries position, not the
  log). `GET /api/games/{id}` has it; small follow-up.

### Phase 2 — Milestone 2.2: cross-instance fanout and presence

- `ValkeyGameEventPublisher` + `GameChannelListener` + `ValkeyFanoutConfig`. One channel
  per game; instances subscribe on **first local subscriber** and unsubscribe on **last**,
  so upstream subscriptions scale with games in play rather than with connections.
- `GameEventPublisher` gained default `onFirstLocalSubscriber` / `onLastLocalSubscriber`
  hooks, so the handler stays unaware of the transport.
- `PresenceTracker` — Valkey keys with a 60 s TTL. Explicit delete on disconnect; the TTL
  is the safety net for an instance that dies without cleanup. Degrades silently: unknown
  reads as offline.
- `PLAYER_PRESENCE` protocol message; `GameSnapshot` now carries `opponentOnline`.
- `spring.data.redis` fail-fast timeouts (1 s / 500 ms). A cache must never stall a request.
- **`ValkeyFanoutIntegrationTest` starts two real Spring instances** and proves a move on
  one reaches a player on the other — plus a Valkey-outage test asserting moves still
  commit with no fanout at all.
- ADR-003 updated: its central claim is now verified rather than argued.

### Phase 2 — Milestone 2.1: WebSocket protocol

- `realtime` module: versioned JSON envelope, closed `ClientMessage`/`ServerMessage`
  enums, record payloads. **No `seq`** — dropped as redundant with `ply`
  (ARCHITECTURE.md §5.2 revision).
- `ChessWebSocketHandler`: first-frame auth with timer + unauthenticated cap,
  subscribe → `GAME_SNAPSHOT`, MOVE/RESIGN through the **same `GameService` pipeline as
  REST**, PING/PONG.
- `GameSessionRegistry`: local sockets only, never game state. Reports first/last local
  subscriber so 2.2 can subscribe upstream per game rather than per socket.
- `GameEventPublisher` port + `LocalGameEventPublisher` (in-JVM, `@ConditionalOnMissingBean`).
  **This is the implementation ADR-003 rejects** — it exists so 2.2's Valkey version is a
  demonstrable improvement rather than an asserted one.
- `GameEvents` + `GameEventBroadcaster` with `@TransactionalEventListener(AFTER_COMMIT)`.
  Committed-but-not-broadcast is recoverable; broadcast-but-not-committed is not.
- `ConcurrentWebSocketSessionDecorator` for write serialisation **and** send-buffer
  backpressure. Applied to every callback, not just connection setup.
- Allowed origins moved from Java to configuration.
- Metrics: `chess.ws.connections.active`, `chess.ws.games.watched`,
  `chess.ws.moves.broadcast`, `chess.ws.games.finished`.
- Tests: `RealtimeGameplayIntegrationTest` — 12 cases with two real sockets, including
  forged token, auth timeout, command-before-auth, foreign-game subscribe, and a
  **reconnect that restores a client which missed a move entirely**.

### Phase 1 — Milestone 1.3b: game lifecycle and the move pipeline

- `V3__game_side_to_move_varchar.sql` — fixes `side_to_move CHAR(1)`, the same bpchar trap
  as `token_hash`. Caught by `docs/BOOT4_CHECKLIST.md` *before* writing the entity.
  Fixed forward; V1 untouched.
- `game` module: `Game` and `MoveRecord` entities (composite key `(game_id, ply)` via
  `@IdClass`), repositories, `GameStatus`/`GameResult`/`Termination`, `GameFacade` +
  `GameView`.
- `GameService` — the move pipeline. Three overlapping defences per ADR-005: idempotency
  key, `@Version` optimistic lock, composite PK. **No distributed lock.** Handles
  `OptimisticLockingFailureException` and `DataIntegrityViolationException` explicitly.
- Metrics: `chess.move.processing`, `chess.move.conflicts`,
  `chess.move.idempotent_replays`, `chess.move.stale_submissions`.
- `GameController`: create, get (state + move log + legal moves), submit move, resign,
  list my games. `IdentityFacade.findByUsername` added for challenges.
- `docs/api/chess-platform.postman_collection.json` — 38 requests, 92 assertions,
  covering every Phase 1 endpoint plus the security behaviours (enumeration timing,
  refresh reuse detection, mass assignment, idempotent retry). Regenerate at each phase
  boundary.
- Tests: `GameplayIntegrationTest` — lifecycle, checkmate awarded to the right side,
  resignation, out-of-turn, illegal, non-player, stale ply, idempotent retry, **and the
  flagship 16-thread concurrency test asserting exactly one move commits at a ply**.

### Phase 1 — Milestone 1.3a: chess rules port

- `chess` module: `ChessRules` port, `Position`, `MoveIntent`, `MoveResult`,
  `GameOutcome`, `Side`, `Promotion`, `IllegalMoveException`.
- `chess.internal.ChesslibRules` — the only class that touches chesslib. Constructs a
  `Board` per call and discards it, because chesslib's `Board` is mutable and not
  thread-safe (ADR-002).
- `DomainException.Rejected` → HTTP 422, for requests understood but not permitted.
- Tests: `PerftTest` (17 published node counts across 5 standard positions — verifies the
  library rather than trusting it) and `ChessRulesTest` (legality, pins, castling, en
  passant, under-promotion, checkmate, stalemate, insufficient material, fifty-move, and a
  16-thread concurrency test that fails if anyone caches a `Board`).
- Pure unit tests — no Spring, no Docker. Run via `:backend:test` in seconds.

### Phase 1 — Milestone 1.2: authentication over HTTP (COMPLETE, green)

- `V2__refresh_tokens.sql` — hashed tokens, `family_id` lineage, partial expiry index.
- `JwtService` — HS256 via Spring Security's `JwtEncoder`/`JwtDecoder` over Nimbus,
  algorithm pinned. `verify()` returns `Optional`, reusable from Phase 2's WebSocket auth.
- `RefreshTokenService` — rotation with family-based reuse detection (ADR-013); the
  rotation claim is a conditional UPDATE, not a read-check-write.
- `SecurityConfig` — Security 7 lambda DSL, stateless, deny-by-default.
- `JwtAuthenticationFilter` — never rejects; populates SecurityContext + MDC, clears both.
- `AuthController` (register/login/refresh/logout), `UserController` (`/me`).
- `ApiExceptionHandler` — RFC 7807 problem+json, domain errors verbatim, everything else
  opaque with a correlation id.
- New dependency: `org.springframework.security:spring-security-oauth2-jose`.
- Tests: `AuthApiIntegrationTest` — 15 cases including reuse detection, mass assignment,
  forged tokens, cookie hardening, and an assertion that failures leak no internals.

### Phase 1 — Milestone 1.1: identity domain (COMPLETE, green)

- `common/error`: `ErrorCode`, `DomainException` (Conflict / NotFound / Unauthorized),
  stack traces disabled since these are thrown on expected paths.
- `common/id/Uuid7`: RFC 9562 v7 generator, lock-free via CAS, monotonic within a
  millisecond, survives clock regression. Hand-written rather than a dependency.
- `identity/domain`: `User` entity + `UserRepository`.
- `identity/internal`: `UserRegistrar` (check-then-insert race handled by the unique
  constraint, not the pre-check), `UserAuthenticator` (uniform error + dummy-hash timing
  defence against user enumeration).
- `identity`: `IdentityFacade` + `UserSummary` — the module's only public surface.
- `platform`: `PasswordEncoderConfig` (bcrypt cost 12 via `DelegatingPasswordEncoder`),
  `ClockConfig`.
- Tests: `Uuid7Test` (6 cases incl. ordering, clock regression, concurrency),
  `UserRegistrationIntegrationTest` (13 cases incl. a 16-thread registration race).

**Fixed while building:** the `entitiesDoNotLeak` ArchUnit rule was too strict — it
forbade a module's own facade from mapping its own entity, which is the pattern it was
meant to encourage. Rescoped per-module. Also excluded `-serial` and `-processing` from
`-Xlint:all`, since `RuntimeException` being `Serializable` made every exception class
fail the build under `-Werror`.

### Phase 0 — COMPLETE (verified 2026-09-06)

Build, compose stack, health check, Flyway V1, integration tests and CI all confirmed
working on the development machine.

### Phase 0 detail (partial)

- Specification reviewed; contradictions identified and resolved:
  - observability scheduled after the deadline that requires it → made cross-cutting
  - "Game Service → workers" vs "modular monolith" → workers are the same artifact
  - AWS + Kubernetes phases implied deploying twice → ECS/kind split (ADR-010)
  - the phase budgets omit learning time → roadmap re-costed at 135–175 h
- Technology choices validated and recorded as ADRs 001–011.
- `ARCHITECTURE.md` written: schema, real-time protocol, clock design, concurrency
  strategy, failure matrix, scalability path, testing strategy.
- `ROADMAP.md` written with per-phase MU/MI/SKIP and definitions of done.
- Repository structure defined.
- Scaffolding written: `settings.gradle.kts`, `gradle/libs.versions.toml`,
  `backend/build.gradle.kts`, `ops/docker/docker-compose.yml`, `application.yml` +
  `application-local.yml`, `logback-spring.xml`, `V1__baseline.sql`,
  `ChessPlatformApplication`, `ModuleBoundaryTest`, `.github/workflows/ci.yml`.
- Gradle wrapper bootstrapped on the dev machine; `verifyGradleVersion` passes.
- **chesslib corrected to 1.3.7 and sourced from JitPack** via an `exclusiveContent`
  repository scoped to `com.github.bhlangonijr` (ADR-012). It was never on Maven Central;
  the original `1.3.4` on `mavenCentral()` could not have resolved at any version.
- **Toolchain auto-provisioning added** (`foojay-resolver-convention` 1.0.0 in
  `settings.gradle.kts`). The Java 25 toolchain declaration was previously a requirement
  with nothing able to satisfy it on a machine without JDK 25.
- **Gradle pinned to 9.7.1** in `gradle/libs.versions.toml`, enforced by a root
  `wrapper` task that reads the catalog and a `verifyGradleVersion` CI step that fails on
  drift. The wrapper itself is not yet generated — see §10.
- `.gitignore` rewritten for Gradle (it was still Maven-era, ignoring `target/` and
  nothing Gradle produces) with explicit negations so `gradle-wrapper.jar` is committed.
- CI now validates the wrapper jar checksum before any Gradle execution.
- Build tool changed to **Gradle** (Kotlin DSL + version catalog) at the project owner's
  request. ADR-011 rewritten; the Maven argument was portfolio legibility, which loses
  to the owner's existing fluency.
- Spring Boot target corrected twice: 3.5.x → 4.0.x → **4.1.x**. 3.5 reached OSS
  end-of-life on 2026-06-30 and was the last of the 3.x line; 4.0.x support ends
  December 2026, inside this project's timeline.

---

## 3. Not yet started

- **Phases 5–10** per `ROADMAP.md`.

---

## 4. Known bugs / unverified state

The Phase 0 risk table that used to live here is resolved: the build, Boot 4.1 starter
coordinates, the Java 25 toolchain, `-Werror`, Flyway, Testcontainers and CI were all
verified green through Milestone 3.1. **ArchUnit was green but vacuous until 4.1b** — it
imported zero Java 25 classes; fixed and guarded (ADR-001 correction). The history is in
`DEVELOPMENT_LOG.md`.

| Item | State | How to check |
|---|---|---|
| Conflict auto-resync not seen in a browser | The server half is integration-tested; the React half compiles but has not been watched (clock rendering was verified in 4.1c) | Hard to trigger from the UI; a browser test that injects a stale-ply MOVE would do it |
| V5 backfill never exercised on real data | No ACTIVE games existed when it ran locally; correct by inspection | Only matters for a deployed database with games in flight |
| No automatic access-token refresh in the client | A session lasts 15 min, then socket auth fails until sign-in | Phase 4 or 10 |
| A move with `from` = `to` is a 500 / `INTERNAL` | `MoveIntent` throws `IllegalArgumentException`, which no handler maps (REST and WebSocket). Found in 9.4 review | Send `{"from":"e2","to":"e2"}`; Phase 10 hardening |
| Hashing queue sized by guess (16) | Too deep for 0.5 vCPU: sign-up waits up to 27 s in a burst (optimisation-02) | Set ~2–4 per task from the measured ~0.9 vCPU-s per hash; verify on the next AWS run |

---

## 5. Technical debt / accepted compromises

Recorded now so they are not discovered later and mistaken for oversights.

| Item | Decision | Revisit when |
|---|---|---|
| Hard dependency on PostgreSQL availability | Accepted; no graceful path if PG is down | Never for this project — documented in the failure matrix instead |
| Access tokens not instantly revocable (15-min window) | Accepted | Only if a security requirement demands it (ADR-009) |
| Network latency charged to the moving player | Accepted policy; no lag compensation | Out of scope (ADR-006) |
| Single-AZ RDS | Cost decision | Never for this project; multi-AZ is discussed, not deployed |
| Kubernetes primarily on `kind` | Cost decision (ADR-010) | EKS window in Phase 8 proves the manifests |
| Elo rather than Glicko-2 | Identical engineering value, less code | Never |
| JitPack is a build-time availability and mutability dependency | Accepted, scoped to one group (ADR-012). Dependency locking not yet applied. | Phase 6, with Renovate/Dependabot |
| Spring Boot 4.1 is a recent major; third-party lag is possible | Accepted. AWS SDK used directly to remove the highest-risk coupling. Falling back to 3.5 is **not** an option — it is EOL. | If a dependency blocks progress, replace the dependency, not the framework |
| No lag/anti-cheat detection | Out of scope | Never |
| Threefold repetition and fifty-move are automatic, not claimed | Simpler than FIDE's claim rule; no draw-claim protocol exists (ADR-015) | If draw offers/claims are ever built |
| First-move window is a constant (30 s), mirrored as a literal in V5 | No second value has been wanted (ADR-014) | When one is |
| No live countdown of the abort window in the UI | The server does not send the window's end; the hint text states the rule | Only if players miss it in practice |

---

## 6. Architectural decisions

Full reasoning in `docs/adr/`. Summary:

| # | Decision |
|---|---|
| 001 | Modular monolith; boundaries enforced by ArchUnit (really, since 4.1b); workers = same JAR, `worker` profile |
| 002 | `chesslib` behind a `ChessRules` port; verified with perft tests |
| 003 | Raw WebSocket + custom JSON envelope; Valkey Pub/Sub fanout; **not** STOMP |
| 004 | PostgreSQL is the only source of truth; Valkey holds nothing unrecoverable |
| 005 | Optimistic locking + idempotency keys + `PK(game_id, ply)`; **no distributed lock** |
| 006 | Clock is computed, never ticks; PostgreSQL `now()` is the sole time authority |
| 007 | Full snapshot on (re)connect; no delta replay |
| 008 | SQS Standard + outbox + `processed_events` dedupe; **not** Kafka, **not** FIFO |
| 009 | JWT access + rotating refresh; WebSocket auth in the first message, not the URL |
| 010 | ECS Fargate as the production path; EKS time-boxed; **no NAT Gateway** |
| 011 | Java 25 LTS + Spring Boot 4.1.1 + **Gradle 9.7.1** (9.8.0 since 2026-09-30); virtual threads, no WebFlux (SQS client amended by 020) |
| 012 | JitPack accepted for chesslib, scoped via `exclusiveContent` to one group |
| 013 | Refresh tokens rotate on every use; reuse of a spent token revokes the whole family |
| 014 | Games nobody started are aborted, never rated — through the same deadline, index and sweeper as timeouts |
| 015 | Threefold repetition: history from `moves.fen_after`, bounded by the halfmove clock; automatic draw |
| 016 | Matchmaking: Valkey queue + Lua pairing on every instance, no lock; idempotent re-seek as heartbeat; push with pull recovery |
| 017 | Rate limiting: Lua token bucket in Valkey (not Bucket4j); fail open |
| 018 | One instance-wide circuit (`ValkeyGuard`) for every degradable Valkey call; 5 s window |
| 019 | ElasticMQ, not LocalStack (now account-gated), as the SQS stand-in |
| 020 | Spring Cloud AWS 4.1.1 for SQS (amends 011's SDK-direct); template never creates queues |
| 021 | One image, three roles (API / worker / migrate); migrations a separate pre-rollout step |
| 022 | CI: PR gate on backend/frontend/image; Trivy before push; SHA-pinned actions; GHCR (ECR in P7) |

---

## 7. Infrastructure state

| Environment | Status | Notes |
|---|---|---|
| Local | Running on the dev machine, verified through 3.3 | `ops/docker/docker-compose.yml`: Postgres 16, Valkey 8. Schema at V5; 3.3 added no migration. |
| AWS — Path A (always-on demo) | Not provisioned | t3.small, planned Phase 7 |
| AWS — Path B (ECS reference) | Not provisioned | Terraform, planned Phase 7, **apply/destroy cycle only** |
| Kubernetes — `kind` | Not provisioned | Planned Phase 8 |
| Kubernetes — EKS | Not provisioned | Time-boxed window, Phase 8 |
| **AWS spend to date** | **$0.00** | Budget alarm not yet created — create it *before* the first `terraform apply` |

---

## 8. Setup requirements

See `SETUP.md`. Summary: any JDK 17+ to run Gradle (the Java 25 toolchain is provisioned
automatically), Docker + Compose, Node 20+ (frontend),
`awscli` and `terraform` (Phase 7 only), `kubectl` and `kind` (Phase 8 only).

---

## 9. Deployment state

Nothing is deployed. No AWS resources exist. No domain registered.

---

## 10. Next recommended tasks

1. **Phase 10 — hardening & docs (10–14 h; ~6.5–46 h left in the 135–175 budget).** Scope it
   first against ROADMAP's list: OWASP API Top 10 review, failure-matrix drills, docs/diagrams,
   resume bullets and explanations, demo video. Owner: MFA (deferred).
2. Candidates recorded, not built: hashing queue sized from measurement (§4); the from = to 500
   (§4); connection-pool size or shedding for moves (kind stress limit); relay poll → LISTEN/NOTIFY
   (1 s of rating latency); `MALLOC_ARENA_MAX` / more margin (11 % headroom under stress); 500
   concurrent sockets on Fargate (a ramp shorter than the play time).

---

## 11. Open questions for the project owner

Answered 2026-09-30 (ADR-023): existing account with a $20 budget alarm; `us-east-1`; no
domain — HTTP only; Path B only. None open.

---

## 12. Measurement ledger

**Every performance, cost, or scale number in this repository must have a row here.**
If it is not in this table, it is an estimate and must be labelled as one.

| Claim | Value | Measured on | Evidence |
|---|---|---|---|
| Concurrency invariant under repetition | 100 rounds × 16 contenders: exactly 1 winner per game, 0 failures, 1.49 s | Dev machine, Testcontainers PG 16, 2026-09-28 | `-Pchess.concurrency.rounds=100`; `concurrency.rounds=100` in the test report |
| kind: deploy from zero / cluster create | ~65 s (deps → migrate Job → api ×2 + worker) / ~70 s (images cached) | kind v0.33.0, k8s v1.37.0, dev machine, 2026-10-01 | `time k8s/deploy.sh`, `time k8s/cluster-up.sh` |
| kind: idle pod memory | api 372–375 Mi (limit 640 Mi), worker 358 Mi, postgres 60 Mi, elasticmq 68 Mi, valkey 9 Mi | Same | `kubectl top pods` |
| Browser flow through ingress-nginx (kind) | pair 0.79 s; rating shown 1.33 s after resignation | Same | `APP_URL=http://localhost npm run e2e:lobby` |
| Rolling restart, one live socket | closed 1001 with reconnect reason → live on a new pod 0.5 s later; ready endpoints ≥ 2 throughout (17 samples) | Same | node WebSocket probe + EndpointSlice polling |
| Baseline, live games (kind, 2 API pods) | 100 / 500 / 1,000 sockets: 28 / 139 / 279 moves/s; MOVE server p99 9.6 (cold) / 6.6 / 6.7 ms; all games consistent | kind, dev machine, 2026-10-01 | `docs/perf/baseline.md` |
| Stress, before → after memory budget | ~1,460 moves/s: before OOMKilled 4×/pod (grown heap), after 0 restarts at full heap; peak 908/1024 Mi; pool saturated in both (p99 ~380–410 ms) | Same | `docs/perf/optimisation-01.md` |
| Rolling deploy under live games (kind) | 40 games / 80 sockets, 4 API pods, `rollout restart` at 60 s: 40/40 games consistent, 0 clock anomalies, 116 × 1001 / 0 abnormal; moves p50/p95/p99 7/11/13 ms; reconnect p50/p95/p99 217/490/498 ms; rollout 42 s; k6 0.03 of 16 cores | kind, dev machine, 2026-10-01 | `docs/perf/2026-10-01-rolling-deploy-kind.md` |
| Crash (SIGKILL one API JVM) under live games | 20 games: 6 × 1006, 20/20 consistent, 0 clock anomalies; container ready again in 9 s | Same | same report |
| JVM clock skew has no effect on game timing | Application `Clock` +10 min: moves charged < 1 s, no expiry, REST clocks full | Dev machine, 2026-09-28 | `ClockSkewIntegrationTest` |
| Game end → rating shown to both players (browser) | 0.8 s (outbox → relay tick → ElasticMQ → worker → commit → push) | Headless Chromium, local, 2026-09-29 | `npm run e2e:lobby` |
| Backlog drain on worker start | 7 queued events rated within 80 ms of startup | Local, ElasticMQ, 2026-09-29 | bootRun log (DEVELOPMENT_LOG, 5.2) |
| Matchmaking pairing latency (browser) | Both players on the board 0.3–0.8 s after the second seek | Headless Chromium, local, 2026-09-28 | `npm run e2e:lobby` |
| Degraded play, Valkey paused (browser) — before ADR-018 | Healthy ~0.13 s; first outage move 4.8 s (mover) / 10.6 s (waiting opponent); steady state 0.8–3.9 s; after unpause < 0.1 s | Headless Chromium, local, fanout=valkey, 2026-09-28 | `npm run e2e:outage` (M4.3 commit) |
| Degraded play, Valkey paused (browser) — after ADR-018 | Healthy ~0.13 s; first outage move 1.9 s (mover) / 10.3 s (waiting opponent); steady state 0.9–2.0 s; recovery after the 5 s window, then ~0.13 s | Same | `npm run e2e:outage` |
| Degraded move visible via REST (server) | Worst 1,039 ms per move with Valkey paused | Testcontainers, 2026-09-28 | `ValkeyOutageIntegrationTest` prints `MEASURED` |
| Rate limiter cost during a Valkey outage | First move after Valkey stops: 1,037 ms (= 1 s Redis command timeout); later moves within 5 s skip Valkey (circuit) | Testcontainers, 2026-09-28 | `ValkeyFanoutIntegrationTest.survivesValkeyOutage` prints `MEASURED` |
| `terraform apply` from zero (first attempt) | 11 m 59 s to the migrate step; RDS 8 m 43 s, ElastiCache 4 m 41 s | AWS us-east-1, 2026-10-01 | owner's terminal output (DEVELOPMENT_LOG 7.5) |
| `terraform destroy` | 10 m 16 s (ElastiCache 3 m 18 s); a second run finished the rest | AWS us-east-1, 2026-10-01 | owner's terminal output |
| Flyway, all 7 migrations against RDS over TLS | 0.9 s | RDS db.t4g.micro, 2026-10-01 | migrate task log |
| App start on Fargate (to "Started") | api 0.5 vCPU: 60–75 s (4 starts); worker 0.25 vCPU: ~122 s (2); migrate 0.25 vCPU: 98–113 s (2) | ECS Fargate x86, 2026-10-01 | CloudWatch Logs, `Started ChessPlatformApplication` |
| First PostgreSQL TLS connection from a 0.25-vCPU task | ~4 s (HikariPool start → first connection) | Same | migrate task log |
| Pairing latency (browser → ALB, from Hyderabad) | 4.8 s, both players on the board after the second seek | Headless Chromium → us-east-1 ALB, 2026-10-01 | `APP_URL=… npm run e2e:lobby` |
| Game end → rating shown to both players (AWS) | 3.35 s (outbox → relay → SQS → worker on Spot → Valkey → API → browser) | Same | `npm run e2e:lobby` against the ALB |
| ALB request distribution across the two API tasks | 25 / 25 over 30 min (one task per AZ) | CloudWatch `RequestCount` by AZ, 2026-10-01 | `get-metric-statistics` |
| Fargate: sign-up burst, before → after the bounded hashing pool | 50 games, 30 s ramp: before 30/50 games, 15/100 sign-ups failed, pool `waiting=11`, 265 WARN/ERROR lines; after 50/50, 0 error lines, 6 shed (503) and retried; API CPU 100 % in both | ECS Fargate, 2 × 0.5 vCPU API, 2026-10-06 | `docs/perf/optimisation-02.md` |
| Fargate: 500 sign-ups at ~0.8/s, ~50 concurrent games | 250/250 games consistent, 0 HTTP failures (HTTP p99 1.3 s), move ack p50/p95/p99 11/73/120 ms, API CPU max 76 % | Same | same report |
| Carrier starvation by bcrypt (local experiment, 1 CPU) | 8 × bcrypt-12 burst: unrelated requests late p50 1,854–1,910 ms on virtual threads vs 0 ms on one platform thread; 0 ms with 16 carriers | Dev machine, JDK 25, `taskset` 1 CPU, 2026-10-06 | `loadtest/experiments/CarrierStarvation.java` |
| JVM processors on a 0.5-vCPU Fargate task | 2 (`availableProcessors`) | ECS Fargate x86, 2026-10-06 | API startup log `Password hashing: … JVM sees 2 processor(s)` |
| bcrypt-12 cost on Fargate | ~0.9 vCPU-s per hash (**estimate**: ~100 hashes ≈ 90 s of 2 × 0.5 vCPU saturated); laptop core 0.42 s (measured) | Same | optimisation-02, CloudWatch CPU |
| AWS cost, 2026-10-01 (UTC day) | $0.20 total — the 7.5 deploy session **and** 9.4 session 1, full stack several hours | Cost Explorer, read 2026-10-06 | `aws ce get-cost-and-usage --granularity DAILY` |
| Clock correct across a server kill | `kill -9` + cold restart; side to move lost 31,769 ms over 31,798 ms wall time (Δ −29 ms); other side 0 ms | Dev machine, local profile, 2026-09-28 | Scripted WebSocket snapshots before/after (DEVELOPMENT_LOG 2026-09-28, M3.3) |

Not yet measured: the bill for 2026-10-06 (9.4 session 2) — not posted when written; read it and
add a row. Estimates currently in the repository, all clearly labelled as such: AWS monthly costs
(`ARCHITECTURE.md` §12.1, ADR-010), phase hour budgets (`ROADMAP.md`), the 1,000-connection
target (`ARCHITECTURE.md` §2 — a *target*, not a result).
