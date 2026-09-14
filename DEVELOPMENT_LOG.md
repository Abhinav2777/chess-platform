# DEVELOPMENT LOG

Chronological record. Newest entries at the top. Each entry: what was done, what was
decided, what was learned, what went wrong.

---

## 2026-09-14 — Milestone 1.2: authentication over HTTP

Delivered as a git patch rather than an archive — first use of the new handoff workflow.

**Decisions worth defending**

- **Reuse detection is the point of rotation** (ADR-013). Rotation alone only shortens
  the window; it is what makes theft *detectable*, because a used token reappearing
  cannot happen legitimately. Revoking the whole family is what makes detection useful —
  otherwise the attacker keeps the token they just minted.
- **Conditional UPDATE for the rotation claim.** `WHERE used_at IS NULL` rather than
  `if (!token.isUsed())`. Third instance of the same principle in this codebase, after
  `uq_users_username` and `PRIMARY KEY (game_id, ply)`: enforce the invariant where
  writes are serialised.
- **SHA-256 for tokens, bcrypt for passwords.** bcrypt's cost defends low-entropy
  secrets; a 256-bit CSPRNG token has no dictionary. bcrypt here would be ~250ms of CPU
  per refresh — a DoS lever, not a security gain.
- **Custom JWT filter over the OAuth2 resource-server DSL.** Phase 2 needs
  `JwtService.verify` callable with no servlet filter chain (WebSocket first-message
  auth), so the service exists regardless; a 30-line filter over it beats two auth paths.
  Crypto is still Nimbus — never hand-rolled.
- **Access token in memory, refresh token in an httpOnly SameSite=Strict cookie.**
  Different threats, different storage: XSS cannot read the refresh token, CSRF cannot
  use it, and the token XSS *could* steal expires in 15 minutes.
- **`/users/me`, not `/users/{id}`.** Identity comes from the token, so IDOR is
  unrepresentable rather than merely guarded against.

**Accepted cost, recorded so it is not later mistaken for a bug:** a genuine double-click
on refresh logs the user out, because the second request is indistinguishable from reuse.
A grace window would fix it and would also be a hole an attacker can aim at.

**Unverified:** none of this has been compiled. The one new dependency is
`spring-security-oauth2-jose`; if it fails to resolve, that is the first thing to check.

---

## 2026-09-14 — Milestone 1.1 complete; starter coordinates resolved properly

**Milestone 1.1 is green.** All identity integration tests pass, and Flyway is confirmed
applying V1 against both the Testcontainers database and the Compose database.

Incidentally, the empty Compose database was a third independent confirmation that Flyway
had genuinely never run — the integration suite uses its own throwaway container, so a
green suite says nothing about the state of the local dev database. Worth remembering as
a general point: **test isolation means test success is not environment verification.**

**Starter coordinates settled from the source, not by diffing.** Rather than delegating
the `start.spring.io` check, the Boot 4.0 migration guide was read directly. Findings:

| Was | Now | Why it mattered |
|---|---|---|
| `org.flywaydb:flyway-core` | `spring-boot-starter-flyway` | raw coordinate carries no auto-config — silent no-op |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | old name is a deprecated alias, so it compiled and gave no signal |
| — | `spring-boot-starter-webmvc-test` | `@WebMvcTest` / `@AutoConfigureMockMvc` moved to `o.s.boot.webmvc.test.autoconfigure` |
| `org.springframework.security:spring-security-test` | `spring-boot-starter-security-test` | `@WithMockUser` / `@WithUserDetails` need it to function |

The last two would have broken Milestone 1.2 on its first test, and — like Flyway —
would have failed in a way that blamed the test code rather than the dependency.

**The rule that generalises.** Boot 4 modularisation creates two distinct traps, and
neither produces a compile error:

1. A raw third-party coordinate resolves and compiles but ships no auto-configuration.
2. A renamed starter still resolves as a deprecated alias.

In both cases the build is green and the behaviour is absent. **A coordinate that
resolves is not a coordinate that works** — the only reliable check is the vendor's own
starter list.

**Still open:** whether the `Instant`/`TIMESTAMPTZ` fix needed the `@JdbcTypeCode`
annotation or whether the global `preferred_instant_jdbc_type` property was sufficient.
Untested either way; two-minute experiment described in PROJECT_STATE next-tasks.

---

## 2026-09-14 — Root cause: Flyway was never auto-configured

**The actual error**, once `testLogging.exceptionFormat = FULL` made it visible:

