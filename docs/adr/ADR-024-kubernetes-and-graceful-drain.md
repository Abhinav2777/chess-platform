# ADR-024: Kubernetes on kind, and a graceful WebSocket drain

**Status:** Accepted for 8.1 (the drain); sections for 8.2–8.3 are added as they are built ·
**Date:** 2026-10-01
**Builds on:** ADR-007 (snapshot recovery), ADR-009 (socket auth), ADR-016 (seeks), ADR-021
(one image, three roles), ADR-023 (the ECS deployment this mirrors).

## Context

Phase 8's done-when: *a rolling deploy under live games causes reconnects but zero lost games or
corrupted clocks — demonstrated with a k6 run across the deploy.*

Owner's decisions (2026-10-01): **kind only** — no EKS window (it would mostly repeat what ECS
proved in Phase 7; IRSA is the task-role idea again; the ~4–6 h go to Phases 9–10) — and
**Kustomize** for manifests (built into kubectl; the roadmap skips authoring Helm charts).

## 8.1 — The drain

**Measured first:** against a no-op stub, stopping the application closed every client socket with
**1006** — no close frame. Spring Boot's graceful shutdown waits for in-flight HTTP requests; an
upgraded WebSocket is not one, so sockets died when Tomcat stopped, after the rest of the
application had begun shutting down. Readiness never changed.

**Decision:** `SocketDrain`, a `SmartLifecycle` at `DEFAULT_PHASE - 512` — it stops before Boot's
web-server graceful shutdown (`- 1024`) and the server stop (`- 2048`), while Tomcat, the pool and
Valkey are still up:

1. readiness → `REFUSING_TRAFFIC`;
2. every open socket closed with **1001 GOING_AWAY**, reason "Server restarting, please reconnect";
3. while draining, a newly arriving socket is closed the same way at once, and closing a socket does
   **not** cancel the player's seek (a deploy is not the player leaving; their re-seek finds the
   entry with its join time — ADR-016).

**The client needed no change.** Its reconnect (Phase 2) already uses full jitter — first retry
uniformly in 0–500 ms, doubling, capped at 30 s — and resyncs from a snapshot (ADR-007). The
missing half was entirely on the server.

**Tests** (`GracefulShutdownIntegrationTest`): the drain called directly (readiness, 1001 + reason
on every socket, seek kept, latecomer bounced) and the real lifecycle stop (clients get *our* 1001,
not Tomcat's cut). Mutation-checked: drain phase after the web server → 1006 again; seek guard
removed → the seek is cancelled.

**What it does not do:** stagger the closes server-side (client jitter spreads the reconnects), or
resubmit a move that was in flight — the client never resends automatically (a move chosen against
a stale board must not be replayed); the snapshot shows the truth and the player moves again.

## Interview angle

**Q:** "What happens to open WebSockets when you deploy?"
**A:** Spring's graceful shutdown doesn't cover them — an upgraded socket isn't an in-flight
request — so at first every client got an abnormal 1006 when Tomcat stopped. I added a lifecycle
step that runs before the web server shuts down: readiness goes false, every socket gets a 1001
"going away, reconnect", and new arrivals are bounced. The client already reconnects with jittered
backoff and resyncs from a snapshot, so a game survives; I verify it under load in Phase 8.3.
