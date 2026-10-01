# Rolling deploy under live games — kind (Phase 8.3)

**Date:** 2026-10-01 · **Scenario:** `loadtest/rolling-deploy.js` via `loadtest/rolling-deploy.sh`
· **Claim tested:** a rolling deploy under live games causes reconnects but zero lost games or
corrupted clocks (ROADMAP Phase 8 done-when; ADR-024).

## Environment

| | |
|---|---|
| Host (server **and** load generator — the same machine) | Intel Core 7 240H, 16 logical CPUs, 15 GB RAM, Linux 7.2 |
| Cluster | kind v0.33.0, one node, Kubernetes v1.37.0 |
| API pods | **4** at start and end of the measured run (HPA min 2 / max 4 had scaled up earlier in the session); each 250m CPU request, no CPU limit, 640 Mi memory |
| Worker / data | 1 worker pod; PostgreSQL 16, Valkey 8, ElasticMQ in-cluster (single pods) |
| Ingress | ingress-nginx controller-v1.15.1, `http://localhost` |
| Image | `chess-platform:8.2-dev` (built from `feat/8.2-kind-manifests`) |
| Load generator | k6 v2.1.0, same host: **0.03 cores average of 16 (4.7 CPU-s over 185 s), peak RSS 75 MiB** — not the bottleneck |

## What the scenario does

40 VUs, one game each: two players with their own WebSockets (80 sockets, 80 users), random legal
moves every 0.8–2.0 s, 600+0 time control. On any close a player reconnects after 0–500 ms of
jitter (as the browser does), re-authenticates, resubscribes and resyncs from `GAME_SNAPSHOT`;
a move in flight is re-sent with the same `clientMoveId`. At 60 s the wrapper runs
`kubectl rollout restart deployment/api`. After 180 s each game is checked against
`GET /api/games/{id}`: every move either player saw acknowledged must be stored at that ply,
unchanged, with nothing beyond it. Clocks are checked live: with no increment they may never rise.

## Results — rolling restart (run 2026-10-01 18:41, the reported run)

| Metric | Value |
|---|---|
| Games verified consistent / inconsistent | **40 / 0** |
| Clock anomalies | **0** |
| Moves acknowledged | 5,023 (27.4/s) |
| Move round trip (MOVE → own MOVE_MADE) | p50 **7 ms** · p95 **11 ms** · p99 **13 ms** · max 66 ms |
| Socket closes with 1001 GOING_AWAY / abnormal | 116 / **0** |
| Reconnect (close → AUTH_OK on a new pod) | p50 217 ms · p95 490 ms · p99 498 ms · max 502 ms |
| Rollout duration (4 pods, one at a time) | 42 s |
| Moves rejected | 0 |

116 closes for 80 sockets: a socket drained from the first old pod can reconnect to an old pod not
yet replaced and be drained again — inherent to replacing one pod at a time; it costs a second
reconnect, never a move. Reconnect time is dominated by the client's deliberate jitter (uniform
0–500 ms, mean 250 ms).

## Contrast — a crash instead of a deploy (18:37, 20 games, 120 s)

SIGKILL to one API JVM from the node (no SIGTERM, no drain; container restarted in place, ready
again in 9 s): **6 abnormal closes (1006)**, games verified **20 / 0 inconsistent**, clock anomalies
**0**. k6 thresholds failed on the abnormal closes, as intended. Correctness does not depend on the
drain: moves commit in PostgreSQL before they are acknowledged, and clients resync from the server.
The drain buys a clean, signalled handoff — readiness off, 1001, seeks kept.

(`kubectl delete pod --grace-period=0 --force` was tried first and is **not** a crash: the kubelet
still sent SIGTERM, the drain ran, and all 7 closes were 1001.)

## Limits — what this does not show

- **The idempotent re-send path was not exercised**: `moves_resent` stayed at 0 in every run — no
  move was in flight at the instant of a cut. Exactly-once moves are covered by the Phase 3
  integration tests, not by this run.
- One host runs both cluster and load generator; numbers are for this machine, not a capacity claim.
- 40 games is the done-when's demonstration, not a load test. Capacity is Phase 9.
- The HPA scaled 2 → 4 before the reported run; whether load or post-start JIT drove it was not
  separated (CPU target 70 % of a 250m request = 175m). Phase 9.

## Other runs in the session

| Run | Setup | Result |
|---|---|---|
| 18:31 control | 8 games, 30 s, no disruption | 8/8 verified, 174 moves, ack p95 37 ms |
| 18:31 rolling | 40 games, 180 s, rollout at 60 s (4 pods; p99 not yet recorded) | 40/40 verified, 4,939 moves, 114 × 1001, 0 abnormal, reconnect p95 473 ms |
| 18:35 force delete | 20 games, `--grace-period=0 --force` | 20/20 verified, 7 × 1001, 0 abnormal (not a crash) |
| 18:37 crash | 20 games, SIGKILL | 20/20 verified, 6 × 1006 |
