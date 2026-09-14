# ROADMAP

Revised at Phase 0 after reconciling the specification's phase budgets against its
deadlines. Where this document differs from `MASTER_PROJECT_SPEC.md`, the difference is
deliberate and explained in § "Deviations from the spec".

Budget: **12–16 weeks · 10–12 h/week · 120–190 hours total.**

---

## The arithmetic

The spec's per-phase budgets sum to **128–180 hours**, which fits the 120–190 envelope.
That is the good news. The bad news is what the sum omits.

**The budget contains no learning time.** For an engineer with 2.5 years of experience
who has not previously written Terraform or Kubernetes manifests, the "must understand"
work in Phases 7 and 8 is real hours that the estimates don't hold. Realistically the
spec's *low* figures are optimistic by roughly 30–40 % once learning, debugging, and
rework are included. Treated honestly this is a **170–240 hour plan** presented as a
128–180 hour one.

Three structural changes close the gap without cutting P0 scope:

1. **Observability becomes cross-cutting, not a phase.** (See § Deviations.)
2. **Kubernetes runs on `kind`, with EKS time-boxed.** (ADR-010.) This removes the
   duplicate-deployment tax between Phases 7 and 8.
3. **Phase 9's original scope shrinks** because logging and metrics were built in
   Phase 1, leaving Phase 9 to do only tracing, dashboards, and measurement.

Revised total: **135–175 hours.** Still tight. The buffer is Phase 8 and the P2 list.

---

## Deviations from the spec

| # | Spec says | We do | Why |
|---|---|---|---|
| 1 | Observability is Phase 9 (weeks 13+) | Structured logging + Micrometer metrics land in Phase 1; Phase 9 does tracing, dashboards, load testing | The spec's own HARD PORTFOLIO DEADLINE requires observability by week 12, which Phase 9 cannot satisfy. It also says "design proper observability from early stages". Self-contradiction; resolved in favour of the earlier requirement, which is better engineering anyway. |
| 2 | Phase 7 AWS, then Phase 8 Kubernetes | ECS Fargate in 7; `kind` in 8 with a 2–3 day EKS window | Deploying twice costs ~15 hours and ~$73/month for an EKS control plane. See ADR-010. |
| 3 | "Stage 4 — Game Service → SQS → Rating/Notification workers" implies separate services | Workers are the same JAR under a `worker` profile, deployed separately | The spec elsewhere mandates a modular monolith. Contradiction resolved toward the explicit instruction. ADR-001. |
| 4 | Terraform provisions "monitoring" | Terraform provisions infrastructure; Prometheus/Grafana come from Helm, CloudWatch dashboards from committed JSON | Terraforming dashboards is low-value time. |
| 5 | Ratings unspecified | Elo, not Glicko-2 | Glicko-2 is ~150 lines more for identical engineering value. Nobody asks about it. |
| 6 | Frontend unbudgeted | Hard cap **10 hours across the whole project**, `react-chessboard` | The single most likely source of silent scope creep. |

---

## Phases

Legend: **MU** = must understand · **MI** = must implement · **SKIP** = deliberately not built.

---

### Phase 0 — Architecture & Setup · 3–5 h · Week 1

**Target:** a defensible design, a repository skeleton, and a Spring Boot app that boots
against real Postgres and Valkey containers.

**MI:** repo structure · `docker-compose.yml` (Postgres 16 + Valkey 8) · Gradle build
(Kotlin DSL + version catalog + Java toolchain) · Spring Boot skeleton · Flyway wired ·
`/actuator/health` green · ArchUnit boundary test · CI workflow · all Phase-0 docs and ADRs.

**SKIP:** any domain code, any AWS, any frontend.

> **Version check is a task, not an assumption.** Spring Boot 3.x is entirely EOL as of
> June 2026 and 4.0.x support ends in December 2026, so **4.1.x** is the target. Confirm
> the exact patch against `docs.spring.io`, and generate a reference project at
> `start.spring.io` to verify starter coordinates — Boot 4 modularised the codebase and
> artifact names cannot be assumed. Boot 4 also removed all 3.x deprecations, so most
> Spring tutorials and Stack Overflow answers are now wrong for this project. Read the
> reference docs. This costs hours; they are budgeted.

