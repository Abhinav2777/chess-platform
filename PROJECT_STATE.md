# PROJECT_STATE

> **This is the primary recovery document.** If development resumes in another
> environment, with another engineer, or with another AI assistant, read this file
> first, then `ARCHITECTURE.md`, then `docs/adr/`.
>
> Update this file at the end of every milestone. A stale PROJECT_STATE is worse than
> none, because it will be trusted.

**SNAPSHOT: M1.1 (2026-09-06)** — see the `SNAPSHOT` file at the repository root.
If a build fails in a way that contradicts this document, check that file first: you may
be building an older extracted copy.

**Last updated:** 2026-09-06 · **Updated at:** Phase 1 Milestone 1.1


---

## 0. Handoff workflow (established 2026-09-14)

**Two rules, both established after being broken:**

1. **Work through `docs/BOOT4_CHECKLIST.md` and run its greps before shipping any
   framework-touching code.** Four separate round trips in Milestone 1.2 were caused by
   Boot 4 changes already recorded in this repository's own ADRs and logs, but not
   consulted.
2. **Documentation ships with the code that changed it — in the same turn, every turn.**
   Not "doc-only, it'll land with the export." Every deferral has produced a divergence:
   a patch that would not apply, and then a set of markdown files silently a week out of
   date. If a fix updates an ADR, `TROUBLESHOOTING.md` or the log, those files go out
   with the fix.


**No archive is produced per milestone.** Archives are created only at a **major phase
boundary**, or when explicitly requested, or when there is a technical reason one is
necessary. Downloading and re-extracting the tree for every small change was costing more
than it delivered, and twice caused work to be done against a stale copy.

### How changes reach the repository

| | |
|---|---|
| **Source of truth** | The git repository on the development machine. Not any archive, and not Claude's workspace. |
| **During a session** | Claude holds a working copy and delivers changes as file contents in chat — new files in full, edits as precise instructions. |
| **Applying them** | Save them into the tree and commit. The commit is what makes "what changed" answerable. |
| **Phase boundary** | One archive, uniquely named `chess-platform-<snapshot>-<date>.tar.gz`, plus a written summary (files changed, tests, commands, env requirements, ADRs, verification list). |

### Claude's workspace is ephemeral

It resets between sessions. If a session starts cold, either upload the current tree or
point Claude at this file — `PROJECT_STATE.md` plus `docs/adr/` is designed to be enough
to reconstruct intent, though not the code.

### Git is not optional for this project

Every delivery problem so far — stale extracted trees, a file silently overwritten by a
same-named file, "which version am I running" — is a problem git already solves.
`git status` after applying changes answers in one second what has otherwise taken a
debugging round trip each time.

```bash
git init && git add -A && git commit -m "Phase 0 + Milestone 1.1"
```

### Rule this does not relax

**Milestone size is unchanged.** Fewer handoffs means larger coherent units of work, not
shallower ones. The repository must be internally consistent at every handoff — never
left half-migrated because a sub-milestone ended.

---

## 1. Current position

| | |
|---|---|
| **Current phase** | Phase 1 — Core Chess MVP |
| **Phase status** | Milestone 1.2 code complete; final fix (reuse-detection rollback) awaiting verification |
| **Hours used (estimated)** | Phase 0: ~5 (complete). Phase 1: ~3 of 16–20 |
| **Cumulative hours (estimated)** | ~8 of 135–175 |
| **Schedule status** | On track |
| **Scope status** | On track — three spec contradictions found and resolved (`ROADMAP.md` § Deviations) |
| **Next milestone** | Milestone 1.3 — chess rules port (chesslib adapter + perft), game lifecycle, move submission |
| **Handoff mode** | In-place edits; archive only at phase boundaries (see §0) |

---

## 2. Completed

### Phase 1 (in progress) — Milestone 1.2: authentication over HTTP

- `V2__refresh_tokens.sql` — hashed tokens, `family_id` lineage, partial expiry index.
- `JwtService` — HS256 via Spring Security's `JwtEncoder`/`JwtDecoder` over Nimbus,
  algorithm pinned. `verify()` returns `Optional`, reusable from Phase 2's WebSocket auth.
- `RefreshTokenService` — rotation with family-based reuse detection (ADR-013); the
  rotation claim is a conditional UPDATE, not a read-check-write.
- `SecurityConfig` — Security 7 lambda DSL, stateless, deny-by-default.
- `JwtAuthenticationFilter` — never rejects; populates SecurityContext + MDC, clears both.
- `AuthController` (register/login/refresh/logout), `UserController` (`/me`).
- `ApiExceptionHandler` — RFC 7807 problem+json, domain errors verbatim, everything else
  opaque with a correlation id.
