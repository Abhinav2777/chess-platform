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

## Debugging

- [ ] Many tests failing at once with `DefaultCacheAwareContextLoaderDelegate` is **one**
      bug. Read the first trace only; the rest are cached-failure replays.
- [ ] `--debug` prints the condition evaluation report — the tool for "why didn't my
      auto-configuration apply". An auto-config that is absent from the classpath does not
      appear even in negative matches, which is itself the answer.
- [ ] `testLogging.exceptionFormat = FULL` is required or exception **messages** are
      discarded and you get only a class name and line number.
