# ADR-024: Kubernetes on kind, and a graceful WebSocket drain

**Status:** Accepted — Phase 8 complete (8.1–8.3) ·
**Date:** 2026-10-01
**Builds on:** ADR-007 (snapshot recovery), ADR-009 (socket auth), ADR-016 (seeks), ADR-021
(one image, three roles), ADR-023 (the ECS deployment this mirrors).

## Context

Phase 8's done-when: *a rolling deploy under live games causes reconnects but zero lost games or
corrupted clocks — demonstrated with a k6 run across the deploy.*

Decisions (2026-10-01): **kind only** — no EKS window (it would mostly repeat what ECS
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

## 8.2 — The cluster and the manifests

**Layout** (`k8s/`): `kind-cluster.yaml` (one node, v1.37.0 pinned); `addons/` (ingress-nginx
controller-v1.15.1 and metrics-server v0.9.0, vendored); `base/` (the app); `deps/` (in-cluster
PostgreSQL, Valkey, ElasticMQ — kind only); `overlays/kind/`; `cluster-up.sh`, `deploy.sh`.

**Decisions:**
- **Probes on the management port** (new `k8s` profile, as `aws`): startup 30 × 5 s (sized from the
  60–122 s JVM starts measured on Fargate), liveness = livenessState only, readiness =
  readinessState + db.
- **preStop sleep 10 s** (native `sleep` action): endpoint removal and SIGTERM start together;
  without the pause the JVM could stop accepting while the ingress still routes to it.
  `terminationGracePeriodSeconds: 45` covers sleep + drain + graceful shutdown.
- **CPU request, no CPU limit; memory limit = request** (640 Mi). CFS throttling stalls a JVM's
  GC/JIT bursts while the node idles. Heap at 60 % of the limit (the image's 75 % left too little
  for metaspace, code cache, stacks, direct buffers).
- **`maxUnavailable: 0, maxSurge: 1`**, PDB `minAvailable: 1`, HPA on CPU 70 % (2–4) with a 5-min
  scale-down window — scaling down *is* a shutdown that reconnects every socket on the pod. CPU is a
  weak signal for a socket server; connections per pod (already exported) needs a custom-metrics
  adapter — Phase 9 discussion.
- **Migrate Job gates the rollout** (`deploy.sh`): delete the old Job (immutable template), apply
  everything but the `chess.dev/gated` Deployments, wait for Complete (stop on Failed), then roll.
  On first deploy the Job's first pod failed — PostgreSQL still starting — and the back-off retry
  completed, as designed.
- **Hardened pods:** non-root 10001, read-only root FS (`/tmp` emptyDir), no privilege escalation,
  all capabilities dropped, RuntimeDefault seccomp.
- **Generated ConfigMap/Secret** (content-hashed names): a config change rolls the pods using it.
  The kind Secret holds development values, committed on purpose and labelled; a real cluster gets
  it from a secret manager.
- **Images preloaded** with `kind load` (this machine's containers have no egress); add-on images
  pulled by digest on the host, then tagged — the pin moves to the load step.

**Found while building:**
1. **The drain never reached the probe.** `group.readiness.include: db,redis` *replaces* the
   default member `readinessState`, so SocketDrain's REFUSING_TRAFFIC left the endpoint at 200.
   8.1's test had checked the in-memory state. Now asserted over HTTP (503 while draining).
2. **Readiness included Valkey** — contradicting ADR-018. A Valkey outage would have made every
   instance unready together: 503 for everything from the ALB or ingress. Asserted over HTTP (200
   with Valkey paused). Both apply to the ECS deployment as well.
3. **403 on the SPA's own script on kind** (host :8000 → node :80): the forwarded port was the
   proxy's, not the browser's. Fixed at the proxy layer (80 → 80); an app-side change was tried,
   reverted — Tomcat derives the port from the scheme once `X-Forwarded-Proto` is set.

**Verified on kind:** deploy from zero ~1 min (cluster ~1–2 min); browser game flow through
ingress-nginx green (pair 0.79 s, rating push 1.33 s); `/actuator` not routed; probes `UP` with no
details; idle memory api 372–375 Mi of 640 Mi, worker 358 Mi. **Rolling restart with a live socket:**
closed `1001 "Server restarting, please reconnect"`, live again on a new pod 0.5 s later; ready
endpoints never below 2 (17 samples).

## 8.3 — The demonstration

`loadtest/rolling-deploy.js` (k6): 40 real games (80 sockets) playing random legal moves, players
that reconnect like the browser and re-send an in-flight move with its idempotency key; at 60 s
`kubectl rollout restart`; every game then checked move-by-move against the server, clocks checked
live. **Result: 40/40 games consistent, 0 clock anomalies, 116 × 1001 and 0 abnormal closes,
moves p99 13 ms, reconnect p99 498 ms (the client's jitter), k6 at 0.03 of 16 cores.** Report:
`docs/perf/2026-10-01-rolling-deploy-kind.md`.

**Contrast, a crash (SIGKILL):** 6 × 1006, still 20/20 games consistent. Correctness rests on
PostgreSQL commits, idempotency keys and snapshot resync; the drain makes the handoff clean.
`kubectl delete --grace-period=0 --force` turned out not to be a crash (the kubelet still sends
SIGTERM).

**Recorded honestly:** the re-send path was never exercised (no move in flight at a cut); the HPA
had scaled to 4 pods before the measured run; cluster and generator share one host.

## Questions this decision raises

**Q:** "What happens to open WebSockets when you deploy?"
**A:** Spring's graceful shutdown doesn't cover them — an upgraded socket isn't an in-flight
request — so at first every client got an abnormal 1006 when Tomcat stopped. I added a lifecycle
step that runs before the web server shuts down: readiness goes false, every socket gets a 1001
"going away, reconnect", and new arrivals are bounced. The client already reconnects with jittered
backoff and resyncs from a snapshot, so a game survives; I verify it under load in Phase 8.3.
