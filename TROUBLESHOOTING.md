# TROUBLESHOOTING

Problems actually encountered during development, and how they were resolved. Add an
entry every time something costs more than fifteen minutes — the next person to hit it
may be you in three months.

Format: **Symptom** → **Cause** → **Fix**.

---

## Encountered

### `Could not find com.github.bhlangonijr:chesslib:<version>`

**Symptom:** dependency resolution fails; `./gradlew :backend:dependencies --configuration compileClasspath`
shows `FAILED` on chesslib while everything else resolves.
**Cause:** two problems wearing one error message. The version was wrong, *and* chesslib
is not on Maven Central at all — it is distributed via JitPack. The `com.github.<user>`
groupId is the tell: that is JitPack's naming convention, derived from the GitHub path.
No version would have resolved from Central.
**Fix:** version `1.3.7`, and add JitPack as an `exclusiveContent` repository scoped to
`com.github.bhlangonijr`. Never add JitPack unscoped — see ADR-012.

**Generalisable:** "Could not find group:artifact:version" is ambiguous between *wrong
version* and *wrong repository*. Distinguish them before changing the version: if the
groupId looks like `com.github.<something>`, suspect the repository first.

### `No matching toolchains found for requested specification: {languageVersion=25}`

**Symptom:** compilation fails on a machine where `./gradlew javaToolchains` lists only
older JDKs.
**Cause:** the build declares a Java 25 toolchain, but a toolchain declaration is a
*requirement*, not a provisioning instruction. Without a resolver, Gradle can only check
whether a matching JDK is already installed.
**Fix:** the `foojay-resolver-convention` settings plugin lets Gradle download it.
Installing JDK 25 locally works too. Note that dependency resolution happens *before*
compilation, so a dependency failure will mask this — a build that dies on a missing
dependency has not yet proven its toolchain works.

---

## Anticipated issues

These have not occurred yet. They are recorded because they are predictable and the
resolution is known in advance.

### Testcontainers fails to start

**Symptom:** `Could not find a valid Docker environment`.
**Cause:** Docker daemon not running, or the socket is not where Testcontainers expects
(common with Colima, Rancher Desktop, and rootless Docker).
**Fix:** ensure Docker is running; if using a non-standard runtime, set
`DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`.

### Flyway checksum mismatch

**Symptom:** `Migration checksum mismatch for version N`.
**Cause:** an already-applied migration file was edited. Migrations are immutable.
**Fix:** locally, `docker compose down -v` and start clean. **Never** run
`flyway repair` against a deployed environment to paper over an edit — write a new
migration instead.

### Virtual thread pinning

**Symptom:** throughput collapses under load despite low CPU.
**Cause:** on JDK 21, a virtual thread that blocks inside a `synchronized` block pins its
carrier thread (addressed by JEP 491 in later JDKs).
**Fix:** replace `synchronized` with `ReentrantLock` on any path that performs I/O.
Diagnose with `-Djdk.tracePinnedThreads=full`.

### WebSocket connection closes immediately after opening

**Symptom:** socket opens then closes with code 1008 or 1000.
**Cause:** most likely the 5-second first-message auth timer expired, or the `AUTH`
frame was malformed. See ADR-009.
**Fix:** check the close reason in the frame; the server sets it deliberately.

### ALB drops WebSocket connections after 60 seconds

**Symptom:** connections die on a fixed interval in AWS but not locally.
**Cause:** ALB idle timeout (default 60s) closes connections with no traffic.
**Fix:** application-level heartbeat at a shorter interval, and raise the ALB idle
timeout. The heartbeat is the real fix — raising the timeout alone just moves the
problem.
