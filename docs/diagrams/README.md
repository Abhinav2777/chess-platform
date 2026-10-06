# Diagrams

Mermaid, rendered by GitHub. Each one describes the system **as built**, checked against the
code in Phase 10.3; the section it belongs to in `ARCHITECTURE.md` is named under it.

1. [The system](#1-the-system)
2. [A move, across two pods](#2-a-move-across-two-pods)
3. [A game ends: the rating, asynchronously](#3-a-game-ends-the-rating-asynchronously)
4. [The AWS deployment](#4-the-aws-deployment)
5. [A rolling deploy under live games](#5-a-rolling-deploy-under-live-games)

---

## 1. The system

One image, three roles. PostgreSQL holds everything that matters; Valkey holds only what can be
rebuilt; the queue carries only what may arrive late.

```mermaid
flowchart LR
    B["Browser — React SPA<br/>hand-written board, no chess logic"]
    E["Edge: ALB (AWS) / ingress-nginx (kind)"]

    subgraph API["API role — N replicas"]
        REST[REST controllers]
        WS["WebSocket handler<br/>first-frame auth"]
        GAME["game: move pipeline,<br/>computed clock"]
        MM["matchmaking:<br/>Lua pairing"]
        SW1[timeout sweeper]
    end

    subgraph WRK["Worker role — same image"]
        RELAY["outbox relay"]
        RATE["rating consumer"]
        SW2[timeout sweeper]
    end

    MIG["Migrate role — Flyway,<br/>before each rollout"]

    PG[("PostgreSQL — the only source of truth")]
    VK[("Valkey — fanout · presence · seek queue · rate limits")]
    SQS[["SQS game-events + DLQ"]]

    B -->|REST| E
    B <-->|WebSocket| E
    E --> REST
    E <--> WS
    REST --> GAME
    WS --> GAME
    WS --> MM
    GAME -->|one transaction per move| PG
    MM --> VK
    GAME -->|publish after commit| VK
    VK -.->|pub/sub| WS
    SW1 --> PG
    SW2 --> PG
    RELAY --> PG
    RELAY --> SQS
    SQS --> RATE
    RATE --> PG
    RATE -->|RATING_UPDATED| VK
    MIG --> PG
```

`ARCHITECTURE.md` §3 · ADR-001 (modular monolith), ADR-004 (PostgreSQL the source of truth),
ADR-021 (one image, three roles).

---

## 2. A move, across two pods

The players are usually on different pods. The mover learns the outcome from the same broadcast
as the opponent, never from a private reply — both see identical state.

```mermaid
sequenceDiagram
    participant W as White (browser)
    participant A as API pod A
    participant PG as PostgreSQL
    participant V as Valkey
    participant Bp as API pod B
    participant Bk as Black (browser)

    W->>A: MOVE {gameId, clientMoveId, expectedPly, from, to}
    A->>V: rate limit (token bucket, Lua)
    A->>PG: BEGIN
    A->>PG: move with this clientMoveId already stored?
    alt a retry
        PG-->>A: yes
        A-->>W: the original result (idempotent replay)
    else a new move
        A->>A: clock: mover flagged? (now() from PostgreSQL)
        A->>A: legal in this position? (chesslib, behind ChessRules)
        A->>PG: INSERT move (PK game_id, ply) · UPDATE game (version + 1)
        A->>PG: COMMIT
        Note over A,PG: a concurrent writer loses on the version → CONFLICT → client resyncs
        A->>V: PUBLISH game:{id} MOVE_MADE (after commit only)
        V-->>A: MOVE_MADE
        V-->>Bp: MOVE_MADE
        A-->>W: MOVE_MADE
        Bp-->>Bk: MOVE_MADE
    end
```

Three layers make "exactly one move per ply" true without a lock: the idempotency key, the
version column, the primary key. `ARCHITECTURE.md` §5, §7 · ADR-005, ADR-006.

---

## 3. A game ends: the rating, asynchronously

The event is written in the game's own transaction, so a finished game without its event cannot
exist. Delivery is at-least-once; the effect is exactly-once.

```mermaid
sequenceDiagram
    participant G as API (game transaction)
    participant PG as PostgreSQL
    participant R as Worker: relay
    participant Q as SQS
    participant C as Worker: rating consumer
    participant V as Valkey
    participant P as Players' pods

    G->>PG: final move / resignation + INSERT outbox row (same transaction)
    loop every second
        R->>PG: SELECT unpublished outbox rows FOR UPDATE SKIP LOCKED
        R->>Q: SendMessageBatch (traceparent as an attribute)
        R->>PG: mark published, COMMIT
    end
    Q->>C: message (at least once)
    C->>PG: BEGIN · INSERT processed_events ON CONFLICT DO NOTHING
    alt already processed (a duplicate delivery)
        PG-->>C: 0 rows inserted — stop, nothing applied
    else first delivery
        C->>PG: lock both players (id order) · Elo · rating_history · COMMIT
        C->>V: PUBLISH RATING_UPDATED
        V-->>P: RATING_UPDATED → both browsers
    end
    C->>Q: acknowledge (only after the commit)
```

Measured: queue or worker down for 60 s → games unaffected, events waited in the outbox, rated
within 3 s / 14 s of recovery (`docs/failure-drills.md`). `ARCHITECTURE.md` §9 · ADR-008,
ADR-020, ADR-026.

---

## 4. The AWS deployment

No NAT gateway: tasks reach AWS APIs over the internet gateway from public subnets, and nothing
reaches them inbound except the load balancer. Applied for sessions, then destroyed.

```mermaid
graph TB
    U["Allowlisted addresses"]
    GH["GitHub Actions<br/>(OIDC, no stored keys)"]

    subgraph VPC["VPC 10.0.0.0/16 — us-east-1, two AZs"]
        subgraph PUB["Public subnets"]
            ALB["ALB :80<br/>(HTTP — no domain, ADR-023)"]
            API1["api task<br/>0.5 vCPU / 1 GB"]
            API2["api task<br/>0.5 vCPU / 1 GB"]
            WK["worker task<br/>Fargate Spot"]
            MG["migrate task<br/>one-off, before rollout"]
        end
        subgraph ISO["Isolated subnets — no route out"]
            RDS[("RDS PostgreSQL 16<br/>db.t4g.micro, TLS forced")]
            EC[("ElastiCache Valkey<br/>cache.t4g.micro, TLS")]
        end
    end

    AWS["SQS + DLQ · Secrets Manager · ECR · CloudWatch Logs"]

    U -->|HTTP / WebSocket| ALB
    ALB --> API1
    ALB --> API2
    API1 --> RDS
    API2 --> RDS
    WK --> RDS
    MG --> RDS
    API1 --> EC
    API2 --> EC
    WK --> EC
    API1 -. "443 via internet gateway" .-> AWS
    WK -. "443 via internet gateway" .-> AWS
    GH -->|push image| AWS
```

Security groups reference each other (ALB → api; api, worker → RDS and Valkey). Only the
worker's task role can touch the queue; the API has no AWS permissions. `ARCHITECTURE.md` §12 ·
ADR-010, ADR-023 · `docs/security-review.md`.

---

## 5. A rolling deploy under live games

No game state lives in a pod, so a pod can leave mid-game: it stops taking new sockets, tells
every client to reconnect, and the reconnect lands on a pod that reads the same database.

```mermaid
sequenceDiagram
    participant K as Kubernetes
    participant Old as Old pod
    participant New as New pod
    participant C as Player's browser
    participant PG as PostgreSQL

    K->>New: start (migrate Job already succeeded)
    New-->>K: ready (startup + readiness probes)
    K->>Old: SIGTERM (after preStop sleep 10 s — endpoints update first)
    Old->>Old: readiness → REFUSING_TRAFFIC
    Old->>C: close 1001 GOING_AWAY "reconnect"
    Old->>Old: refuse new sockets, finish in-flight requests, exit
    C->>New: reconnect (exponential backoff, full jitter) · AUTH · SUBSCRIBE
    New->>PG: load game
    New-->>C: GAME_SNAPSHOT — the same position and clocks
    Note over C,New: a move sent before the cut is re-sent with the same clientMoveId — applied once
```

Measured on kind: 40 live games through a rolling restart — 40/40 consistent, 116 sockets moved
with 1001, 0 abnormal closes, reconnect p99 498 ms (`docs/perf/2026-10-01-rolling-deploy-kind.md`).
`ARCHITECTURE.md` §12.2 · ADR-024.
