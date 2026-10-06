# Optimisation 02 — password hashing that cannot stop the rest of the server

**Dates:** 2026-10-01 and 2026-10-06 · **Phase:** 9.4 (the AWS session) · **Scenario:**
`loadtest/games.js` run as a one-off ECS task by `loadtest/aws-loadtest.sh` · **Experiment:**
`loadtest/experiments/CarrierStarvation.java`

## Environment

| | |
|---|---|
| Region / platform | AWS us-east-1, ECS Fargate, x86_64 |
| API | **2 tasks × 0.5 vCPU / 1 GB**; heap 50 % committed and touched at start (optimisation-01); HikariCP max 10 per task |
| Worker | 1 task × 0.25 vCPU / 1 GB, Fargate Spot |
| Data | RDS PostgreSQL 16 `db.t4g.micro` (single AZ); ElastiCache Valkey `cache.t4g.micro`; SQS |
| Ingress | ALB, one listener (HTTP — ADR-023) |
| Load generator | One-off Fargate task, **1 vCPU / 2 GB**, `grafana/k6:2.3.0` pinned by digest, inside the VPC, aimed at the ALB's **private** address. Its CPU and memory were **not measured** (no Container Insights). For scale only: on the laptop, k6 used 0.18 cores for 1,000 sockets (baseline.md). |
| Rate limits | Raised for these sessions only (`relaxed_auth_rate_limits`): every k6 user registers from one address |
| Server metrics | CloudWatch, 1-minute resolution — coarser than the kind scraper |

The scenario is the baseline's: two players per game, random legal moves, 0.8–2.0 s think time, a
10-minute clock. Each player **registers** (bcrypt, cost 12) right before its game. On a laptop
that cost was invisible. On 0.5 vCPU it turned out to be the dominant cost.

## Session 1 (2026-10-01, image `c5c9517`) — bcrypt inside a transaction

No valid gameplay data. Every level failed in the first seconds: registration timed out waiting for
a connection (`HikariPool-1 - Connection is not available, request timed out after 5480ms
(total=10, active=10…)` on `POST /api/auth/register`). Games without players followed: the 50-game
run logged **7,754 sockets closed with 1008** and `INTERNAL` errors.

**Cause:** `AuthenticationService.register` and `login` were `@Transactional`. A JPA transaction
takes its pooled connection when it begins, so the connection sat idle for the whole hash. At
about 3 registrations a second, 10 connections were all held by hashes.

**Change (`08fe129`):** hash first, then a short transaction. The lookup in `login` is its own short
read, and `matches()` runs with nothing held. `PasswordHashingConnectionIntegrationTest` records,
at the moment of hashing, that no transaction is open and no connection is checked out. It was
red before the change (mutation-checked).

## Session 2, run 1 (2026-10-06 19:06, image `d792560`) — still failing, for a different reason

50 games, 30 s ramp:

| | |
|---|---|
| Registrations failed / games not created | 15 of 100 / 20 of 50 |
| HTTP request duration p50 / p99 / max | 5.9 s / 25.1 s / 27.5 s |
| API CPU, the minute of the ramp | 91 % average, **100 % max** |
| Pool timeouts on one task | `total=10, active=10, idle=0, waiting=11` — on `register`, `POST /api/games`, `SUBSCRIBE` |
| Hikari's own wait | **4,795 ms against a configured `connection-timeout` of 3,000 ms** |
| Games verified | 30 of 50 (0 inconsistent, 0 clock anomalies) |

No hash held a connection any more, yet the pool emptied. The clue is the timeout overshoot: a
thread waiting at most 3 s for a connection woke up after 4.8 s. **Threads were not being
scheduled.**

### The mechanism

Requests run on virtual threads, which run on a few *carrier* threads, one per processor the JVM
sees. A virtual thread gives up its carrier only when it blocks. bcrypt never blocks: it is a few
hundred milliseconds of pure CPU. While hashes occupy every carrier, nothing else on the instance
runs. A request that holds a connection sends its SQL, and when the reply arrives it is runnable
again. But it waits behind every queued hash, **with the connection still checked out.** Moving
the hash out of the transaction fixed holding a connection *during* a hash. It did not fix holding
one *behind* a hash.

### Measured, locally

`CarrierStarvation.java`: every 20 ms a "request" arrives and waits 5 ms for a "database reply". The
measure is how late it finishes, counted **from its arrival**. Meanwhile a burst of 8 real bcrypt-12
hashes runs. The JVM is pinned to one CPU (`taskset`, `-XX:ActiveProcessorCount=1`). Two runs each:

| Where the hashes run | Lateness p50 | p95 | p99 | max |
|---|---|---|---|---|
| Virtual threads, 1 carrier | **1,854 / 1,910 ms** | 3,210 / 3,262 | 3,328 / 3,382 | 3,350 / 3,407 |
| One platform thread, 1 CPU | **0 / 0 ms** | 0 / 0 | 2 / 3 | 18 / 23 |
| Virtual threads, 16 carriers (the laptop) | 0 ms | 0 | 0 | 3 |

The last row is why the kind baseline never showed it. The first version of the experiment
started its clock *inside* the probe, so it missed the wait for a carrier to start and reported
~5 ms. Measuring from arrival is what exposed the effect. Recorded because it is an easy mistake.

## The change (PR #29, image `5f3bfef`)

`BoundedPasswordEncoder` wraps the `PasswordEncoder` bean, so registration, login and the dummy hash
for unknown users all go through it unchanged:

- Hashes run on **platform threads**, one per processor by default (`chess.auth.hashing.threads`).
  The OS preempts platform threads, so the request carriers keep getting CPU during a burst. With
  `n` hashing threads beside `c` carriers, hashing can take at most about `n/(n+c)` of the CPU.
