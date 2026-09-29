# ADR-021: One container image, three roles; migrations as a separate step

**Status:** Accepted · **Date:** 2026-09-29
**Builds on:** ADR-001 (workers are the same artifact), ADR-020 (Netty native access).

## Context

Phase 6 packages the backend for deployment (Phase 7: ECS; Phase 8: Kubernetes). Since Phase
5 the backend runs in two roles — API, and worker (outbox relay + rating consumer) — and the
roadmap asks for schema migrations to be a step separate from deploying code.

## Decision

**`backend/Dockerfile`, multi-stage, built from the repository root.**

- **Build stage** `eclipse-temurin:25-jdk-noble`: build scripts → `resolveDependencies`
  (its own layer, so a code-only change reuses ~88 MB of cached jars; plain layers, not
  BuildKit cache mounts, because only layers travel through CI's cache) → sources → `bootJar`
  → `jarmode=tools extract --layers`.
- **Runtime stage** `eclipse-temurin:25-jre-noble`: JRE only; Ubuntu rather than Alpine
  (Netty's native transport targets glibc). Boot's layers copied least- to most-volatile:
  a code change is a **565 kB** application layer.
- **Non-root** fixed uid/gid 10001, no shell login.
- **`JDK_JAVA_OPTIONS`** (the launcher variable — `JAVA_TOOL_OPTIONS` rejects `--` options):
  `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED`.
  Overridable per deployment.
- **Exec-form `ENTRYPOINT`**: the JVM is PID 1 and receives SIGTERM; graceful shutdown runs.
- **No `HEALTHCHECK`**: no curl in a JRE image; ECS/Kubernetes probe
  `/actuator/health/liveness|readiness`.
- **Build context is an allowlist** (`backend/Dockerfile.dockerignore`): everything excluded,
  the build inputs let back in. A denylist leaks what nobody thought to list.

**One image, three roles**, selected by `SPRING_PROFILES_ACTIVE`:

| Role | Profile | Runs | Behind the load balancer |
|---|---|---|---|
| API | *(none)* | HTTP + WebSocket, sweeper, matchmaker | yes |
| worker | `worker` | relay + rating consumer (+ health endpoint) | no |
| migrate | `migrate` | Flyway, Hibernate validation, then `exit 0` | — one-off |

**Migrations run as a one-off step before a rollout.** A failing migration stops the deploy
while the old version still serves, instead of failing on the first new instance mid-rollout.
The rule this imposes: **migrations must be backward compatible with the running version**
(expand → deploy → contract), since old and new instances share the schema during a rollout.
Flyway stays enabled at application startup as a no-op safety net: with the migrate step
already done it only validates.

## Verified (2026-09-29)

- Build: 142 s cold, **17.7 s** after a code-only change (dependency layer cached). Image
  400 MB: ~310 MB JRE base, 88 MB dependencies, 0.4 MB loader, 0.6 MB application.
- `id` → uid 10001. Compose `--profile app`: migrate exited 0 ("validated 7 migrations"),
  then API and worker; Postman 132/132 against the API container; the worker container rated
  Postman's games; **`e2e:lobby` passed with the rating crossing worker container → Valkey →
  API container in 799 ms**.
- `docker stop`: 2.5 s, "Graceful shutdown complete", exit **143** (128 + SIGTERM — the JVM's
  normal code for a SIGTERM exit; an orchestrator must not treat it as a crash).
- **Trivy found 3 CRITICAL (Tomcat 11.0.24) and 2 HIGH (Jackson 3.1.5, 2.21.5)** in Boot
  4.1.1's managed versions; no Boot 4.1.2 existed. Overridden via Boot's own properties
  (`tomcat.version`, `jackson-bom.version`, `jackson-2-bom.version`); full suite green; rescan:
  **0 fixable HIGH/CRITICAL**, exit code 0.

## Alternatives considered

| Alternative | Why not (yet) |
|---|---|
| **Copy a CI-built jar into a single-stage image** | Faster in CI (no second Gradle run), but the image then depends on the build environment; `docker build` alone would not reproduce it. |
| **Alpine JRE** | ~40 MB smaller; musl vs Netty's glibc native transport. |
| **Distroless / `jlink` custom runtime** | ~80 MB instead of ~310 MB base and fewer CVE surfaces. Worth doing if image pull time or scan noise matters — recorded, not built. |
| **Buildpacks (`bootBuildImage`)** | Good defaults, less control and less to explain; the Dockerfile is the interview artifact. |
| **Separate images per role** | Three artifacts to version and scan for one codebase; ADR-001 decided otherwise. |
| **Migrate on application startup only** | Couples schema change to rollout; many replicas race for the Flyway lock; failure happens mid-deploy. |

## Consequences

- Every Boot upgrade must re-check the three overrides (remove any Boot now manages at or
  above) — checklist item.
- The worker serves HTTP only for probes; nothing routes player traffic to it.
- Exit code 143 on normal shutdown: noted for Phase 7 task definitions / Phase 8 probes.

## Interview angle

**Q:** "Walk me through your Dockerfile."
**A:** Multi-stage: a JDK build stage where dependencies get their own layer before the
sources, so a code change reuses them; then Boot's layered extraction into a JRE image, so a
typical push is half a megabyte. Non-root, exec-form entrypoint so SIGTERM reaches the JVM and
graceful shutdown works, heap sized from the container limit, crash on OOM so the orchestrator
restarts us. One image, three roles by Spring profile — API, worker, and a migrate step that
runs Flyway and exits before a rollout.

**Q:** "Your scan found critical CVEs. What did you do?"
**A:** They were in Tomcat and Jackson versions managed by Spring Boot, with no Boot patch out
yet. I overrode just those versions through Boot's own properties, ran the full suite, rescanned
to zero, and wrote down that each override must go when Boot catches up — an override left
behind becomes the next vulnerability.
