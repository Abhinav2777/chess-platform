# Optimisation 01 — a memory budget that holds its own worst case

**Date:** 2026-10-01 · **Phase:** 9.3 · **Scenario:** `loadtest/games.js` via `loadtest/baseline.sh`
with `THINK_MIN_MS=100 THINK_MAX_MS=300 PLAY_SECONDS=90`, 500 games (1,000 sockets)
· **Environment:** as `docs/perf/baseline.md` (kind on one 16-core laptop; 2 API pods fixed, no
CPU limit; k6 on the same host — 0.74 of 16 cores, 445 MiB peak).

## Before

The baseline (`baseline.md`) found no bottleneck at human think times, with two limits close:
HikariCP 9 of 10, pod memory 594 of 640 Mi. A stress run cut think time to 0.1–0.3 s
(**~1,460 moves/s** over 1,000 sockets) to find what breaks first.

**Run 21:16 (pods that had already run three baseline levels):**

```
21:16:44  pool saturated  active 10/10, pending 16 / 36
21:16:55                  pending 159 / 127, CPU 5.7 / 5.0 cores, memory 625 / 582 Mi
21:17:01  both API pods OOMKilled (exit 137), 3 s apart → total outage → restart → reconnect herd
21:18:03  saturated again (pending 63 / 52) → killed again …   4 restarts per pod in ~5 minutes
```

Exit 137 / `OOMKilled` = the **cgroup** limit, not a Java `OutOfMemoryError` (the JVM runs with
`ExitOnOutOfMemoryError`, which would exit differently).

## The bottleneck

Two, in sequence:

1. **The connection pool saturates first** (10 per pod): moves queue for a connection — 131–170
   pending at peak, MOVE p99 ~400 ms. With virtual threads nothing else bounds that queue.
2. **Memory turns saturation into an outage.** Measured per API JVM: non-heap **212 Mi**, heap max
   **371 Mi** (60 % of 640). Heap max + non-heap = 583 Mi before thread stacks, GC structures and
   malloc — the 640 Mi container cannot hold the JVM's worst case. Whether it dies depends on
   history: G1 grows the committed heap under load and rarely returns it.
   **Run 21:50**, identical stress on freshly restarted pods: heap committed only ~190 Mi, **no
   restart** (pool saturated: pending 170, p99 413 ms). Same config, opposite outcome — a latent
   failure.

**Made deterministic:** the same old budget with the heap committed and touched at startup
(`InitialRAMPercentage=60 -XX:+AlwaysPreTouch`) — **every migrate attempt OOMKilled (5/5, 512 Mi),
the API pod OOMKilled at startup** (exit 137). (`maxUnavailable: 0` kept the old pods serving; the
migrate gate stopped the rollout.) The 60 % chosen in 8.2 had assumed ~250 Mi for everything but
the heap; measurement says otherwise.

## The change

Size the container from the measured footprint, and make the worst case the normal case:

| | Before | After |
|---|---|---|
| API / worker memory limit | 640 Mi | **1 Gi** |
| Migrate Job limit | 512 Mi | **768 Mi** |
| Heap | `MaxRAMPercentage=60` (grows on demand) | **`MaxRAMPercentage=50` `InitialRAMPercentage=50` `-XX:+AlwaysPreTouch`** — committed and touched at start |
| Image default | 75 % | 50 % |
| ECS task definitions (1,024 MB) | image default 75 % → 768 + 212 + native > 1,024 | same JVM options as kind (not yet applied) |

Pre-touching means the pod's steady-state memory **is** its worst case: a budget too small fails at
deploy, visibly, instead of months later under a burst.

## After

**Run 22:20**, identical stress, new budget:

| | Before (21:16, grown heaps) | Before (21:50, fresh heaps) | **After (22:20, heap pre-touched)** |
|---|---|---|---|
| API restarts in the window | 4 per pod (OOMKilled) | 0 | **0** |
| Heap committed max | grew toward 371 Mi | 183 / 193 Mi | **495 / 495 Mi** (the whole heap, from start) |
| Non-heap max | — | 212 Mi | 211 / 212 Mi |
| Container memory peak | 625 Mi, then killed | 596 Mi | **907 / 908 Mi of 1,024** |
| Moves/s | — (outage) | 1,463.6 | 1,455.3 |
| MOVE server p50 / p95 / p99 (ms) | — | 2.80 / 166.06 / 413.49 | 2.78 / 162.83 / 377.82 |
| Hikari pending max / mean wait | 159 / — | 170 / 20.4 ms | 131 / 18.9 ms |
| Games consistent / clock anomalies | not completed | 500 / 0 | **500 / 0** |

## What this shows — and what it does not

- **Shown:** a pod running at its worst-case memory survives the stress that crashed the old pods,
  with zero restarts; the old budget cannot even start at its worst case. The outage mode is removed
  by construction, not by luck.
- **Not a throughput gain**, and not claimed as one: moves/s and latency are unchanged because the
  connection pool is still saturated at ~1,460 moves/s. That is the **next limit** (pool size, or
  shedding load when saturated) — deliberately a separate change, so this one stays attributable.
- **Headroom is thinner than predicted:** 908 of 1,024 Mi (~11 %), not the ~20 % estimated — native
  memory grew under load (up to 98 threads on one pod; glibc malloc arenas). Holds, but a production
  setting would add margin (e.g. 1.25 Gi) or cap native growth (`MALLOC_ARENA_MAX=2`). Recorded.
- One laptop; CPU uncapped; cluster and generator share the host (see `baseline.md` limits).

## Raw output

`loadtest/results/baseline-20261001-211628-500g.*` (the crash: samples only — k6 was stopped),
`…-215043-500g.*` (before, fresh), `…-222017-500g.*` (after) — gitignored.
