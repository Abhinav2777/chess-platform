# ADR-012: Accept JitPack as a dependency source, scoped to a single group

**Status:** Accepted · **Date:** 2026-09-06
**Related:** ADR-002 (use chesslib rather than writing a rules engine)

## Context

ADR-002 chose `chesslib` for move generation. The build initially declared only
`mavenCentral()`, and dependency resolution failed:

```
Could not find com.github.bhlangonijr:chesslib:1.3.4
```

The version was wrong, but that was not the cause. **chesslib is not published to Maven
Central under this coordinate.** The project's own README instructs consumers to add the
JitPack repository. The groupId is the tell: `com.github.<github-user>` is JitPack's
naming convention, derived from the source repository path rather than a reverse-domain
namespace the author controls. No version of that coordinate would have resolved from
Central.

This turns a version bump into an actual architectural decision, because JitPack is not
Maven Central.

## What JitPack is, and why that matters

JitPack builds artifacts **on demand from GitHub tags**. It is a build service, not a
curated registry. Practical differences from Central:

- **Artifacts are not immutable in the same sense.** A force-pushed or moved tag changes
  what a coordinate resolves to. Central rejects republication of a released version.
- **No staging, signing, or namespace verification.** Central requires proof of control
  over the reverse-domain groupId. JitPack's namespace is "whatever GitHub account you
  have," so `com.github.anyone` is claimable by anyone named `anyone`.
- **It is a build-time availability dependency.** A JitPack outage breaks a cold build.
  Gradle's cache and CI caching hide this most of the time, not always.
- **First resolution may be slow** — JitPack compiles the jar if nobody has requested
  that tag before.

This sits directly against the supply-chain position taken elsewhere in this project,
where the Gradle wrapper jar is checksum-validated in CI precisely because it is a
binary that executes before our own code does.

## Decision

Add JitPack, but as **`exclusiveContent` scoped to `com.github.bhlangonijr`** — not as a
general repository.

```kotlin
exclusiveContent {
    forRepository { maven { name = "jitpack"; url = uri("https://jitpack.io") } }
    filter { includeGroup("com.github.bhlangonijr") }
}
```

The scoping is the decision; adding the repository is just the mechanism. It gives two
guarantees:

1. **JitPack is consulted for that one group and nothing else.** An unfiltered JitPack
   entry means every dependency that misses on Central gets retried against JitPack.
   That is slow, and it is a **dependency-confusion vector**: an attacker who registers
   a GitHub repository matching an internal or typo'd coordinate gets their artifact
   served. Scoping removes the vector entirely.
2. **That group is fetched only from JitPack.** A same-named artifact appearing on
   Central or any mirror cannot substitute itself.

## Alternatives considered

**Find it on Central under a different coordinate.** MvnRepository lists mirrors, but the
author's README documents JitPack, and building on an undocumented mirror coordinate
means depending on something the maintainer does not consider supported.

**Use `kchesslib`**, a Kotlin Multiplatform fork that *is* on Maven Central. Rejected:
it drags the Kotlin stdlib into a Java-only application, and a fork's maintenance is a
second bet on top of the first. Central availability is not worth either.

**Vendor the jar into the repository.** Removes the build-time dependency entirely and
makes the artifact immutable. Rejected for now: a committed binary is the exact thing we
validate the wrapper jar against, and it makes updates manual and invisible. **Kept as
the mitigation if JitPack ever causes a real build failure** — at which point vendoring
with a recorded checksum becomes the better trade.

**Write the rules engine after all.** Rejected — see ADR-002. Twenty hours to avoid one
scoped repository is a bad trade.

## Consequences

- The build now depends on jitpack.io being reachable on a cold cache. Gradle's
  dependency cache and CI caching cover the normal case.
- Dependency locking would pin the resolved artifact hash and neutralise most of the
  mutability concern. **Not done yet** — it belongs with the Renovate/Dependabot work in
  Phase 6, where the whole dependency-hygiene story lands together.
- Anyone reading `build.gradle.kts` sees a non-Central repository. The comment block
  explains why, because an unexplained third-party repository in a build file is exactly
  the thing a security reviewer should flag.

## Interview angle

**Q:** "I see a JitPack repository in your build. Isn't that a supply-chain risk?"
**A:** It is, and it's why it's scoped rather than just added. chesslib is only
distributed through JitPack — the `com.github.bhlangonijr` groupId is the giveaway, it's
derived from the GitHub path rather than a domain the author proved they own. JitPack
builds from tags on demand, so it has none of Central's staging, signing, or namespace
verification. I used `exclusiveContent` so JitPack serves exactly one group and that
group comes from nowhere else. Without the filter, every dependency that misses on
Central would get retried against JitPack, which is a dependency-confusion vector — you
can claim a coordinate just by owning the matching GitHub repo name. If it ever caused a
real outage I'd vendor the jar with a recorded checksum, but I'd rather have the
dependency visible and scoped than invisible and committed.

**Q:** "How would you harden this further?"
**A:** Gradle dependency locking, so the resolved artifact hash is pinned and a moved tag
can't silently change what I build against. That's Phase 6 work alongside Renovate — I'd
rather do dependency hygiene as one coherent piece than scatter it.