```
org.hibernate.tool.schema.spi.SchemaManagementException: Schema validation: missing table [users]
```

Not a column type mismatch. The table did not exist.

**Cause.** Spring Boot 4 modularised auto-configuration into per-technology jars.
`FlywayAutoConfiguration` now ships in `spring-boot-flyway`, published via
`spring-boot-starter-flyway`. The build declared raw `org.flywaydb:flyway-core`, which
puts Flyway on the classpath with no auto-configuration behind it. The application starts
cleanly, accepts every `spring.flyway.*` property, and migrates nothing.

**Flyway had therefore never run — including during Phase 0.** `/actuator/health` was
green because the `db` indicator validates a connection, and an empty database has a
perfectly good connection. Phase 0 step 9 (`\dt` should list users/games/moves) was the
check that would have caught it.

**Fix:** `org.springframework.boot:spring-boot-starter-flyway` + `flyway-database-postgresql`.

**Three lessons, in descending order of value**

1. **An absent component is more dangerous than a broken one.** A broken migration tool
   fails loudly at startup. A missing one hands you an empty database and a green health
   check, and the failure surfaces phases later, somewhere else, blaming something else.
   Anything whose absence is indistinguishable from success needs an explicit assertion —
   hence the new `SchemaMigrationIntegrationTest`.
2. **A risk written down but not gated on is not managed.** `PROJECT_STATE.md` §4 has said
   since Phase 0: *"Starter coordinates may have changed. Boot 4 modularised the codebase.
   Generate a project at start.spring.io and diff the build file. That tool is ground
   truth; this repo is not."* That verification was Phase 0 step 5. It was not confirmed
   done, and I proceeded to Milestone 1.1 anyway. The risk register was accurate and
   useless. **It is now a hard gate in ROADMAP Phase 0's definition of done.**
3. **The condition evaluation report is the tool for "why didn't my auto-configuration
   apply".** `--debug` prints which conditions matched. Absent auto-configuration does not
   appear even in negative matches, which is itself the diagnosis.

**Still unverified:** the `Instant`/`TIMESTAMPTZ` fix from the previous round. Validation
never reached column types because the table was missing, so both the
`preferred_instant_jdbc_type` property and the `@JdbcTypeCode` annotation are untested.
Once the suite is green, remove the annotation and re-run — if it still passes, the global
property works and the annotation was redundant.

---

## 2026-09-14 — Schema validation failure on the first entity

**Symptom:** all 12 identity integration tests failed. Eleven of them were noise —
Spring caches a failed application context and replays the failure, so only the first
stack trace was real.

**Cause:** `ddl-auto: validate` rejected `User`. Hibernate maps `java.time.Instant` to
plain `TIMESTAMP` by default; `users.created_at` is `TIMESTAMPTZ`. Mismatch, refuse to
start.

**Fix:** `hibernate.type.preferred_instant_jdbc_type: TIMESTAMP_UTC` — global, so it
covers `games.last_move_at`, `finished_at` and every future timestamp. The alternatives
were worse: changing columns to `TIMESTAMP` discards the offset and makes timestamps
depend on server zone, which is unacceptable in a system whose clock decides game
outcomes; `@JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)` per field works but has to be
remembered every time, and forgetting once is a startup failure.

**The lesson worth keeping.** This setting was in `application.yml` throughout Phase 0
and Phase 0 passed — because there were no entities, so `validate` had nothing to
validate. **A check that passes over an empty subject set has told you nothing.** The
same trap applies to the ArchUnit rules (`allowEmptyShould(true)` until Phase 1 gave them
classes to inspect) and to `-Werror`, which only started failing once real code existed.
Phase 0's green build was weaker evidence than it looked.

**Also worth keeping:** when many tests fail at once with
`DefaultCacheAwareContextLoaderDelegate`, that is one bug wearing N costumes. Read the
first trace; the rest are cache replays.

**Follow-up — the build was configured to hide the answer.** The first fix attempt did
not resolve it, and the diagnostic `grep` returned nothing, because Gradle's default
`testLogging.exceptionFormat` is `SHORT`: it prints the exception class and line number
and discards the message. Hibernate's validation errors name the exact column and both
types, so the message was the entire diagnosis and it was being thrown away by my own
build config. Set `exceptionFormat = FULL` and `showCauses = true`.

Generalisable and worth more than the bug itself: **when a second diagnostic attempt
produces no new information, stop hypothesising and fix the observability.** Two rounds
of guessing cost more than the one-line logging change would have.