**Done when:** `docker compose up` + `./gradlew :backend:bootRun` yields a health check
reporting Postgres and Valkey UP; `./gradlew :backend:check` passes locally and in CI;
Flyway has applied V1; ADRs 001–011 written.

---

### Phase 1 — Core Chess MVP · 16–20 h · Weeks 1–3

**Target:** two authenticated users can play a complete, legal, persisted chess game via
REST. No real-time yet — moves are polled.

**MU:** Spring Security filter chain internals · bcrypt cost and why · JWT claims and
validation pitfalls · JPA `@Version` semantics · Flyway migration discipline ·
keyset vs offset pagination.

**MI:** `identity` (register, login, refresh, `/me`) · `chess` rules port + chesslib
adapter + **perft tests** · `game` (create, load, submit move, resign) · schema V1 ·
`POST /api/games/{id}/moves` with idempotency key · centralised exception handling with
RFC 7807 problem details · structured JSON logging with MDC · Micrometer metrics ·
Testcontainers integration tests.

**SKIP:** WebSockets · matchmaking (games are created by explicit invite) · ratings ·
clocks (added Phase 3) · any UI beyond a board that submits moves.

**Done when:** an integration test plays a full scholar's-mate game end-to-end through
the API; perft matches published node counts to depth 4; illegal moves, out-of-turn
moves, and moves in finished games all return correct 4xx codes with problem details;
a retried move with the same `clientMoveId` returns the original result.

---

### Phase 2 — Real-Time Multiplayer · 15–18 h · Weeks 3–5

**Target:** two browsers, live moves, survives disconnection.

**MU:** WebSocket handshake and why headers are unavailable · Valkey Pub/Sub semantics
and its lack of delivery guarantees · why publishing inside a transaction is a bug ·
backpressure on a slow WebSocket consumer.

**MI:** `WebSocketHandler` + envelope + protocol (ARCHITECTURE.md §5) · first-message
auth with timeout and unauthenticated cap · per-game subscription registry · Valkey
Pub/Sub fanout with subscribe/unsubscribe on first/last local subscriber ·
`GAME_SNAPSHOT` on subscribe · `@TransactionalEventListener(AFTER_COMMIT)` publish ·
heartbeat/ping · React client with `react-chessboard` (**≤ 6 h**) · two-client
integration test including reconnect mid-game.

**SKIP:** STOMP · SockJS fallback · delta replay · spectators.

**Done when:** two browsers play a full game with moves appearing in under ~200 ms
locally; killing one API instance mid-game and reconnecting restores correct state;
an integration test asserts the snapshot after reconnect matches the pre-disconnect
position.

> **This is the HARD MVP DEADLINE (spec: week 6).** Cumulative: 34–43 h. On track.

---

### Phase 3 — Concurrency, Clock & Reliability · 16–20 h · Weeks 5–7

**Target:** the backend becomes technically robust rather than merely functional. Per
the spec, this phase outranks new features.

**MU:** optimistic vs pessimistic locking trade-offs · why Redlock is not the answer here
(ADR-005) · `FOR UPDATE SKIP LOCKED` · transaction isolation levels · idempotency as a
protocol property, not a code trick.

**MI:** `ClockCalculator` as a pure function + exhaustive unit tests · clock columns and
`turn_deadline` · Postgres `now()` as the sole time authority · timeout sweeper with
`SKIP LOCKED` · full `OptimisticLockException` handling with re-validation ·
`expectedPly` stale-client rejection with automatic resync · **the concurrency
integration test** (N threads, one ply, exactly one commit) · `chess_move_conflicts_total`
and `chess_move_idempotent_replays_total` metrics · abandonment abort for pre-move games.

**SKIP:** lag compensation · draw offers/takebacks (P2).

**Done when:** the concurrency test passes reliably over 100 runs; a game survives
`docker kill` of the API mid-move with correct clocks on reconnect; deliberately
skewing a pod's system clock by 5 seconds does not affect any game's timing.

---

### Phase 4 — Valkey + Matchmaking · 12–16 h · Weeks 7–8