- New dependency: `org.springframework.security:spring-security-oauth2-jose`.
- Tests: `AuthApiIntegrationTest` — 15 cases including reuse detection, mass assignment,
  forged tokens, cookie hardening, and an assertion that failures leak no internals.

### Phase 1 — Milestone 1.1: identity domain (COMPLETE, green)

- `common/error`: `ErrorCode`, `DomainException` (Conflict / NotFound / Unauthorized),
  stack traces disabled since these are thrown on expected paths.
- `common/id/Uuid7`: RFC 9562 v7 generator, lock-free via CAS, monotonic within a
  millisecond, survives clock regression. Hand-written rather than a dependency.
- `identity/domain`: `User` entity + `UserRepository`.
- `identity/internal`: `UserRegistrar` (check-then-insert race handled by the unique
  constraint, not the pre-check), `UserAuthenticator` (uniform error + dummy-hash timing
  defence against user enumeration).
- `identity`: `IdentityFacade` + `UserSummary` — the module's only public surface.
- `platform`: `PasswordEncoderConfig` (bcrypt cost 12 via `DelegatingPasswordEncoder`),
  `ClockConfig`.
- Tests: `Uuid7Test` (6 cases incl. ordering, clock regression, concurrency),
  `UserRegistrationIntegrationTest` (13 cases incl. a 16-thread registration race).

**Fixed while building:** the `entitiesDoNotLeak` ArchUnit rule was too strict — it
forbade a module's own facade from mapping its own entity, which is the pattern it was
meant to encourage. Rescoped per-module. Also excluded `-serial` and `-processing` from
`-Xlint:all`, since `RuntimeException` being `Serializable` made every exception class
fail the build under `-Werror`.

### Phase 0 — COMPLETE (verified 2026-09-06)

Build, compose stack, health check, Flyway V1, integration tests and CI all confirmed
working on the development machine.

### Phase 0 detail (partial)

- Specification reviewed; contradictions identified and resolved:
  - observability scheduled after the deadline that requires it → made cross-cutting
  - "Game Service → workers" vs "modular monolith" → workers are the same artifact
  - AWS + Kubernetes phases implied deploying twice → ECS/kind split (ADR-010)
  - the phase budgets omit learning time → roadmap re-costed at 135–175 h
- Technology choices validated and recorded as ADRs 001–011.
- `ARCHITECTURE.md` written: schema, real-time protocol, clock design, concurrency
  strategy, failure matrix, scalability path, testing strategy.
- `ROADMAP.md` written with per-phase MU/MI/SKIP and definitions of done.
- Repository structure defined.
- Scaffolding written: `settings.gradle.kts`, `gradle/libs.versions.toml`,
  `backend/build.gradle.kts`, `ops/docker/docker-compose.yml`, `application.yml` +
  `application-local.yml`, `logback-spring.xml`, `V1__baseline.sql`,
  `ChessPlatformApplication`, `ModuleBoundaryTest`, `.github/workflows/ci.yml`.
- Gradle wrapper bootstrapped on the dev machine; `verifyGradleVersion` passes.
- **chesslib corrected to 1.3.7 and sourced from JitPack** via an `exclusiveContent`
  repository scoped to `com.github.bhlangonijr` (ADR-012). It was never on Maven Central;
  the original `1.3.4` on `mavenCentral()` could not have resolved at any version.
- **Toolchain auto-provisioning added** (`foojay-resolver-convention` 1.0.0 in
  `settings.gradle.kts`). The Java 25 toolchain declaration was previously a requirement
  with nothing able to satisfy it on a machine without JDK 25.
- **Gradle pinned to 9.7.1** in `gradle/libs.versions.toml`, enforced by a root
  `wrapper` task that reads the catalog and a `verifyGradleVersion` CI step that fails on
  drift. The wrapper itself is not yet generated — see §10.
- `.gitignore` rewritten for Gradle (it was still Maven-era, ignoring `target/` and
  nothing Gradle produces) with explicit negations so `gradle-wrapper.jar` is committed.
- CI now validates the wrapper jar checksum before any Gradle execution.
- Build tool changed to **Gradle** (Kotlin DSL + version catalog) at the project owner's
  request. ADR-011 rewritten; the Maven argument was portfolio legibility, which loses
  to the owner's existing fluency.
- Spring Boot target corrected twice: 3.5.x → 4.0.x → **4.1.x**. 3.5 reached OSS
  end-of-life on 2026-06-30 and was the last of the 3.x line; 4.0.x support ends
  December 2026, inside this project's timeline.