---

## 2026-09-14 — Handoff workflow changed; wrapper added to the repository

**Problem being solved.** Producing an archive after every milestone meant downloading,
extracting and replacing the tree constantly, and it twice caused work to be done against
a stale copy — once because three different archives shared the filename
`chess-platform-phase0.tar.gz`, and once because two different files named
`build.gradle.kts` collided in a flat output directory. Both times the file *contents*
were correct and the *delivery* made them useless.

**New workflow** (recorded in `PROJECT_STATE.md` §0): changes are delivered in chat and
applied in place. An archive is produced only at a major phase boundary, on explicit
request, or when technically necessary. Milestone size is unchanged — fewer handoffs
means larger coherent units, not shallower ones.

**Wrapper.** `gradlew` and `gradle/wrapper/gradle-wrapper.properties` (pinned 9.7.1) are
now committed. `gradle-wrapper.jar` still is not, and deliberately: it is a binary that
can only come from a real Gradle distribution, and handing over an executable of
unverified provenance would contradict the wrapper-checksum validation added to CI for
exactly that reason. `./gradlew wrapper` generates it once, locally, after which it lives
in git history and never travels again. Same for `gradlew.bat`.

**Standing recommendation: initialise git.** Every delivery failure in this project so far
is one `git status` away from being a non-event.

---

## 2026-09-06 — Phase 0 verified complete; Phase 1 Milestone 1.1 (identity domain)

**Phase 0 closed.** Compile, compose stack, health check, Flyway V1, integration tests
and CI all green on the dev machine. Estimated ~5 hours against a 3–5 hour budget.

**Milestone 1.1 built:** `common/error`, `common/id/Uuid7`, `identity` domain +
persistence + registration + authentication, `IdentityFacade`, password and clock config,
and tests. No HTTP layer — that is 1.2. Building the domain first meant the registration
race got solved in the domain rather than papered over in a controller.

**Spring Security 7 surface verified before writing any of it.** The hard removals are
`.and()` chaining, `authorizeRequests`, `AntPathRequestMatcher`/`MvcRequestMatcher`, and
`AccessDecisionManager` (moved to a separate module). The lambda DSL and
`authorizeHttpRequests` survive. Also relevant for 1.2: `SecurityJackson2Modules` is
replaced by `SecurityJacksonModules` on a Jackson 3 `JsonMapper.Builder`.

**Design decisions worth remembering**

- **The registration pre-check is not a correctness mechanism.** Two threads can both
  read "username free" before either writes. Only `uq_users_username` serialises them.
  The pre-check exists solely to produce a precise error in the common case; the
  constraint violation is the real guard. Same shape as move idempotency (ADR-005), and
  now proven by a 16-thread race test asserting exactly one winner.
- **`saveAndFlush`, not `save`.** `save` defers the INSERT to commit, which throws the
  constraint violation outside the try block and surfaces as a 500 instead of a 409.
- **Timing-attack defence in authentication.** Identical messages for "no such user" and
  "wrong password" close the enumeration oracle in the response body but not in the
  clock: returning early when no user exists is ~100 ms faster than hashing. So we hash
  against a dummy hash regardless. Deliberately no short-circuit boolean.
- **`Locale.ROOT` on `toLowerCase`.** Under a Turkish locale `"I".toLowerCase()` is a
  dotless `"ı"`, so a user registering as `IVAN` on a Turkish-locale server becomes
  unfindable everywhere else.
- **UUIDv7 hand-written.** ~25 lines of exactly-specified bit layout with its own tests
  is a smaller risk than another unverifiable coordinate, and the monotonicity behaviour
  is something we wanted to control rather than inherit. Clock regression holds the
  previous timestamp rather than emitting a smaller one.

**Two defects in my own Phase 0 scaffolding, found by using it**

1. The `entitiesDoNotLeak` ArchUnit rule forbade any class outside `..domain..` or
   `..internal..` from touching an entity — which rejects a module's own facade mapping
   its own entity to a DTO, the exact pattern the rule was written to encourage.
   Rescoped to forbid entities crossing *module* boundaries. **An architecture rule that
   fires on correct code gets suppressed within a week and then guards nothing.**
2. `-Xlint:all -Werror` fails on every exception class, because `RuntimeException` is
   `Serializable` and lacks a `serialVersionUID`. Excluded `-serial` and `-processing`
   rather than adding four lines of version UID ceremony to classes that are never
   serialised.

