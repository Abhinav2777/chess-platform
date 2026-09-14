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