---

## 3. Not yet started

Everything else. Phases 1–10 per `ROADMAP.md`.

Phases 1–10 per `ROADMAP.md`. Phase 0's *files* exist but are unverified (§4).

Immediate next actions are in §10.

---

## 4. Known bugs / unverified state

**The scaffolding has never been built or run.** It was written in an environment with
no access to Maven Central or the Gradle distribution server, so nothing has been
compiled, no dependency has been resolved, and the application has not started. Treat
every file as a first draft that compiles in principle.

Highest-risk items, in order:

| Risk | Why | How to check |
|---|---|---|
| **Compilation has never succeeded** | Dependency resolution failed on chesslib, which happens *before* compilation — so the Java 25 toolchain, the ArchUnit rules, and every starter coordinate are still unproven. | `./gradlew :backend:test` |
| Starter coordinates | **Resolved 2026-09-14** against the official Boot 4.0 migration guide, not by guessing: `flyway-core` → `spring-boot-starter-flyway`; `spring-boot-starter-web` → `-webmvc` (old name is a deprecated alias, which is why it compiled); added `spring-boot-starter-webmvc-test` and `spring-boot-starter-security-test`. | `data-redis` and `websocket` companions still unexercised — re-check at Phase 2 |
| `@MockBean` / `@SpyBean` are removed in Boot 4 | Not used yet. Replacements are Spring Framework 7's `@MockitoBean` / `@MockitoSpyBean`. | Milestone 1.2, when mocking starts |
| `verifyGradleVersion` task is untested | Written but never executed; the drift-matching logic was verified only by simulation | It runs first in CI, so a bug surfaces immediately rather than silently |
| Version numbers stale | All pinned versions verified 2026-09-06 only | Re-check `docs.spring.io/spring-boot/system-requirements.html` |
| `logstash-logback-encoder` compatibility | Boot 4 moved to Jackson 3; the encoder may need a Jackson-3-compatible release | Build; if it fails, Boot 4.1 has built-in structured JSON logging as a fallback (`logging.structured.format.console`) — arguably better, since it removes a dependency |
| `-Werror` may fail the build | Strict by design, but a noisy dependency can make it unbuildable | If it blocks progress, narrow to specific `-Xlint` categories rather than dropping it |
| ArchUnit `allowEmptyShould` | Rules match nothing until Phase 1; without this flag ArchUnit fails an empty rule | Should be handled; confirm the test passes on the empty codebase |

**Do not start Phase 1 until `./gradlew :backend:check` and `bootRun` both succeed.**

No application logic exists yet, so there are no logic bugs.

---

## 5. Technical debt / accepted compromises

Recorded now so they are not discovered later and mistaken for oversights.

| Item | Decision | Revisit when |
|---|---|---|
| Hard dependency on PostgreSQL availability | Accepted; no graceful path if PG is down | Never for this project — documented in the failure matrix instead |
| Access tokens not instantly revocable (15-min window) | Accepted | Only if a security requirement demands it (ADR-009) |
| Network latency charged to the moving player | Accepted policy; no lag compensation | Out of scope (ADR-006) |
| Single-AZ RDS | Cost decision | Never for this project; multi-AZ is discussed, not deployed |
| Kubernetes primarily on `kind` | Cost decision (ADR-010) | EKS window in Phase 8 proves the manifests |
| Elo rather than Glicko-2 | Identical engineering value, less code | Never |
| JitPack is a build-time availability and mutability dependency | Accepted, scoped to one group (ADR-012). Dependency locking not yet applied. | Phase 6, with Renovate/Dependabot |
| Spring Boot 4.1 is a recent major; third-party lag is possible | Accepted. AWS SDK used directly to remove the highest-risk coupling. Falling back to 3.5 is **not** an option — it is EOL. | If a dependency blocks progress, replace the dependency, not the framework |
| No lag/anti-cheat detection | Out of scope | Never |

---

## 6. Architectural decisions

Full reasoning in `docs/adr/`. Summary:

