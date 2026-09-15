# TROUBLESHOOTING

Problems actually encountered during development, and how they were resolved. Add an
entry every time something costs more than fifteen minutes — the next person to hit it
may be you in three months.

Format: **Symptom** → **Cause** → **Fix**.

---

## Encountered

### `Schema validation: missing table [users]` — Flyway never ran

**Symptom:** every integration test fails on context load. Hibernate reports a missing
table. The application starts fine outside tests and `/actuator/health` is UP.
**Cause:** Spring Boot 4 moved auto-configuration into per-technology modules. Depending
on raw `org.flywaydb:flyway-core` puts Flyway on the classpath **without**
`FlywayAutoConfiguration`, which now ships in `spring-boot-flyway`. The app starts, every
`spring.flyway.*` property is accepted, and nothing migrates.
**Fix:** depend on `org.springframework.boot:spring-boot-starter-flyway` (plus
`org.flywaydb:flyway-database-postgresql`), not `flyway-core`.

**This applies to any dependency that used to be auto-configured from a raw coordinate.**
Boot 4 also renames `spring-boot-starter-web` to `spring-boot-starter-webmvc` for Spring
MVC, and expects a test counterpart starter alongside each main starter.

**The diagnostic to reach for: the condition evaluation report.** When auto-configuration
does not apply, this says why — which conditions matched and which did not:

```bash
./gradlew :backend:bootRun --args='--spring.profiles.active=local --debug' 2>&1 | grep -i flyway
```

An absent auto-configuration does not appear in "Negative matches" — it is simply not on
the classpath at all, which is itself the answer.

**Why the health check did not catch it.** `db` health validates that a connection can be
obtained. An empty database passes. **A green health check is not a schema check.**

### Why `/actuator/health` being UP proved nothing about migrations

See above. Generalisable: an absent component is more dangerous than a broken one. A
broken migration tool fails loudly at startup; a missing one hands you an empty database
and a green build. Anything whose absence is indistinguishable from success needs an
explicit assertion — hence `SchemaMigrationIntegrationTest`.

### Every integration test fails with `SchemaManagementException`

**Symptom:** all tests in a class fail. The first shows
`SchemaManagementException at AbstractSchemaValidator`; the rest show only
`IllegalStateException at DefaultCacheAwareContextLoaderDelegate:157`.
**Reading it:** only the *first* failure is real. Spring caches application contexts, so
once a context fails to load, every subsequent test gets the cached failure. **Debug the
first failure and ignore the rest** — twelve failures here were one bug.
**Cause:** `ddl-auto: validate` found the entity mapping disagreeing with the Flyway
schema. In this case `java.time.Instant` maps to plain `TIMESTAMP` by default while the
column is `TIMESTAMPTZ`.
**Fix:** `spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type: TIMESTAMP_UTC`.

**Second instance (2026-09-14):** `wrong column type ... column [token_hash] ... found
[bpchar (Types#CHAR)], but expecting [varchar(64)]`. The migration said `CHAR(64)`; the
entity mapped `String` with `length = 64`. Fix: `VARCHAR(64)`. PostgreSQL gains nothing
from `CHAR(n)` — it is blank-padded `bpchar`. **Write both sides from the type-mapping
table in ARCHITECTURE.md §4.2.1.**

**Note the blast radius:** one entity's mismatch fails the whole persistence unit, so the
new `RefreshToken` broke every pre-existing identity test as well. 25 failures, one
wrong word.

**Why it appeared only in Phase 1:** Phase 0 had no entities, so `validate` had nothing
to check and passed vacuously. A green build does not mean a setting works — it may mean
the setting had no input. Worth remembering for `-Werror`, ArchUnit, and any other check
whose subject set starts empty.

**To see the exact validation message.** Hibernate names the column and both types, so
the message *is* the diagnosis. Two ways to get it:

```bash
open backend/build/reports/tests/integrationTest/index.html   # always has the full text
./gradlew :backend:integrationTest                            # needs exceptionFormat = FULL
```

**`--info` and `grep` will not help if `exceptionFormat` is SHORT.** Gradle's default
truncates every exception to a class name and a line number and discards the message.
The `testLogging` block in `backend/build.gradle.kts` now sets
`exceptionFormat = TestExceptionFormat.FULL` and `showCauses = true` for exactly this
reason. Two debugging rounds were lost to a build that was configured to hide the answer
— **check that failures are legible before trying to interpret them.**

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

### `HV000030: No validator could be found for constraint '@Positive' validating type 'java.time.Duration'`

**Symptom:** startup fails binding `@ConfigurationProperties`.
**Cause:** Bean Validation's comparison constraints (`@Positive`, `@Negative`, `@Min`,
`@Max`, `@PositiveOrZero`) only have validators for numeric types — `BigDecimal`,
`BigInteger` and the primitive number types. `Duration` is not one, and the mistake
compiles cleanly. Spring Boot ships `@DurationUnit` for *conversion*, not validation.
**Fix:** validate in the record's compact constructor. It also yields a message naming
the property, which beats a generic constraint violation.

**Generalisable:** a validation annotation that compiles is not a validation annotation
that applies. Constraints are matched to types at runtime, so an inapplicable one is a
startup failure, and — worse — a constraint silently doing nothing would be a validation
that passes while validating nothing.

