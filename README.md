# Chess Platform

A production-grade real-time multiplayer chess platform. Java 21 · Spring Boot ·
PostgreSQL · Valkey · WebSockets · AWS · Docker · Kubernetes.

The interesting engineering here is not chess. It is making a turn-based mutation of
shared state correct under concurrency, network failure, pod death, and rolling
deployment — with a clock that decides outcomes and therefore cannot be wrong.

## Status

**Phase 0 — Architecture & Setup.** No application code yet.
See [`PROJECT_STATE.md`](PROJECT_STATE.md) for the authoritative current state.

## Documentation

| File | Contents |
|---|---|
| [`PROJECT_STATE.md`](PROJECT_STATE.md) | **Start here.** Current phase, what's done, what's next, known debt |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Schema, real-time protocol, clock, concurrency, failure matrix, scaling |
| [`ROADMAP.md`](ROADMAP.md) | Phases, time budgets, definitions of done, cut order |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records — the *why* behind every major choice |
| [`SETUP.md`](SETUP.md) | Local development |
| [`DEPLOYMENT.md`](DEPLOYMENT.md) | AWS and Kubernetes deployment, and the destroy checklist |
| [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) | Problems encountered and their resolutions |
| [`INTERVIEW_NOTES.md`](INTERVIEW_NOTES.md) | Questions this project should let you answer |
| [`DEVELOPMENT_LOG.md`](DEVELOPMENT_LOG.md) | Chronological record of work and decisions |

## Three decisions worth reading first

- **The clock does not tick.** Remaining time is computed from three persisted columns
  and PostgreSQL's `now()`, so it is identical from any pod, survives reconnection to a
  different instance, and cannot drift. [ADR-006](docs/adr/ADR-006-computed-clock.md)
- **No distributed lock.** Move safety comes from an idempotency key, an optimistic
  version column, and a composite primary key — all inside one database transaction. A
  Redis lock would add a TTL-expiry failure mode without removing any of those.
  [ADR-005](docs/adr/ADR-005-optimistic-locking-not-distributed-locks.md)
- **PostgreSQL is the only source of truth.** Valkey holds nothing that cannot be
  rebuilt. It can be restarted mid-game.
  [ADR-004](docs/adr/ADR-004-postgres-source-of-truth.md)

## A note on numbers

No performance, cost, or scale figure appears in this repository without being either
(a) backed by a report in `docs/perf/` with the date and instance types stated, or
(b) explicitly labelled an estimate. See `PROJECT_STATE.md` §12.