**MU:** Valkey ZSET operations · Lua script atomicity and why it replaces a lock ·
TTL and eviction policies · cache invalidation as a correctness question.

**MI:** matchmaking ZSET per time control · **Lua atomic pairing script** · rating-window
expansion by wait time · dedupe guard · stale-entry TTL handling · presence keys ·
`game:{id}:state` read cache with explicit invalidation · Bucket4j rate limiting via
Valkey · **a test that runs the full game flow with Valkey stopped** and asserts moves
still commit.

**SKIP:** sophisticated skill-based matchmaking · queue priority · party/friend games.

**Done when:** 20 simulated players are paired correctly with no duplicates and no lost
entries; `docker stop valkey` degrades to polling without any move being lost.

---

### Phase 5 — Async Processing · 10–14 h · Weeks 8–10

**MU:** at-least-once delivery · transactional outbox and the window it closes ·
DLQ redrive · why `rating += delta` is not idempotent.

**MI:** `outbox` table + relay · SQS Standard queue + DLQ · `GameFinished` event ·
Elo consumer · `processed_events` dedupe table · worker Spring profile ·
LocalStack Testcontainers tests including **deliberate duplicate delivery** and DLQ
routing · queue-depth and DLQ metrics.

**SKIP:** Kafka · SQS FIFO · notification delivery beyond a persisted row · analytics.

**Done when:** delivering the same `GameFinished` twice moves a rating exactly once;
a consumer that throws three times lands the message in the DLQ; ratings survive a
worker crash mid-processing.

---

### Phase 6 — Docker + CI/CD · 8–10 h · Week 10

**MI:** Renovate or Dependabot on `libs.versions.toml` (~30 min — closes the gap that
produced three wrong version pins in Phase 0) · multi-stage Dockerfile with a JRE base
and a non-root user · `.dockerignore` ·
compose profiles for dev/test · GitHub Actions: build → unit → integration
(Testcontainers) → Trivy image scan → push to ECR → tag · branch protection ·
Flyway migration step separated from deploy.

**SKIP:** GitOps/ArgoCD · multi-arch builds unless free.

**Done when:** a push to `main` produces a scanned, tagged image in ECR with zero manual
steps, and the pipeline fails on a deliberately introduced failing test.

> **HARD PORTFOLIO DEADLINE approaches (spec: week 12).** Cumulative: 80–103 h.

---

### Phase 7 — AWS Deployment · 14–18 h · Weeks 11–12

**MU:** VPC subnet/route-table design · why NAT Gateway costs more than the compute ·
security groups vs NACLs · IAM task roles vs instance roles · RDS parameter groups ·
ALB target groups and WebSocket support · Terraform state and workspaces.

**MI:** Terraform for VPC (public + private, **no NAT**), ALB, ECS Fargate service +
task defs (api and worker), RDS Postgres, ElastiCache Valkey, SQS + DLQ, ECR, Secrets
Manager, CloudWatch log groups, budget alarm at $20 · remote state in S3 with DynamoDB
locking · Path A always-on demo on t3.small · a `terraform destroy` checklist.

**SKIP:** multi-AZ RDS (Optional — cost) · CloudFront · Route 53 unless a domain is
already owned · WAF · multi-region.

**Done when:** `terraform apply` from zero produces a working public HTTPS + WSS
deployment; `terraform destroy` leaves no billable resources; DEPLOYMENT.md is accurate
enough for a stranger to follow.

---

### Phase 8 — Kubernetes · 12–16 h · Weeks 13–14

**MU:** readiness vs liveness (different endpoints, different meanings) · requests vs
limits and CPU throttling · HPA metrics pipeline · PodDisruptionBudget · the SIGTERM →
readiness-fail → drain sequence.

**MI:** Deployment, Service, Ingress, ConfigMap, Secret · readiness probe that checks
dependencies, liveness probe that checks only liveness · resource requests/limits ·
HPA · `RollingUpdate` with `maxUnavailable: 0` · PDB · **graceful WebSocket shutdown**
(fail readiness → `GOING_AWAY` close frames with reconnect hint → drain → exit) ·
run on `kind` · EKS window with ALB Ingress Controller and IRSA, then destroy.

**SKIP:** service mesh · operators · Helm charts beyond consuming them · cluster
autoscaler · multi-cluster.