---

### A side effect vanishes when the method throws

**Symptom:** a database write performed just before throwing an exception is not there
afterwards. Here: reuse detection revoked a token family, threw 401, and the revocation
was gone — so the victim's replay was correctly rejected while the attacker's token kept
working.
**Cause:** Spring rolls back on `RuntimeException` by default. Any write in the same
transaction is discarded, including one you intended as a security action or an audit
record.
**Fix:** run it in a separate transaction — `@Transactional(propagation = REQUIRES_NEW)`
**in a different bean**, since self-invocation bypasses the proxy, and on a **public**
method, since CGLIB cannot proxy non-public ones and Spring ignores `@Transactional`
there silently.

**Why `noRollbackFor` is the wrong reach:** when the method joins an outer transaction,
the outer boundary's rules govern the commit. You would have to annotate every layer, and
the guarantee breaks the moment someone wraps the call in another transactional method.

**Generalisable:** *write-then-throw in a transaction is always a bug unless the write is
in its own transaction.* Applies to audit logging, security events, failed-login counters,
and anything else meant to record that something went wrong.

---

### `No qualifying bean of type X` when the bean has `@ConditionalOnMissingBean`

**Symptom:** a `@Component` annotated `@ConditionalOnMissingBean(SomeInterface.class)` is
never registered, and everything depending on that interface fails to autowire.
**Cause:** **`@ConditionalOnMissingBean` is only reliable inside auto-configuration
classes.** Spring Boot's documentation restricts it to them explicitly. On a
component-scanned bean the condition is evaluated during scanning, in an order undefined
relative to other user beans, so it can run before the registry is in the state you
assumed.
**Fix:** `@ConditionalOnProperty`, `@Profile`, or an explicit `@Bean` method — anything
whose outcome does not depend on scan order.

**The design lesson is bigger than the annotation.** "Use this implementation unless
another one is present" makes deployment topology an emergent property of the classpath.
Making it a named property means an operator chooses it deliberately and can see what was
chosen. Here, silently selecting in-JVM fanout behind a load balancer would desynchronise
games with no configuration to point at.

---

### `expected single matching bean but found 2`

**Symptom:** a context fails to start because two beans of the same type exist — one of
them yours, one auto-configured.
**Cause:** declaring a bean the framework already provides. Here,
`RedisMessageListenerContainer`: Spring Boot auto-configures one whenever Spring Data
Redis is on the classpath, and a hand-written `@Bean` collided with it.
**Fix:** delete yours and inject the auto-configured bean. Only declare one when you need
behaviour the default does not provide — and then mark it `@Primary` or qualify the
injection point deliberately.

**How to check before writing the bean:** run with `--debug` and read the condition
evaluation report. It lists every auto-configuration that applied, every one that did not,
and why. That is the authoritative answer to "does Boot give me this already?" — reasoning
about it is not.

---

### A property override silently does not apply

**Symptom:** a programmatically started Spring context ignores a property you passed it,
and behaves as if `application.yml` were authoritative. Here, a second instance started
with `chess.realtime.fanout=valkey` kept using in-JVM fanout, so a cross-instance test
exercised two unconnected instances and timed out.

**Cause:** `SpringApplicationBuilder.properties(...)` maps to
`SpringApplication.setDefaultProperties`, which is the **lowest**-precedence property
source — *below* `application.yml`. Any key the application already defines wins. Keys the
application does not define appear to work, which is what makes this so confusing: in this
case the datasource and Redis settings applied and only `fanout` did not.

**Fix:** pass them as command-line arguments — `run("--chess.realtime.fanout=valkey")` —
which is the highest-precedence source. `@SpringBootTest(properties = ...)` and
`@DynamicPropertySource` also override correctly; only the builder's `properties()` is the
trap.

**Guard against it:** assert the override took.

```java
assertThat(context.getEnvironment().getProperty("chess.realtime.fanout"))
        .isEqualTo("valkey");
```

Without that, the day someone changes how the instance is configured, the test goes green
while proving nothing — a test that cannot fail for the reason it exists is worse than no
test.

---

### A player shows offline while they are connected

**Symptom:** the presence badge says offline even though the opponent is in the game and
moves arrive normally.
**Cause:** presence was stored as one flag per (game, user) but written from per-socket
events. During any reconnect the old and new sockets overlap, and if the old one's close is
processed last it overwrites the new one's "online".
**Fix:** store a **set of session ids** per (game, user). Online means the set is non-empty;
only the transition to or from empty is announced. An overlapping reconnect becomes a
no-op.

**Why a Redis set rather than a local counter:** a per-instance count fixes the reconnect
case and reintroduces the identical bug across instances — a player with sockets on two
instances would be announced offline when either closed.

**React StrictMode is how this was found**, and it is worth keeping enabled for exactly
that reason. It mounts, unmounts and remounts every effect in development, which performs
a reconnect on every page load. It did not cause the bug; it made a production race
deterministic.

**Generalisable:** state about an entity that several connections can assert is a
*reference count*, not a boolean. The boolean version is correct until the first overlap,
which is also the first reconnect.

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