Both were only findable by writing real code against the scaffolding. Worth noting as a
pattern: **Phase 0 deliverables are hypotheses until Phase 1 exercises them.**

**Next:** Milestone 1.2 — Security filter chain, JWT issuance, rotating refresh tokens
(needs a `V2__refresh_tokens.sql` migration), auth endpoints, RFC 7807 problem details.

---

## 2026-09-06 (later) — chesslib resolution failure; toolchain gap found

**Symptom**

`./gradlew :backend:test` failed with `Could not find com.github.bhlangonijr:chesslib:1.3.4`.
Everything else on the compile classpath resolved.

**Root cause — two problems in one error**

The version was wrong (1.3.4 was invented; current is **1.3.7**), but that was not the
cause. **chesslib is not published to Maven Central under this coordinate at all.** It is
distributed via JitPack, per the project's own README. No version would have resolved
from `mavenCentral()`.

The tell was in the coordinate the whole time. `com.github.<github-user>` is JitPack's
naming convention — it is derived from the GitHub repository path, not a reverse-domain
namespace the author proved they control. A groupId of that shape should trigger a
repository check before a version check.

Worse: `backend/build.gradle.kts` carried a comment asserting *"chesslib publishes to
Maven Central."* A confidently wrong comment is more expensive than no comment, because
it redirects the next person away from the actual cause.

**Fixed**

- chesslib → 1.3.7 (licence also corrected: Apache 2.0, not MIT as previously recorded).
- JitPack added as `exclusiveContent` scoped to `com.github.bhlangonijr`. **ADR-012**
  records why the scoping is the decision and adding the repository is just the
  mechanism: an unfiltered JitPack entry retries every dependency miss against it, which
  is a dependency-confusion vector, since a coordinate there is claimable by whoever owns
  the matching GitHub repo name.

**Second defect, found while investigating**

`./gradlew javaToolchains` reported only JDK 21. `settings.gradle.kts` had **no toolchain
resolver**, so the Java 25 toolchain declaration was a requirement nothing could satisfy:
the build would have failed with `No matching toolchains found` the moment chesslib was
fixed. Added `foojay-resolver-convention` 1.0.0 (a settings plugin, not a project plugin;
versions before 1.0.0 cap out at Gradle 8.14.x, so the 1.x line is required on 9.7.1).

**The ordering fact worth keeping**

Dependency resolution runs *before* compilation. A build that dies on a missing
dependency has proven nothing about its toolchain, its compiler flags, or its source. The
report that this run "got past the Java 25 toolchain issue" was optimistic — the
toolchain was never reached. Generally: **a failing build only tells you about the first
gate it hit.**

**Version errors so far: four** (Boot 3.5, Boot 4.0, Gradle unpinned, chesslib). Worth
being precise about the remedy, because the obvious one is wrong: **Renovate would not
have caught this.** Renovate updates coordinates that already resolve; it cannot fix a
coordinate pointing at the wrong repository. The actual root cause is that dependency
resolution cannot be exercised in the environment where these files are written — every
coordinate is unverified until a real build runs. That is a standing limitation, not a
lapse of care, and the mitigation is sequencing: **`./gradlew :backend:test` is the gate
that must pass before any further scaffolding is trusted.**

**Next:** step 4 of PROJECT_STATE §10 — first successful compile. Then the compose stack.

---

## 2026-09-06 (evening) — Gradle version pinned; two scaffolding defects fixed

**The defect**

The Phase 0 scaffolding shipped with **no Gradle version anywhere**. `libs.versions.toml`
had no entry and SETUP.md said `gradle wrapper --gradle-version <current>` with a literal
placeholder. Caught on export to the Arch machine, when the wrapper turned out not to
exist and there was nothing to tell you what version to generate.

This is the same failure mode as the Spring Boot version, twice over: a decision that
*looked* made because it was mentioned, but was never actually fixed to a value.

**Resolved: Gradle 9.7.1.** The intersection of four constraints — Java 25 as the daemon
JVM needs >= 9.1.0; Spring Boot 4.1's plugin accepts 8.14+ or 9.x; 9.7.0 has a Kotlin DSL
regression that Gradle itself says to skip; 9.3.0 carried repository-handling CVE fixes.
Full table in ADR-011.

**The trap worth remembering:** Java 25 binds harder than Spring Boot here. Reading only
the Spring docs suggests Gradle 8.14 is fine. On JDK 25 that fails with
`Unsupported class file major version 69` inside `_BuildScript_` — an error that blames
the build script and Groovy, not the JDK, so it sends you debugging the wrong thing.
Gradle 8 on Java 25 was requested upstream and closed as not planned.

