# ADR-001: Modular monolith over microservices

**Status:** Accepted · **Date:** 2026-09-06

## Context

The system has identifiable bounded contexts: identity, game, matchmaking, rating,
realtime. The obvious portfolio temptation is to deploy each as a service, because
"microservices" is a resume keyword. The spec explicitly forbids this.

## Decision

One Spring Boot deployable containing all modules, with boundaries enforced by
**ArchUnit tests** rather than by network calls. Async workers run from the *same
artifact* under a `worker` Spring profile, so they scale independently as separate
ECS tasks / Kubernetes Deployments without becoming a separate codebase.

Module structure: `com.chessplatform.<module>` with an `internal` sub-package that
ArchUnit forbids other modules from importing.

## Alternatives considered

**Microservices from day one.** Rejected. At this scale the network boundary buys
nothing and costs a great deal: distributed transactions where a local one sufficed,
service discovery, N deployment pipelines, N sets of dashboards, and debugging a
move-processing bug across three services. It would consume 30+ hours that belong to
concurrency and observability work.

**Flat package-by-layer monolith** (`controllers/`, `services/`, `repositories/`).
Rejected. It produces no boundaries at all, so the "modular" claim would be false and
extraction later would be a rewrite.

## Consequences

- One transaction can span identity and game data. This is a genuine advantage, not a
  compromise — the move pipeline is atomic without a saga.
- Independent scaling is available where it matters (workers), and unavailable where
  it doesn't.
- Extraction remains cheap: because `internal` packages are unreachable, a module's
  public surface is already its would-be API.
- If a module were extracted, the first candidate is `rating`, because it is
  already asynchronous, has no read dependency on game internals, and would be the
  first to need independent scaling under tournament load.

## Interview angle

**Q:** "Why isn't this microservices?"
**A:** Extraction is justified by an independent scaling need, an independent
deployment cadence, or independent team ownership. This system has one deployment
cadence and one owner, and the only component with a different scaling profile — the
rating consumer — is already deployed separately from the same artifact. Adding
network boundaries before those forces exist converts local method calls into
partial-failure modes for no benefit. I did enforce the boundaries with ArchUnit so
extraction stays cheap when a reason appears.

**Follow-up:** "What would you extract first, and what would break?"
**A:** `rating`. It would break the current ability to read a user's rating in the
same transaction that finalises a game — we'd move to eventual consistency, showing
the rating a beat late in the `GAME_FINISHED` message.