| # | Decision |
|---|---|
| 001 | Modular monolith; boundaries enforced by ArchUnit; workers = same JAR, `worker` profile |
| 002 | `chesslib` behind a `ChessRules` port; verified with perft tests |
| 003 | Raw WebSocket + custom JSON envelope; Valkey Pub/Sub fanout; **not** STOMP |
| 004 | PostgreSQL is the only source of truth; Valkey holds nothing unrecoverable |
| 005 | Optimistic locking + idempotency keys + `PK(game_id, ply)`; **no distributed lock** |
| 006 | Clock is computed, never ticks; PostgreSQL `now()` is the sole time authority |
| 007 | Full snapshot on (re)connect; no delta replay |
| 008 | SQS Standard + outbox + `processed_events` dedupe; **not** Kafka, **not** FIFO |
| 009 | JWT access + rotating refresh; WebSocket auth in the first message, not the URL |
| 010 | ECS Fargate as the production path; EKS time-boxed; **no NAT Gateway** |
| 011 | Java 25 LTS + Spring Boot 4.1.1 + **Gradle 9.7.1**; virtual threads, no WebFlux; AWS SDK v2 direct, not Spring Cloud AWS |
| 012 | JitPack accepted for chesslib, scoped via `exclusiveContent` to one group |

---

## 7. Infrastructure state

| Environment | Status | Notes |
|---|---|---|
| Local | Compose file written, **never started** | `ops/docker/docker-compose.yml`: Postgres 16, Valkey 8 |
| AWS — Path A (always-on demo) | Not provisioned | t3.small, planned Phase 7 |
| AWS — Path B (ECS reference) | Not provisioned | Terraform, planned Phase 7, **apply/destroy cycle only** |
| Kubernetes — `kind` | Not provisioned | Planned Phase 8 |
| Kubernetes — EKS | Not provisioned | Time-boxed window, Phase 8 |
| **AWS spend to date** | **$0.00** | Budget alarm not yet created — create it *before* the first `terraform apply` |

---

## 8. Setup requirements

See `SETUP.md`. Summary: JDK 21, Docker + Compose, Node 20+ (frontend only from Phase 2),
`awscli` and `terraform` (Phase 7 only), `kubectl` and `kind` (Phase 8 only).

---

## 9. Deployment state

Nothing is deployed. No AWS resources exist. No domain registered.

---

## 10. Next recommended tasks

**Phase 0 verification — in this order. Do not start Phase 1 until all pass.**

Steps 1–3 are done.

1. ~~Bootstrap the wrapper~~ — done. `./gradlew --version` reports 9.7.1.
2. ~~`./gradlew verifyGradleVersion`~~ — done, passing.
3. ~~Commit `gradlew`, `gradlew.bat`, `gradle/wrapper/` including the jar~~ — done.
4. **`./gradlew :backend:test`** — first real compile. Proves in one command: chesslib
   resolves from JitPack, every Spring starter coordinate is valid on Boot 4.1, the
   Java 25 toolchain resolves or downloads, `-Werror` doesn't reject our own code, and
   ArchUnit passes on the empty codebase. Expect a slow first run (JDK and JitPack
   downloads).
5. **Diff against `start.spring.io`** — generate a reference project (Gradle Kotlin DSL,
   Java 25, Boot 4.1, same starters) and compare its build file. Do this even if step 4
   passed: it catches coordinates that resolve but are deprecated or superseded in Boot 4.
6. **`docker compose -f ops/docker/docker-compose.yml up -d`** — then
   `docker compose ps` and wait for both containers to report *healthy*, not just *up*.
7. **`./gradlew :backend:bootRun --args='--spring.profiles.active=local'`**
8. **`curl -s localhost:8080/actuator/health | jq`** — `db` and `redis` both UP.
9. **Confirm Flyway applied V1:** `docker exec -it chess-postgres psql -U chess -d chess -c '\dt'`
   should list `users`, `games`, `moves`, `flyway_schema_history`.
10. **`./gradlew :backend:integrationTest`** — Testcontainers. Needs Docker; starts its
    own containers independent of the compose stack.
11. **Push; confirm CI is green.**
12. **Log anything that broke in `TROUBLESHOOTING.md`.**

Then **Phase 1 Milestone 1: `identity` module.**

---

## 11. Open questions for the project owner

- Is a domain name available for the Phase 7 demo, or should it run on the raw ALB
  hostname? (Affects whether Route 53 + ACM are in scope.)
- Preferred AWS region. `us-east-1` is assumed for all cost estimates; an ap-south-1
  deployment would be lower-latency from Hyderabad but slightly more expensive.
- Is there an existing AWS account with billing alerts configured, or does one need
  creating?

---

## 12. Measurement ledger

**Every performance, cost, or scale number in this repository must have a row here.**
If it is not in this table, it is an estimate and must be labelled as one.

| Claim | Value | Measured on | Evidence |
|---|---|---|---|
| *(none yet)* | | | |

Estimates currently in the repository, all clearly labelled as such: AWS monthly costs
(`ARCHITECTURE.md` §12.1, ADR-010), phase hour budgets (`ROADMAP.md`), the 1,000-connection
target (`ARCHITECTURE.md` §2 — a *target*, not a result).