**Made the pin enforceable rather than documentary**

Pinning a version in a file nobody checks is how this went wrong in the first place. So:
the root `wrapper` task reads the version from the catalog (no `--gradle-version` flag to
get wrong), and a `verifyGradleVersion` task fails CI if `gradle-wrapper.properties` has
drifted from it. The match uses a trailing hyphen (`gradle-9.7.1-`) so `9.7.11` cannot
satisfy a `9.7.1` pin — a prefix collision a naive `contains` would miss.

**Two defects found while fixing this**

1. `.gitignore` was still Maven-era: it ignored `target/` and `maven-wrapper.jar` and had
   no `build/` or `.gradle/`. The first build would have produced hundreds of untracked
   class files. Rewritten, with explicit negations so `gradle-wrapper.jar` is committed —
   a clone without it cannot build, and a global gitignore or stray `*.jar` rule would
   otherwise drop it silently, failing only on someone else's machine.
2. CI did not validate the wrapper jar. It is a committed binary that executes before any
   of our own code compiles, which makes it a supply-chain surface. Now checksummed
   against Gradle's published releases, before setup-gradle runs.

**Learned**

A version that is "recommended" in prose is not pinned. Three times now the same shape:
Boot 3.5 from memory, Boot 4.0 without checking the support window, Gradle not at all.
The fix is not more care — it is making the pin machine-checkable, which is what
`verifyGradleVersion` does and what the Boot version still lacks. **Open follow-up:
Renovate or Dependabot on the catalog would close the remaining gap.** Worth ~30 minutes
in Phase 6 alongside the rest of CI.

**Next:** bootstrap the wrapper on the Arch machine, verify the build boots, then Phase 1
Milestone 1 (`identity`).

---

## 2026-09-06 (later) — Phase 0: build tooling, framework version, scaffolding

**Done**
- Switched Maven → **Gradle** (Kotlin DSL, version catalog, Java toolchain).
- Corrected the Spring Boot target **again**, to 4.1.x.
- Wrote the scaffolding: build files, compose stack, Spring config, structured logging,
  `V1__baseline.sql`, application entry point, ArchUnit boundary test, CI workflow.

**The Boot 4 question, and why the framing was wrong**

The question asked was: *does Boot 4 improve learning/interview value enough to justify
migration friction?* The honest answer to that question is **no — essentially zero
improvement.** Nothing this project teaches touches version-specific framework surface.

But checking the support timeline before answering showed the question didn't apply.
**Spring Boot 3.5 reached OSS end-of-life on 30 June 2026, and 3.5 was the last of the
3.x line.** Every 3.x branch is now unsupported. And 4.0.x support ends December 2026 —
inside this project's own timeline — so even the version chosen yesterday was wrong.

So the real choice was never "new vs stable", it was "supported vs unsupported", and the
right target is 4.1.x. No amount of reasoning about migration friction would have
surfaced that. **Checking the support timeline should be step one in any framework
version decision, not a detail.**

The friction concern also turned out to be overstated, for an instructive reason. The
alarming migration estimates in circulation are for moving *large existing estates*
across Jakarta EE 11 and Jackson 3 — removed deprecations, custom auto-configuration
touching internal APIs. Greenfield pays almost none of that. What remains is that older
tutorials target 3.x and will be wrong, which is a documentation problem.

**Gradle over Maven**

Draft 1 of ADR-011 argued Maven on portfolio legibility — an interviewer can scan a
`pom.xml` quickly. Real, but small, and it loses to the owner's existing fluency. Sixteen
weeks of friction against your own build tool is a genuine cost with nothing on the other
side. The version catalog recovers most of the legibility argument anyway.

**Deliberate choices in the scaffolding worth remembering**

- `ddl-auto: validate`, never `update`. Flyway owns the schema in every environment. The
  app refuses to start if entities and migrations have drifted — which is the failure you
  want at boot, not during a query at 3am.
- Hikari pool at 10, not a bigger number. A pool larger than the database can serve turns
  connection starvation into database thrashing, which is harder to diagnose. `pods x
  pool_size` against RDS `max_connections` is the real ceiling. Measure in Phase 9.
- `test` and `integrationTest` split. A suite that takes four minutes stops being run
  locally, and a feedback loop nobody uses is worse than none.
