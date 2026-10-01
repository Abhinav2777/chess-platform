# Architecture Decision Records

Format: Context → Decision → Alternatives considered → Consequences → Interview angle.

An ADR is immutable once accepted. If a decision changes, write a new ADR that
supersedes the old one and mark the old one `Superseded by ADR-NNN`.

| # | Decision | Status |
|---|---|---|
| 001 | Modular monolith over microservices | Accepted |
| 002 | Use `chesslib` rather than writing a rules engine | Accepted |
| 003 | Raw WebSocket + custom protocol instead of STOMP | Accepted |
| 004 | PostgreSQL is the sole source of truth | Accepted |
| 005 | Optimistic locking + idempotency keys, not distributed locks | Accepted |
| 006 | Computed (non-ticking) server-authoritative clock | Accepted |
| 007 | Snapshot-on-reconnect instead of event replay | Accepted |
| 008 | SQS Standard + idempotent consumers, not Kafka or FIFO | Accepted |
| 009 | JWT access + rotating refresh; WebSocket first-message auth | Accepted |
| 010 | ECS Fargate as the production path; EKS time-boxed | Accepted |
| 011 | Java 25 + Spring Boot 4.1.1 + Gradle 9.7.1 | Accepted |
| 012 | Accept JitPack, scoped to one group, for chesslib | Accepted |
| 013 | Refresh token rotation with family-based reuse detection | Accepted |
| 014 | Abort games nobody started, through the timeout machinery | Accepted |
| 015 | Threefold repetition from the persisted move log, applied automatically | Accepted |
| 016 | Matchmaking: Valkey queue, Lua pairing, push with pull recovery | Accepted |
| 017 | Rate limiting: Lua token bucket in Valkey, fail open behind a circuit | Accepted |
| 018 | One instance-wide circuit for every degradable Valkey call | Accepted |
| 019 | ElasticMQ, not LocalStack, as the SQS stand-in | Accepted |
| 020 | Spring Cloud AWS 4.x for SQS (amends 011) | Accepted |
| 021 | One container image, three roles; migrations as a separate step | Accepted |
| 022 | CI: PR gate, scan before push, SHA-pinned actions, GHCR | Accepted |
| 023 | AWS deployment: one origin, HTTP behind an allowlist, create-measure-destroy | Accepted |
| 024 | Kubernetes on kind (Kustomize); graceful WebSocket drain | Accepted |
| 025 | A time-boxed frontend UI pass (dark, lichess-like; no UI libraries) | Accepted |
