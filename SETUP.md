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

The wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) is committed, pinned to
**Gradle 9.8.0** in `gradle/libs.versions.toml`. CI checksums `gradle-wrapper.jar` against
Gradle's published releases. To change the version, edit the catalog, then:

```bash
./gradlew wrapper          # no --gradle-version flag; the root build reads the pin
./gradlew wrapper          # twice: the second run uses the new version to write its own files
./gradlew verifyGradleVersion
```

Never bump only `gradle-wrapper.properties` (Dependabot does exactly that):
`verifyGradleVersion` fails the build on the drift.

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
| ElasticMQ (SQS, Phase 5) | http://localhost:9324 (API), http://localhost:9325 (UI) | none (the app sends dummy credentials) |
| Actuator | http://localhost:8080/actuator | — |
| Prometheus scrape | http://localhost:8080/actuator/prometheus | — |

These credentials are for local development only and are intentionally weak. Production
credentials come from AWS Secrets Manager and never appear in this repository.

## Running the containerized stack (Phase 6)

```bash
docker build -f backend/Dockerfile -t chess-platform:dev .      # from the repo root
docker compose -f ops/docker/docker-compose.yml --profile app up -d
```

Runs the same image three ways (ADR-021): `migrate` (Flyway, exits 0), then `api` on :8080 and
`worker` (relay + rating consumer). Stop any `bootRun` first. Development-sized auth rate limits
are set in the compose file. On a machine where containers cannot reach the internet, see
TROUBLESHOOTING (“docker build fails: UnknownHostException”).

The image also serves the frontend (ADR-023): open **http://localhost:8080** — one origin,
exactly as behind the ALB. The browser checks run against it with
`APP_URL=http://localhost:8080 npm run e2e:lobby` (from `frontend/`).

## Kubernetes on kind (Phase 8)

Needs `kind` and `kubectl`, ~5 GB of free memory, and port 80 free on the host.

```bash
# 1. Build the image (on this machine: TROUBLESHOOTING "docker build fails: UnknownHostException")
docker build -f backend/Dockerfile -t chess-platform:dev .
# 2. Cluster + add-ons (ingress-nginx, metrics-server), images preloaded — ~1–2 min
k8s/cluster-up.sh
# 3. Deploy: dependencies → migrate Job (must complete) → api ×2 + worker — ~1 min
k8s/deploy.sh dev
# 4. Open http://localhost  (the browser checks: APP_URL=http://localhost npm run e2e:lobby)
kubectl -n chess get pods ; kubectl -n chess top pods
# Tear down
kind delete cluster --name chess
```

A new image: build it with a new tag and `k8s/deploy.sh <tag>` — the migrate Job runs first, then a
rolling update (one pod at a time, never below two ready). Layout and reasoning: ADR-024.

## API collection

`docs/api/chess-platform.postman_collection.json`. Import into Postman and **Run
collection** against a running app — 38 requests with assertions. See
`docs/api/README.md`.

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
