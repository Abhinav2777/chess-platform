# Failure drills — what happens to live games when a dependency fails

**Date:** 2026-10-06 · **Phase:** 10.2 · **Harness:** `loadtest/failure-drill.sh` (k6 `games.js` +
fault injection + a 2-second timeline) · **Test:** `DatabaseOutageIntegrationTest`

## Environment and method

| | |
|---|---|
| Cluster | kind v0.33.0, one node, Kubernetes v1.37.0, on the development laptop (as `docs/perf/baseline.md`) |
| Image | `main` at `1776711` (from ECR), then the same image with this milestone's fixes layered on (`10.2-fix`) |
| API | **2 pods, fixed** — the HPA removed for the drills (it had scaled to 4 on startup CPU, and could change mid-drill); readiness `readinessState,db` every 5 s, 2 failures; liveness = the JVM only |
| Data | PostgreSQL 16, Valkey 8, ElasticMQ — in-cluster, single pods |
| Load | 20 games (40 sockets), 0.8–2.0 s think time, k6 on the same host. Every game resigns at its deadline, so every game produces a rating event |
| Fault | injected 60 s into the run; the timeline records ready API endpoints, what a **new** visitor gets (`GET /` and `/api/users/me` through ingress), the outbox backlog, rating events processed, and restarts |
| Checked after every run | every move a player saw acknowledged is stored, at its ply, unchanged, and nothing else (k6 per game); clocks never went up; WebSocket closes |

The k6 player behaves like the browser after a refused move (shows it, chooses again —
`useGame.ts`); before this milestone it waited forever, which would have measured k6.

**How the faults were injected — and one that did not do what it said.** PostgreSQL's data in kind
is an `emptyDir`: a container restart keeps it, deleting the pod destroys it, so PostgreSQL is
never scaled down. *Crash:* `SIGQUIT` to the postmaster (immediate shutdown), kubelet restarts the
container, WAL recovery. *Freeze:* `SIGSTOP` to every PostgreSQL process **from the kind node**.
The first attempt sent it from inside the container, where the postmaster is PID 1 — and Linux
ignores `SIGSTOP` sent to a namespace's init from inside it. That froze only the backends; the
postmaster went on accepting connections. Noticed because the sampler's own `psql` kept answering
during a "freeze" (`NSpid: 15751 1` confirmed it). The queue is frozen the same way rather than
deleted: ElasticMQ is in-memory and the app creates its queues only at startup, so a deleted pod
would come back without queues — a failure SQS does not have.

## Results

| Drill | Games consistent | Clock anomalies | Abnormal closes | Moves refused | Longest move wait | What changed for players |
|---|---|---|---|---|---|---|
| Control (no fault) | 20 / 20 | 0 | 0 | 0 | 72 ms | — |
| **PostgreSQL crash** (immediate shutdown → restart) | 20 / 20 | 0 | 0 | 4 | 1.69 s | Database back in < 4 s; readiness never flipped; the 4 moves in flight refused, players chose again |
| **PostgreSQL frozen 30 s** — before the fixes | 20 / 20 | 0 | 0 | 127, all `INTERNAL` | **33.5 s** | See below |
| **PostgreSQL frozen 30 s** — after the fixes | 20 / 20 | 0 | 0 | 155, all `SERVICE_UNAVAILABLE` | **2.9 s** | See below |
| **Queue (SQS) frozen 60 s** | 20 / 20 | 0 | 0 | 0 | — | None. 20 rating events queued in the outbox; **all published and rated within 3 s** of the queue returning |
| **Worker down 60 s** | 20 / 20 | 0 | 0 | 0 | — | None. 20 events queued; **drained 14 s** after the worker was scaled up (most of it JVM start) |

No API pod restarted in any drill: liveness checks the JVM only, so a database outage cannot
become a restart storm.

### PostgreSQL frozen — the timeline

