# Spring Boot 4 pre-delivery checklist

**Consulted before shipping any code that touches the framework.** Every line here was
learned by breaking the build, not by reading a guide. Checking this list takes a minute;
each item below cost a round trip.

## Dependency coordinates

- [ ] **Starters, never raw third-party coordinates.** Boot 4 modularised
      auto-configuration into per-technology jars. `org.flywaydb:flyway-core` resolves,
      compiles, accepts every `spring.flyway.*` property — and never runs a migration,
      because `FlywayAutoConfiguration` ships in `spring-boot-flyway`. Use
      `spring-boot-starter-<tech>`.
- [ ] **Renamed starters still resolve as deprecated aliases.** `spring-boot-starter-web`
      compiles fine; the current name is `spring-boot-starter-webmvc`. A stale name gives
      no signal.
- [ ] **Every main starter needs its test companion.** `@WebMvcTest` and
      `@AutoConfigureMockMvc` need `spring-boot-starter-webmvc-test`; `@WithMockUser`
      needs `spring-boot-starter-security-test`.

## Jackson

- [ ] **Boot 4 defaults to Jackson 3** — `tools.jackson.databind.JsonMapper`.
      `com.fasterxml.jackson.databind.ObjectMapper` is Jackson **2**: it compiles, and
      there is no such bean at runtime.
- [ ] **Prefer the framework abstraction.** Return `ProblemDetail` / use message
      converters and let Spring own the Jackson version. Injecting a mapper directly binds
      you to a library whose package moved between majors.

## Spring Security 7

- [ ] `.and()` chaining — **removed**. Lambda DSL only.
- [ ] `authorizeRequests` — **removed**. Use `authorizeHttpRequests`.
- [ ] `AntPathRequestMatcher` / `MvcRequestMatcher` — **removed**.
- [ ] `AccessDecisionManager` — **moved** to `spring-security-access`.
- [ ] Most Security examples online predate all of the above and will not compile.

## JPA / Hibernate

- [ ] **Write entity and migration from the type table in `ARCHITECTURE.md` §4.2.1.**
      Two mismatches so far: `Instant` vs `TIMESTAMPTZ`, `CHAR` vs `VARCHAR`.
- [ ] `Instant` needs `hibernate.type.preferred_instant_jdbc_type: TIMESTAMP_UTC`.
- [ ] Never `CHAR(n)` in PostgreSQL — stored as blank-padded `bpchar`, and Hibernate
      expects `varchar`.
- [ ] **Blast radius:** one entity's mismatch fails the whole persistence unit, so a new
      entity breaks every existing JPA test.

## Bean Validation

- [ ] **`@Positive`, `@Min`, `@Max` apply to numeric types only.** On a `Duration` they
      compile and fail at startup with `HV000030`. Validate in a compact constructor.
- [ ] A constraint that compiles is not a constraint that applies.

## Before shipping — run these

```bash
grep -rn "fasterxml"      --include=*.java backend/src   # Jackson 2 leakage
grep -rn "\.and()"        --include=*.java backend/src   # removed in Security 7
grep -rn "authorizeRequests" --include=*.java backend/src
grep -rn "@MockBean\|@SpyBean" --include=*.java backend/src  # removed; use @MockitoBean
```

## Edits that fail silently

A scripted string replacement that finds no match is a **no-op that reports success**. It
produced subtly incomplete files four times in this project. Verify the *result*, not the
edit — or make the edit fail loudly when its anchor is absent.

- annotations used without their import (a replacement anchored in the wrong file)
- adjacent javadoc blocks (a new one stacked above an existing one)
- try-with-resources variables never referenced (`-Xlint:try` under `-Werror`)
- `@Column` Java type vs migration column type (ARCHITECTURE.md §4.2.1)
- `assertThat((List<?>) x).contains(...)` does not compile — a wildcard cast gives AssertJ a
  capture type. `hasSize` on the same cast does, which is why this slips through.

## Transactions

- [ ] **No `@Transactional` method called on `this` where the annotation must change
      anything.** Self-invocation bypasses the proxy. Benign when the caller already holds a
      compatible transaction; a bug when the callee needed to open one or start a new one.
- [ ] **No write followed by a throw in the same transaction** unless the write has its own
      transaction. Rolled back with the exception. Has shipped twice.
- [ ] **No `REQUIRES_NEW` updating a row the caller locked `FOR UPDATE`.** Hangs until lock
      timeout; PostgreSQL does not report it as a deadlock.
- [ ] **Static constants declared before any static instance that uses them.** Or written as
      constant expressions, which the compiler inlines. A failed static initialiser makes the
      class unloadable for the life of the JVM.

## Adding a state, or loosening an invariant

- [ ] **Grep every accessor of a field that can now be null or take a new value.** Adding
      ABORTED made `result` nullable for a terminal game; the broadcaster's
      `result().name()` would have thrown in an `AFTER_COMMIT` listener — after the commit,
      with no client told. The new state is cheap; the cost is in consumers that assumed
      the old states were all there were.
- [ ] **Look for exhaustive `switch`es over the enum** you extended. A switch *expression*
      without `default` fails to compile, which is the good outcome; a switch *statement*
      silently does nothing for the new value.

## Tests and scheduled jobs

- [ ] **A `@Scheduled` job keeps running in every cached test context.** Disabling it with
      a property in one test class does nothing about another class's context that is still
      alive and pointed at the same database. Disable it in the shared base class.

## Debugging

- [ ] Many tests failing at once with `DefaultCacheAwareContextLoaderDelegate` is **one**
      bug. Read the first trace only; the rest are cached-failure replays.
- [ ] `--debug` prints the condition evaluation report — the tool for "why didn't my
      auto-configuration apply". An auto-config that is absent from the classpath does not
      appear even in negative matches, which is itself the answer.
- [ ] `testLogging.exceptionFormat = FULL` is required or exception **messages** are
      discarded and you get only a class name and line number.