- A **bounded queue** (`chess.auth.hashing.queue-capacity`, 16). When it is full the request is
  refused at once: **503 `SERVER_BUSY` with `Retry-After: 1`**. The refusal depends only on the
  queue, not on whether the account exists, so it does not reopen the user-enumeration timing
  oracle.
- Executor metrics (`executor.queued`, `executor.idle`, `executor`), a rejection counter, and a
  startup line giving the processor count the JVM sees.

Shipped with it, both found in the same logs:
- **The harness:** a game that could not start looped `AUTH` → `AUTH_FAILED` → reconnect at about
  48 sockets/s (14,405 sessions for 100 players). Such games now count in `games_not_started` and
  open no sockets, and a 503 is retried after its `Retry-After`.
- **WebSocket payload validation:** a `SUBSCRIBE` without a game id opened a read transaction and
  then failed, logged at ERROR as unhandled. Payloads are now validated against the REST bodies'
  constraints before any transaction starts.

## After (2026-10-06, image `5f3bfef`)

| | Before (19:06, 50 games) | **After (20:28, 50 games)** | **After (20:36, 250 games)** |
|---|---|---|---|
| Ramp / play | 30 s / 120 s | 30 s / 120 s | **600 s** / 120 s |
| Peak concurrent games (sockets) | 50 (100) | 50 (100) | **≈50 (≈100)** — see below |
| Games verified / not started | 30 / 20 | **50 / 0** | **250 / 0** |
| Inconsistent / clock anomalies | 0 / 0 | **0 / 0** | **0 / 0** |
| Abnormal closes / moves rejected | 14,320 / 127 | **0 / 0** | **0 / 0** |
| API WARN/ERROR log lines | 265 | **0** | **0** |
| Requests shed (503, then retried) | — | 6 | 0 |
| HTTP failed | 35 of 180 | 6 of 206 (the 503s) | **0 of 1,000** |
| HTTP p50 / p95 / p99 / max | 5.9 s / 21.7 s / 25.1 s / 27.5 s | 0.96 s / 23.1 s / 25.4 s / 27.2 s | **0.34 / 0.95 / 1.30 / 2.04 s** |
| Moves/s | 7.3 | 20.4 | 29.3 |
| Move ack p50 / p95 / p99 / max (ms) | 15 / 278 / 898 / 12,214 | 20 / 467 / 1,243 / 2,763 | **11 / 73 / 120 / 440** |
| API CPU, max 1-min | 100 % | 100 % (ramp), 31 % average after | **76 %** |
| API memory, max | — | 81 % | 83 % (pre-touched heap, as designed) |
| RDS CPU / connections, max | — | 9.5 % / 21 | 9.6 % / 21 |
| Healthy targets, min | — | 2 | 2 |

**The startup line corrected a claim:** `Password hashing: 2 platform thread(s), queue 16; JVM sees
2 processor(s)`. I had predicted **1** for a 0.5-vCPU task. Fargate shows the JVM 2 processors and
limits the task's CPU in a way the JVM does not see; the exact mechanism was not checked. So the
burst monopolised 2 carriers, not 1. The mechanism is the same, and the JVM is sized for more CPU
than it gets.

## What this shows — and what it does not

- **Shown:** on Fargate, a burst of sign-ups no longer takes the service down. The same 50-game
  burst went from 20 games failing, pool timeouts and 265 error lines to 50/50 games and 0 error
  lines, with the CPU still at 100 %. The CPU is still saturated, but nothing else fails. Load
  shedding worked (6 refusals, all retried successfully). Correctness held throughout: 0
  inconsistent games, 0 clock anomalies, in every run including the failing ones.
- **The 250-game run was not 500 concurrent sockets.** With a 600 s ramp and 120 s of play, games
  finished long before the last one started: about 250 × 120 / 600 ≈ **50 concurrent games**. It
  shows 500 sign-ups at about 0.8/s with zero failures, at the same concurrency as the 50-game run.
  A run with play longer than the ramp would have measured 500 concurrent sockets. **The owner chose
  to stop there** (cost and time). **Fargate is measured to ~100 concurrent sockets; the
  1,000-socket figure exists only for kind** (`baseline.md`).
- **The hashing queue is too deep for this hardware.** In the 50-game run, registrations waited up
  to 27 s. Estimate, not a direct measurement: about 100 hashes kept 2 × 0.5 vCPU saturated for
  about 90 s, so roughly **0.9 vCPU-seconds per bcrypt-12 hash** (0.42 s on one laptop core). That
  is about 0.5 hashes/s per task, and a queue of 16 means a wait of about 30 s. For a ~5 s budget,
  2–4 per task. Not changed in this session: one variable per run.
- **Sign-up capacity is about 1 per second** for the whole deployment (estimate, same arithmetic).
  bcrypt, not chess, sets the limit at this size. At larger scale the options are bigger tasks,
  autoscaling on CPU, or authentication as its own service. Lowering the cost factor would trade
  password security for a load test, and is not an option.
- **Move latency during the ramp** (p99 1.2 s in the 50-game run) is CPU contention with hashing,
  bounded by the pool. After the ramp, 31 % average CPU. The 250-game run at ~0.8 sign-ups/s:
  p99 120 ms.

## Raw output

`loadtest/results/aws-20261001-23*` (session 1: logs only; the summaries were lost to a pagination
bug, since fixed), `aws-20261006-190654-50g.txt` (before; the k6 summary is in this report, read
from CloudWatch), `aws-20261006-202813-50g.*`, `aws-20261006-203608-250g.*` (after) — gitignored.
