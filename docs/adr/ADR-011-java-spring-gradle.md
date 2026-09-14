# ADR-011: Java 25 LTS + Spring Boot 4.1.x + Gradle

**Status:** Accepted · **Date:** 2026-09-06
**Supersedes:** two earlier drafts of this ADR, both wrong. Draft 1 said Java 21 +
Spring Boot 3.5.x + Maven, written from memory. Draft 2 corrected to Boot 4.0.x after
checking. Draft 3 (this one) corrects again to **4.1.x** and switches to Gradle.
The correction history is kept deliberately — see § Lesson.

**Version numbers below were verified on 2026-09-06 and will drift. Re-verify at
start of work.**

## Context

Two questions were on the table: Maven vs Gradle, and Boot 4 vs Boot 3.5.

The Boot question was framed as "does Boot 4 improve learning/interview value enough to
justify migration friction?" Checking the support timeline showed the framing was wrong.

## The facts that decided it

- **Spring Boot 3.5 reached open-source end of life on 30 June 2026.** The final OSS
  release was 3.5.16. 3.5 was the last of the 3.x line, so **every 3.x branch is now out
  of OSS support** — no free security patches.
- **Spring Boot 4.0.x OSS support ends December 2026.** That is inside this project's
  timeline. 4.0 is already the wrong target.
- **4.1.x is the current supported line.** It requires Java 17 minimum and supports up
  to Java 26, on Spring Framework 7.0.9+.

## Decision

- **Spring Boot 4.1.x** (latest patch)
- **Java 25 (LTS)**
- **Gradle 9.7.1** with the Kotlin DSL and a version catalog
- **AWS SDK v2 `SqsClient` directly**, not Spring Cloud AWS
- Virtual threads enabled

## Rationale — Boot version

**Does Boot 4 materially improve learning or interview value? No. Essentially zero.**
Nothing this project teaches touches version-specific framework surface: the WebSocket
protocol, optimistic locking, the clock, Valkey pub/sub, the outbox, Kubernetes, AWS and
observability are all identical on either version. On the merits of learning alone, the
honest answer is that it makes no difference.

But that was the wrong question. **3.x is EOL as of two months ago.** The choice is not
between "current" and "stable" — it is between supported and unsupported. Starting a
greenfield project in September 2026 on a framework with no security patches is not a
decision that survives the question "why?", and 4.0 would need re-deciding in December.

**The friction concern was also overstated, and the reason is instructive.** Published
estimates for a 3.5 → 4.x enterprise migration run to hundreds of hours across dozens of
breaking changes. Those are *migration* costs — removed deprecations, Jakarta EE 11,
Jackson 2 → 3, custom auto-configuration touching internal APIs. **We are not migrating.**
Greenfield on 4.1 pays almost none of it. The residual friction is that older tutorials
and Stack Overflow answers target 3.x and will be wrong. That is a documentation problem,
not an engineering one.

## Rationale — Gradle over Maven

Draft 1 argued Maven on *portfolio legibility*: an interviewer can scan a `pom.xml` in
ten seconds. That argument is real but small, and it loses to the project owner's
existing fluency. Sixteen weeks of friction against your own build tool is a genuine
cost with no offsetting benefit, and Gradle is not exotic in Java shops.

Using the Kotlin DSL with a **version catalog** (`gradle/libs.versions.toml`): versions
live in one file, the IDE completes them, and the dependency list stays readable — which
recovers most of the legibility argument that favoured Maven.

## Rationale — Gradle 9.7.1 specifically

The first draft of this ADR said "Gradle" and pinned no version, leaving
`gradle wrapper --gradle-version <current>` in the setup notes. That is the same class of
mistake as the framework versions above: a decision that looks made but isn't.

Four constraints, intersecting at 9.7.1:

| Constraint | Requires | Source |
|---|---|---|
| Java 25 as the Gradle daemon JVM | >= 9.1.0 | Gradle compatibility matrix |
| Java 25 as a toolchain target | >= 9.1.0 | Gradle compatibility matrix |
| Spring Boot 4.1 Gradle plugin | 8.14+ or 9.x | Spring Boot Gradle plugin reference |
| Kotlin DSL without the `Option` annotation argument-order regression | != 9.7.0 | Gradle 9.7.1 release notes |
| Repository-handling CVE fixes | >= 9.3.0 | Gradle 9.3.0 release notes |

