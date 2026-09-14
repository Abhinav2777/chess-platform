# Repository structure

```
chess-platform/
├── README.md                  entry point
├── PROJECT_STATE.md           ← recovery document, read first
├── ARCHITECTURE.md            the design
├── ROADMAP.md                 phases, budgets, definitions of done
├── DEVELOPMENT_LOG.md         chronological record
├── INTERVIEW_NOTES.md         rehearsal script
├── SETUP.md  DEPLOYMENT.md  TROUBLESHOOTING.md
│
├── docs/
│   ├── adr/                   ADR-001 … ADR-011
│   ├── api/                   OpenAPI spec (Phase 1)
│   ├── diagrams/              Mermaid sources
│   ├── perf/                  k6 reports — the ONLY source of performance claims
│   └── REPOSITORY_STRUCTURE.md
│
├── settings.gradle.kts        single build; frontend stays outside Gradle
├── gradle/libs.versions.toml  version catalog — every version carries its check date
│
├── backend/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/chessplatform/
│       │   ├── ChessPlatformApplication.java
│       │   ├── common/        errors, ids, time, pagination
│       │   ├── platform/      security config, observability, health
│       │   ├── identity/      ┐
│       │   ├── chess/         │ each module:
│       │   ├── game/          │   api/         controllers + DTOs
│       │   ├── realtime/      │   domain/      entities + services
│       │   ├── matchmaking/   │   internal/    ← unreachable from other modules
│       │   └── rating/        ┘   <Module>Facade.java  ← the only public entry point
│       ├── main/resources/
│       │   ├── application.yml, application-local.yml, application-worker.yml
│       │   ├── db/migration/  Flyway V1__… V2__…
│       │   └── logback-spring.xml
│       └── test/java/com/chessplatform/
│           ├── architecture/  ArchUnit boundary rules
│           ├── unit/          rules adapter, ClockCalculator, Elo
│           ├── integration/   Testcontainers
│           └── concurrency/   the flagship test
│
├── frontend/                  React + TS. Hard cap: 10 h across the whole project.
│
├── infra/
│   ├── terraform/  modules/{network,data,compute,messaging}  envs/{dev,prod}
│   └── k8s/        base/ + overlays/{kind,eks}
│
├── ops/
│   ├── docker/     docker-compose.yml, Dockerfile
│   ├── loadtest/   k6 scenarios
│   └── grafana/    dashboard JSON
│
└── .github/workflows/  ci.yml, deploy.yml
```

## The rules that make this "modular"

1. **A module is entered only through its facade.** `game` may call
   `IdentityFacade.findUser(...)`; it may not import anything under `identity.internal`
   or `identity.domain`.
2. **`internal` is enforced, not suggested.** ArchUnit fails the build on violation.
   Without enforcement, "modular monolith" is a claim rather than a property, and by
   month three it is a false claim.
3. **No cyclic dependencies between modules.** Also an ArchUnit rule. If `game` and
   `rating` both need something, it belongs in `common`.
4. **`common` depends on nothing.** It holds error types, ID generation, the clock
   abstraction, pagination — no business logic.
5. **Modules are ordered by dependency direction:**
   `common ← platform ← identity ← chess ← game ← {realtime, matchmaking, rating}`.
   Arrows point toward the dependency. Nothing points backwards.

Rule 5 is what makes extraction cheap later: a module with no inbound dependencies from
below can be lifted out with its facade becoming an HTTP or queue interface. `rating` is
deliberately the leaf most ready to leave (ADR-001).
