# SETUP

## Prerequisites

| Tool | Version | Needed from |
|---|---|---|
| JDK | 25 (LTS) | Phase 0 |
| Docker + Compose | current | Phase 0 |
| Node.js | 20+ | Phase 2 |
| AWS CLI v2 | current | Phase 7 |
| Terraform | 1.6+ | Phase 7 |
| kubectl + kind | current | Phase 8 |
| k6 | current | Phase 9 |

Verify Java: `java -version` must report 25 — **or don't bother.** The build declares a
Java 25 toolchain and `settings.gradle.kts` applies the foojay resolver, so Gradle
downloads JDK 25 itself if it isn't present. Any JDK 17+ can run Gradle.

Check what Gradle can see with `./gradlew javaToolchains`. If JDK 25 is absent, the first
compile downloads it (once, a few minutes).

Gradle is not in this list — the wrapper (`./gradlew`) downloads the pinned version.
The build also declares a **Java toolchain**, so Gradle fetches and compiles against
JDK 25 even if a different JDK is on your PATH. Compilation is therefore identical on
every machine and in CI.

## The Gradle wrapper

`gradlew` and `gradle/wrapper/gradle-wrapper.properties` are in the repository, pinned to
**Gradle 9.7.1**. `gradle-wrapper.jar` and `gradlew.bat` are not — see
`gradle/wrapper/README-MISSING-JAR.md`. One command produces them:

```bash
./gradlew wrapper          # no --gradle-version flag; the root build reads the pin
./gradlew verifyGradleVersion
```

Commit all four files afterwards, including the jar. A clone without it cannot build.
`git update-index --chmod=+x gradlew` if the executable bit does not survive.

## Local development

```bash
# 1. Start dependencies
docker compose -f ops/docker/docker-compose.yml up -d

# 2. Confirm both are healthy before starting the app
docker compose -f ops/docker/docker-compose.yml ps

# 3. Run the backend
./gradlew :backend:bootRun --args='--spring.profiles.active=local'

# 4. Verify
curl -s localhost:8080/actuator/health | jq
```

A healthy response reports `status: UP` with `db` and `redis` components UP. If either
is DOWN, the app is running but its dependencies are not — do not proceed.

## Local service endpoints

| Service | Address | Credentials |
|---|---|---|
| API | http://localhost:8080 | — |
| PostgreSQL | localhost:5432 | `chess` / `chess` / db `chess` |
| Valkey | localhost:6379 | none |
| Actuator | http://localhost:8080/actuator | — |
| Prometheus scrape | http://localhost:8080/actuator/prometheus | — |

These credentials are for local development only and are intentionally weak. Production
credentials come from AWS Secrets Manager and never appear in this repository.

## Tests

```bash
./gradlew :backend:test              # unit + ArchUnit. Fast, no Docker.
./gradlew :backend:integrationTest   # Testcontainers. Slow, needs Docker.
./gradlew :backend:check             # both
```

The split is deliberate. A suite that takes four minutes stops being run locally, and a
feedback loop nobody uses is worse than no feedback loop. `test` should stay under ~10
seconds; if it creeps past that, something belongs in `integrationTest`.

Enable container reuse to avoid restarting Postgres on every run:

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

Integration tests start their own containers via Testcontainers and do **not** use the
compose stack. They are isolated and can run while the compose stack is up.

## Database migrations

Flyway runs on startup. Migrations live in
`backend/src/main/resources/db/migration/V<n>__<description>.sql`.

**Rules:** migrations are immutable once committed — never edit an applied migration,
always add a new one. Every migration must be backward-compatible with the previous
application version, because rolling deployments run both simultaneously.

## Resetting local state

```bash
docker compose -f ops/docker/docker-compose.yml down -v   # -v drops the volumes
```