**Done when:** a rolling deploy under live games causes reconnects but zero lost games
or corrupted clocks — demonstrated with a k6 run across the deploy.

---

### Phase 9 — Tracing, Load Testing & Optimisation · 10–14 h · Weeks 14–15

**MU:** trace context propagation across async boundaries · p50/p95/p99 and why means
lie · how to tell whether the load generator is the bottleneck.

**MI:** OpenTelemetry agent + OTLP · **trace context injected into SQS message
attributes** so traces span the queue · Grafana dashboards · k6 WebSocket scenarios at
100 / 500 / 1,000 connections · establish a baseline · find the real bottleneck (expected:
HikariCP pool / RDS `max_connections`) · make **one measured optimisation** and record
`before → bottleneck → change → after` in `docs/perf/`.

**SKIP:** synthetic monitoring · log aggregation beyond CloudWatch · alerting beyond
DLQ depth and error rate.

**Done when:** `docs/perf/baseline.md` and `docs/perf/optimisation-01.md` exist with real
k6 output, stated instance types, and stated dates. **No number appears anywhere in this
repository that is not backed by one of these files.**

---

### Phase 10 — Hardening & Documentation · 10–14 h · Weeks 15–16

**MI:** security review against the OWASP API Top 10 · verified failure-matrix drills
(actually stop each dependency and record what happened) · all docs finalised · Mermaid
diagrams committed · `INTERVIEW_NOTES.md` completed · 3 resume bullets · 30-second /
2-minute / 10-minute explanations · recorded demo video.

**Done when:** a stranger can clone, run locally, understand the architecture, and find
the reasoning for every major decision without asking a question.

---

## Cumulative schedule

| Phase | Hours | Cumulative | Target week |
|---|---|---|---|
| 0 | 3–5 | 3–5 | 1 |
| 1 | 16–20 | 19–25 | 3 |
| 2 | 15–18 | 34–43 | **5 (MVP)** |
| 3 | 16–20 | 50–63 | 7 |
| 4 | 12–16 | 62–79 | 8 |
| 5 | 10–14 | 72–93 | 10 |
| 6 | 8–10 | 80–103 | 10 |
| 7 | 14–18 | 94–121 | **12 (portfolio-ready)** |
| 8 | 12–16 | 106–137 | 14 |
| 9 | 10–14 | 116–151 | 15 |
| 10 | 10–14 | 126–165 | 16 |

**Cut order if behind schedule** — cut from the bottom, never from Phase 3:

1. Phase 8 EKS window (keep `kind`) — saves 4 h and ~$20
2. Phase 9 tracing (keep metrics + load tests) — saves 4 h
3. Phase 5 notification consumer (keep rating) — saves 3 h
4. Phase 8 entirely (document the manifests, don't run them) — saves 12 h

Phases 1–3 are never cut. A project with excellent concurrency handling and no
Kubernetes is a far stronger interview asset than the reverse.

---

## Scope priority

**P0 (must):** Spring Boot backend · React frontend · PostgreSQL · auth · legal chess ·
multiplayer · WebSockets · server-authoritative state · server-authoritative clock ·
persistence · concurrency protection · idempotency · Docker · AWS deployment ·
automated tests · logging + metrics · documentation.

**P1 (should):** Valkey · matchmaking · ratings · SQS · async processing · CI/CD ·
Kubernetes · load testing · tracing · rate limiting · graceful reconnect.

**P2 (only with time left):** leaderboards · draw offers · game analysis · player stats ·
spectators.

**P3 (do not build):** tournaments · notifications beyond a DB row · mobile apps ·
Kafka · service mesh · multi-region · Stockfish analysis · chat · payments · anything
in the spec's OUT OF SCOPE list.

---

## The feature-request test

Any new idea must answer six questions before it is built. If it fails three or more,
the answer is **"Do not build this yet. It has low portfolio ROI."**

1. Does it deepen backend engineering?
2. Does it demonstrate distributed-systems knowledge?
3. Does it improve cloud understanding?
4. Does it create an interesting interview conversation?
5. Does it materially improve the portfolio?
6. How many hours — and what gets cut to pay for them?
