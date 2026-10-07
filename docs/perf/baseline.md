# Baseline — live games at 100 / 500 / 1,000 WebSocket connections (kind)

**Date:** 2026-10-01 · **Scenario:** `loadtest/games.js` driven by `loadtest/baseline.sh`
(server metrics: `loadtest/server-metrics.py`) · **Image:** `chess-platform:9.2-b` (main @ 9bb98c1
+ 9.2 changes) · **Phase:** 9.2

## Environment

| | |
|---|---|
| Host — server **and** load generator | Intel Core 7 240H, 16 logical CPUs, 15 GB RAM, Linux 7.2 |
| Cluster | kind v0.33.0, one node, Kubernetes v1.37.0 |
| API | **2 pods, fixed** (HPA removed for the run); each 250m CPU request, **no CPU limit**, 640 Mi memory; HikariCP max 10 per pod |
| Worker / data | 1 worker; PostgreSQL 16, Valkey 8, ElasticMQ in-cluster, single pods, no limits on CPU |
| Ingress | ingress-nginx controller-v1.15.1, `http://localhost` |
| Tracing | spans created at 100 % sampling, **not exported** (no OTLP endpoint configured) |
| Load generator | k6 v2.1.0 on the same host — headroom per level below |

## Method

Each level runs N games (2 players, 2 sockets each) of random legal moves with 0.8–2.0 s think
time; VU starts are spread over 30 s, then each game plays 120 s. Every game is checked against the
server afterwards (every acknowledged move stored, in order, nothing extra) and clocks are checked
live. Server-side latency comes from the `chess.ws.message` histogram (one Observation per
WebSocket frame, ADR-026): bucket deltas between snapshots before and after the window, summed over
both pods — the window's own distribution, not a lifetime average. Pool and CPU are sampled every
5 s. Levels ran in the order 50 → 250 → 500 → 50 games; the final 50-game run repeats the first on
warm JVMs.

## Results

| Sockets (games) | Moves/s | k6 MOVE ack p50 / p95 / p99 / max (ms) | Server MOVE p50 / p95 / p99 (ms) | Hikari active max / pending max / mean wait | API CPU avg / max per pod | API memory max | k6 CPU / RSS | Games consistent |
|---|---|---|---|---|---|---|---|---|
| 100 (50) — cold | 28.4 | 7 / 9 / 11 / 18 | 5.69 / 8.18 / 9.58 | 1 / 0 / 0.032 ms | 0.19 / 0.52 cores | 495 Mi | 0.02 cores / 84 MiB | 50 / 50 |
| 500 (250) | 139.3 | 4 / 6 / 7 / 108 | 3.68 / 5.46 / 6.62 | 3 / 0 / 0.008 ms | 0.64 / 2.37 cores | 548 Mi | 0.09 cores / 212 MiB | 250 / 250 |
| **1,000 (500)** | **279.2** | **4 / 6 / 7 / 37** | **2.99 / 5.12 / 6.65** | **9 of 10 / 0 / 0.011 ms** | 1.18 / 5.34 cores | **594 Mi of 640** | 0.18 cores / 377 MiB | 500 / 500 |
| 100 (50) — warm repeat | 28.2 | 5 / 7 / 8 / 28 | 4.35 / 6.34 / 7.10 | 1 / 0 / 0.033 ms | 0.13 / 0.38 cores | 597 Mi | 0.02 cores / 81 MiB | 50 / 50 |

Every level: 0 inconsistent games, 0 clock anomalies, 0 rejected moves, 0 abnormal closes.
Client ack ≈ server time + ~1–2 ms (network and client on the same host).

## Reading it

- **No bottleneck at 1,000 connections and ~280 moves/s on this host.** Latency stayed flat
  (server p99 ≈ 6.6 ms at every level); nothing queued.
- **Two limits are close, and they are the next questions:**
  - **Connection pool:** active connections peaked at **9 of 10** per pod at 1,000 sockets, with
    nothing pending yet. One more step of load and moves start waiting for a connection — the
    roadmap's expected bottleneck, not yet reached.
  - **Pod memory:** **594 Mi of 640 Mi** (93 %), and it did not fall back afterwards (the warm
    repeat shows 597 Mi). More connections, or a burst, risk the OOM killer.
- **Latency was lower at higher load, even warm** (server p50 4.35 ms at 100 sockets warm vs
  2.99 ms at 1,000). The cold first run explains part (JIT warm-up), but not the warm repeat.
  Consistent with a lightly loaded laptop CPU sitting in low-power states — observed, **not
  separated**. Comparisons across levels on this host should be read with that in mind.

## Limits

- One laptop runs cluster, dependencies and k6; CPU has no container limits, so pods borrow idle
  cores (peaks of 5 cores per API pod). These are numbers for this machine, not a capacity claim for
  the architecture. The AWS session (9.4) measures the same scenario on fixed Fargate CPU.
- The move rate is set by human-like think time (0.8–2.0 s). Saturation is the subject of 9.3.
- Tracing overhead is included (spans created, not exported); exporting adds a batch processor.

## Raw output

`loadtest/results/baseline-20261001-21*` (gitignored): k6 logs and summary JSON, server snapshots
before/after, 5-second samples, per-level summaries.