```
t=60   every PostgreSQL process frozen
t≈66–71  both API pods unready (readiness includes db; 2 failed probes, 5 s apart)
t≈78   ingress: 503 for everything — the SPA too. A new visitor cannot load the page.
t≈90   unfrozen
t≈100  pods ready again (one probe period after the database answers)
```

Players already in a game **kept their sockets** — ingress-nginx does not close established
connections when a Service loses its endpoints (0 abnormal closes). Their moves were refused while
the database was gone; afterwards they played on. Nothing acknowledged was lost.

## What the drills found, and what was changed

**1. Nothing bounded a query's wait on an unresponsive database.** pgjdbc's socket timeout defaults
to forever. In the freeze a move on a pooled connection waited 33.5 s — the whole freeze. A
*silent network partition* (a failover that leaves connections black-holed) would hang requests
for TCP's retransmission timeout, about fifteen minutes.
→ `socketTimeout=10` s on the application's connections (the longest real query is milliseconds);
none for the migrate role, where DDL may legitimately run long. **After: longest wait 2.9 s.**

**2. A database outage looked like a bug.** Every refused move reached the player as `INTERNAL`
("Something went wrong") and was logged at ERROR as unhandled — 127 lines for a 30 s freeze, each
one a page if ERROR is alerted on. The failure matrix claimed "moves rejected with 503"; over the
WebSocket there was no such thing.
→ `DatabaseUnavailable` recognises the outage from JDBC's signals (SQLState class 08, the pool's
timeout, a socket timeout) wherever they sit in the cause chain, and both transports answer it the
same way: `SERVICE_UNAVAILABLE` — REST 503 with `Retry-After: 2` — logged at WARN. **After: 155
refusals, all `SERVICE_UNAVAILABLE`; ERROR lines 127 → 1.** The one left is Spring's own
"Application exception overridden by rollback exception": once per transaction caught mid-query,
from the framework's logger.

The integration test found a case the drill hid: when a query loses its connection *inside* a
transaction, the rollback then fails on the evicted connection, and Spring throws the
**rollback's** exception — the original (SQLState 08006) is gone from the chain, and HikariCP's
"Connection is closed" carries no SQLState. Recognised by its exact text, pinned by the test (an
upgrade that changes it fails the test); the cost — a bug that reuses a closed connection would read
as 503 — is written in the code.

**3. Trade-off kept, with its numbers: `db` in readiness.** A shared database outage makes every
pod unready at once: ingress answers 503 to new visitors (SPA included) for the outage plus one
probe period (~10 s) on recovery. Removing `db` would keep the SPA loading and the API answering its
own typed 503s, and recover instantly. Kept because of what it protects: a newly rolled-out version
that cannot reach the database (a broken secret, a wrong URL) never receives traffic — the rollout
stalls on readiness (`maxUnavailable: 0`) instead of replacing healthy pods. Deploy safety over a
friendlier error page during an outage that serves nothing useful anyway. Revisit if the SPA should
stay loadable during a database outage (serve it from a CDN/S3, which also removes it from this
question).

**Observed, not changed: the outbox relay holds a connection across the send.** In the queue drill,
leak detection fired on the relay's thread: it keeps its transaction — and the row locks that stop
another relay double-sending — open while waiting on SQS. Bounded by the SDK's read timeout
(~30 s), on the worker's own pool, one thread. An explicit SDK call timeout would shorten it.

## Not drilled

Valkey down (4.3, in a browser) and an API pod killed / a rolling deploy under live games (8.3) were
drilled earlier — `ARCHITECTURE.md` §13 points to them. Not drilled: a whole availability zone
(single-AZ RDS by cost decision); RDS Multi-AZ failover (not deployed); network partition between
pods (socket-timeout behaviour is covered by the test, not by a partition on kind).

## Raw output

`loadtest/results/drill-20261006-*` (gitignored): `*.k6.log`, `*.k6.json`, `*.timeline.tsv`,
`*.api.log`, `*.summary.txt`. Control `230908`, crash `231301`, freeze before `232201` (the
in-container `SIGSTOP` attempt is `231735`), queue `232624`, worker `232944`, freeze after `234345`.