- Valkey runs with persistence **off** locally. It holds nothing unrecoverable
  (ADR-004), and a cache surviving restarts would let us accidentally depend on that.
- `ck_games_result_consistency` in the schema: a finished game must have a result, an
  active one must not. Enforced in the database so no code path — including a future bug
  or a manual fix — can produce a half-finished game.
- The ArchUnit test was written **before** any module exists. Retrofitting boundary rules
  onto real code means starting with violations to grandfather in, and a rule with
  exceptions is not a rule.

**Not done — and this matters**

None of it has been built. The environment it was written in has no access to Maven
Central or the Gradle distribution server, so no dependency was resolved and nothing
compiled. The starter coordinates in particular are conventional but unverified against
Boot 4's modularised jar layout. `PROJECT_STATE.md` §4 lists the risks in order and
`start.spring.io` is the ground truth to check them against.

**Next:** verify the build, then Phase 1 Milestone 1 (`identity`).

---

## 2026-09-06 — Phase 0: specification review and architecture

**Done**
- Reviewed `MASTER_PROJECT_SPEC.md` in full.
- Wrote `ARCHITECTURE.md`, `ROADMAP.md`, `PROJECT_STATE.md`, ADRs 001–011.
- Defined the repository structure.

**Contradictions found in the specification**

1. *Observability is scheduled after the deadline that requires it.* The phase plan puts
   observability in Phase 9 (weeks 13–16), while the HARD PORTFOLIO DEADLINE requires it
   by week 12 — and a separate section says "design proper observability from early
   stages." **Resolution:** logging and metrics become cross-cutting from Phase 1;
   Phase 9 keeps tracing, dashboards, and load testing.

2. *"Game Service → SQS → Rating/Notification/Analytics workers" implies microservices*
   while the spec elsewhere mandates a modular monolith. **Resolution:** workers are the
   same artifact under a `worker` Spring profile, deployed separately. Independent
   scaling without a second codebase (ADR-001).

3. *Phase 7 (AWS) followed by Phase 8 (Kubernetes) implies deploying the system twice*,
   costing ~15 hours and ~$73/month for an EKS control plane, against a project budget
   under $50. **Resolution:** ECS Fargate for the production path, `kind` for Kubernetes,
   EKS for a 2–3 day proving window (ADR-010).

4. *The phase budgets sum correctly (128–180 h) but contain no learning time.* For an
   engineer new to Terraform and Kubernetes this understates Phases 7–8 substantially.
   **Resolution:** roadmap re-costed at 135–175 h with an explicit cut order.

**Decided**
- No distributed lock anywhere in the move path. The database provides the guarantee;
  Redlock would add a TTL-expiry failure mode without removing the need for the
  constraint (ADR-005).
- The clock is computed, not ticked, and timestamped by PostgreSQL rather than the
  application server — eliminating inter-pod clock skew (ADR-006).
- Raw WebSocket over STOMP, because Spring's simple broker does not fan out across
  instances and fixing that means adding RabbitMQ purely as a relay (ADR-003).
- Snapshot-on-reconnect rather than delta replay, which is what makes unguaranteed
  Pub/Sub acceptable (ADR-007).

**Corrected**
- ADR-011 was initially drafted as Java 21 + Spring Boot 3.5.x from memory. Checking the
  official sources showed Spring Boot 4.0 has since gone GA on Spring Framework 7 /
  Jakarta EE 11, with 4.1 in RC, and that Java 25 is now an LTS with first-class Boot 4
  support. ADR-011 was rewritten. **Lesson: never write a framework version into a
  decision record without verifying it against the vendor's own documentation on the
  day.** Every version number in this repository carries the date it was checked.
- The correction changed two other things. Java 25 rather than 21 because JDK 24's
  JEP 491 removed virtual-thread pinning on `synchronized` blocks — a failure mode that
  presents as throughput collapse at idle CPU, which is exactly what this workload would
  have hit. And Spring Cloud AWS was dropped in favour of the AWS SDK v2 `SqsClient`
  directly, to remove the one dependency most likely to lag a new Spring major.

**Learned / worth remembering**
- ADR-005 and ADR-007 are the two decisions most likely to be challenged in an
  interview, and both have a clean answer that turns the challenge into a strength.
- The `turn_deadline` column exists solely so the timeout sweeper is an index scan
  rather than a table scan. Derived values cannot be indexed usefully.

**Next:** Phase 0 scaffolding — Maven project, compose stack, booting app, CI.