**Java 25 binds harder than Spring Boot, which is the trap.** Reading only the Spring
documentation suggests 8.14 is acceptable. On JDK 25, Gradle 8.14.3 fails with
`Unsupported class file major version 69` inside `_BuildScript_` — an error that names
the build script and Groovy rather than the JDK, so it reads like a script bug. Running
Gradle 8 on Java 25 was requested upstream and closed as not planned; there is no 8.x
escape hatch.

**The pin is enforced, not documented.** The version lives in
`gradle/libs.versions.toml`; the root build's `wrapper` task reads it, and a
`verifyGradleVersion` task fails CI if `gradle-wrapper.properties` has drifted. Without
that, someone running `./gradlew wrapper --gradle-version X` silently makes the catalog
entry a comment — and a version pinned only in prose is exactly what this ADR already
got wrong once.

## Rationale — Java 25 over 21

Both LTS, so not a support-window question. The specific reason is **JDK 24's JEP 491,
which removed virtual-thread pinning on `synchronized` blocks.** On Java 21, a virtual
thread blocking inside a `synchronized` block pins its carrier, which under load looks
like throughput collapsing while CPU sits idle — a genuinely unpleasant thing to
diagnose, in exactly this project's connection-heavy shape. Choosing the LTS without
that failure mode is free.

## Rationale — no Spring Cloud AWS

Removes the dependency most likely to lag a Spring major. It is also the better call on
merit: writing the SQS polling loop directly means dealing with visibility timeouts,
long polling, and batch deletes rather than having `@SqsListener` hide them — and
explaining those mechanics is a stated project goal.

## Consequences

- Jackson 3 is the default in Boot 4. Serialisation behaviour differs from Jackson 2 in
  places, so the WebSocket envelope needs explicit round-trip tests rather than assumed
  behaviour.
- Spring Security 7 ships with Framework 7. Config DSL details must be verified against
  the reference docs when the security chain is written in Phase 1, not copied from
  older examples.
- Boot 4 modularised the codebase into smaller jars. **Starter coordinates must be
  confirmed against `start.spring.io`, not assumed.**
- Undertow support was dropped in Boot 4 (Servlet 6.1 baseline). We use Tomcat; no impact.
- 4.1's own OSS window will eventually close too. Note the date when confirmed; a
  portfolio project does not need patching forever, but the README should not claim
  currency it has lost.

## Lesson

Three drafts, two of them wrong, both wrong because a version number was written from
memory instead of checked. **No framework, library, or runtime version enters this
repository without being verified against the vendor's own documentation on the day it
is written, and without carrying the date it was checked.** This applies to every
version string in `build.gradle.kts` and `libs.versions.toml`.

A second-order lesson worth keeping: the original question was "is the new version worth
the friction?" and the answer turned out to be "the old version is unsupported," which
no amount of reasoning about friction would have surfaced. Checking the support timeline
should be the *first* step in a framework-version decision, not a detail.

## Interview angle

**Q:** "Why Spring Boot 4?"
**A:** Because 3.x went end-of-life in June 2026 — 3.5 was the last of the line and
there are no more OSS security patches. It wasn't really a choice between old and new,
it was between supported and unsupported. Boot 4 didn't buy me anything technically for
this project; almost nothing I built touches version-specific framework surface. The
migration horror stories you hear are about moving large existing estates across Jakarta
EE 11 and Jackson 3. Greenfield pays almost none of that.

**Q:** "Why Java 25 rather than 21?"
**A:** Both are LTS. The specific reason is that JDK 24 fixed virtual-thread pinning on
`synchronized` blocks. On 21, a virtual thread that blocks inside a `synchronized` block
pins its carrier thread, and in a connection-heavy workload that shows up as throughput
collapsing while CPU sits idle. Since I was choosing between two LTS releases anyway, I
took the one without that trap.

**Q:** "How do you keep the build reproducible?"
**A:** The Gradle version is pinned in the version catalog, the wrapper task reads it so
regenerating can't drift, and a CI step fails the build if the committed
`gradle-wrapper.properties` disagrees with the pin. A Java toolchain declaration means
Gradle downloads and compiles against JDK 25 regardless of what's on anyone's PATH, so
CI and local machines compile identically. And the wrapper jar is checksum-validated in
CI against Gradle's published releases — it's a committed binary that executes before
any of my code does, so it's a supply-chain surface worth treating as one.

**Q:** "Why not Spring Cloud AWS for SQS?"
**A:** Two reasons. It tracks Spring Boot releases, so it was the dependency most likely
to lag a new major and block me. And using the SDK directly meant I actually had to
handle visibility timeouts, long polling and batch deletes myself — which is the part
worth understanding.
