# syntax=docker/dockerfile:1.7
#
# The chess platform backend. One image, three roles (ADR-001, Phase 6):
#   API     — default
#   worker  — SPRING_PROFILES_ACTIVE=worker   (outbox relay + rating consumer)
#   migrate — SPRING_PROFILES_ACTIVE=migrate  (run Flyway, then exit)
#
# Build from the repository root (the Gradle wrapper and version catalog live there):
#   docker build -f backend/Dockerfile -t chess-platform .
# The build context is an allowlist: backend/Dockerfile.dockerignore.
#
# Since Phase 7 the image also serves the SPA (ADR-023): one origin for page, API and socket.

# ---------------------------------------------------------------- frontend
# Build-only: nothing from this stage but dist/ reaches the runtime image, so Node and
# node_modules are neither shipped nor scanned. Lockfile first, so a source-only change
# reuses the npm layer.
FROM node:24-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src src
RUN npm run build

# ---------------------------------------------------------------- build
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

# Build scripts first, then dependencies, then sources: a code-only change reuses the
# dependency layer — in CI too, where the layer cache is the only cache that travels.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN ./gradlew :backend:resolveDependencies --no-daemon --quiet

COPY backend/src/main backend/src/main
# Into the jar's classpath:/static — served by Spring's static resource handling, with "/"
# forwarding to index.html. Lands in the application layer (kilobytes), after the sources.
COPY --from=frontend /frontend/dist backend/src/main/resources/static
RUN ./gradlew :backend:bootJar --no-daemon --quiet \
 && java -Djarmode=tools -jar backend/build/libs/chess-platform.jar \
        extract --layers --launcher --destination /extracted

# ---------------------------------------------------------------- runtime
# JRE, not JDK: no compiler in production. Ubuntu (noble), not Alpine: Netty's native
# transport is built against glibc, and musl surprises are not a debugging session worth
# having for ~40 MB.
FROM eclipse-temurin:25-jre-noble

# Security fixes Ubuntu has published but the base image does not have yet. Temurin rebuilds
# its images on its own schedule; the first time it lagged (2026-09-30: OpenSSL, HIGH, fixed in
# Ubuntu five days after the base image was built) the CI scan correctly refused to publish.
#
# OS_PATCH_DATE exists to break the layer cache. Without it this RUN line never changes, so
# the cached layer — and whatever package versions it froze — is reused forever and later fixes
# never arrive. CI passes today's date: at most one re-run per day. The cost is that layers
# below it get new digests daily, so the first push of a day re-uploads the dependency layer.
ARG OS_PATCH_DATE=unset
RUN echo "OS packages patched as of ${OS_PATCH_DATE}" \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
 && rm -rf /var/lib/apt/lists/*

# A fixed, non-root, non-login user. A compromised process should not own the container.
RUN groupadd --system --gid 10001 chess \
 && useradd --system --uid 10001 --gid chess --no-create-home --shell /usr/sbin/nologin chess

WORKDIR /app
# Boot's layers, least to most frequently changed, so a push after a code change uploads
# the application layer (kilobytes) and not the dependencies (~100 MB).
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# JDK_JAVA_OPTIONS, not JAVA_TOOL_OPTIONS: only the launcher variable accepts `--` options.
#   MaxRAMPercentage       heap sized from the container's memory limit, not the host's
#   ExitOnOutOfMemoryError die cleanly and let the orchestrator restart us, rather than
#                          limp on in an undefined state
#   enable-native-access   Netty's native loading (ADR-020); Java 25 warns, later blocks
# Overridable per deployment: set JDK_JAVA_OPTIONS in the task definition.
# 50 %, not 75: the JVM's non-heap measured 212 MB under load (9.3) — at 75 % of a 1 GB container,
# heap max + non-heap + native exceeds the limit and the kernel kills the process before Java
# reports anything. Deployments also commit the heap up front (k8s overlay, ECS task definition).
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"

# Exec form: the JVM is PID 1 and receives SIGTERM directly, so graceful shutdown
# (server.shutdown=graceful) actually runs. A shell form would swallow the signal and the
# orchestrator would SIGKILL us mid-request.
#
# No HEALTHCHECK: the JRE image has no curl, and ECS/Kubernetes probe
# /actuator/health/liveness and /readiness themselves.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
