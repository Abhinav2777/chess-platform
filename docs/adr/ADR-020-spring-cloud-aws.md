# ADR-020: Spring Cloud AWS 4.x for SQS

**Status:** Accepted · **Date:** 2026-09-29
**Amends:** ADR-011 ("AWS SDK v2 direct, not Spring Cloud AWS") — for SQS.

## Context

Milestone 5.1 was built on the AWS SDK directly, as ADR-011 decided: Spring Cloud AWS is an
adapter that must track every Spring Boot major, and this project had already been bitten by
tooling lagging the platform (ArchUnit vs Java 25, 4.1b). The project owner asked to use
Spring Cloud AWS instead, citing `spring-cloud-aws-starter-sqs:3.4.0`.

Checked before changing anything:

| Version | Built on | On this project's Boot 4.1 |
|---|---|---|
| 3.4.0 | Spring Cloud Commons 4.3 — **Boot 3.5** (EOL June 2026) | Wrong major. Boot 4 moved auto-configuration into per-technology modules; a Boot 3 starter resolves and then fails or silently does nothing (BOOT4_CHECKLIST). |
| **4.1.1** | Spring Cloud Commons 5.0.3 — **Boot 4.0.8**, AWS SDK 2.54.3 | One Boot *minor* ahead of what it is tested on. Proven by the suite, not assumed. |

## Decision

**Spring Cloud AWS 4.1.1** (`spring-cloud-aws-starter-sqs`), with the AWS SDK BOM imported
after its BOM so the SDK is ours (2.55.6).

- The `SqsAsyncClient` is auto-configured from `spring.cloud.aws.*` (static region; endpoint
  override and dummy credentials only in the `local` profile and tests).
- An `SqsAsyncClientCustomizer` restores explicit call timeouts — the relay holds a
  transaction open across the send.
- **Our own `SqsTemplate` bean with `QueueNotFoundStrategy.FAIL`.** The framework default
  *creates* a missing queue on first send; in AWS that queue would have no redrive policy
  and poison messages would retry forever. Queues are created only by `SqsQueues` (with the
  policy) or by Terraform. Tested.
- The relay sends with `SqsTemplate.sendMany`, keeping per-message results; an `eventId`
  header maps them back to outbox rows and travels as a message attribute.
- The 5.2 consumer will use `@SqsListener` with **manual acknowledgement**, so "delete only
  after the rating transaction commits" stays explicit in our code rather than implied by a
  framework default.

## Verified (2026-09-29)

- Spring Cloud AWS 4.1.1 + Boot 4.1 + Netty 4.2.17 (the AWS Netty client asks for 4.1.138;
  Boot manages 4.2): all 11 outbox/relay tests pass unchanged in behaviour, plus a test that
  the template refuses to create a queue. Unit 82, integration 120.
- No Spring Cloud compatibility verifier involved (the starter does not pull
  `spring-cloud-commons`); no startup warnings about Boot versions.
- Local stack: Postman's games → 3 events → published first attempt → on the queue.
- Startup time unchanged within noise (7.2 s before Phase 5, 8.4 s SDK-direct, 8.0 s now).

## Consequences

- **The async Netty HTTP client is back**, which 5.1 had excluded: Spring Cloud AWS's SQS
  support is built on `SqsAsyncClient`. It shares Boot's Netty version with Lettuce.
- **Java 25 native-access warning** from Netty's native loading: `--enable-native-access=ALL-UNNAMED`
  added to `bootRun` and tests; the Phase 6 Dockerfile must pass it too.
- **Version lag is now a standing risk.** Spring Cloud AWS 4.1.1 targets Boot 4.0.x. A
  future Boot upgrade must be checked against its release notes first (checklist item).
- **Gain:** `@SqsListener` for 5.2 — listener container, concurrency, back-pressure,
  acknowledgement modes and error handling that would otherwise be hand-written — and the
  idiomatic Spring stack a reviewer expects.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Keep the SDK direct** (ADR-011 as written) | Fewer moving parts and full visibility, but the 5.2 consumer would hand-roll a polling loop, concurrency and acknowledgement that `@SqsListener` provides. The owner's call; the lag risk is now mitigated by tests and a checklist item. |
| **3.4.0 as named** | Boot 3.5 line; not viable on Boot 4.1. |
| **Spring Cloud Stream + SQS binder** | Another abstraction layer over the same client for one queue. |

## Interview angle

**Q:** "You originally used the AWS SDK directly. Why switch?"
**A:** Because the consumer I was about to write is exactly what `@SqsListener` gives you —
container, concurrency, acknowledgement. The reason I'd avoided it was version lag, so before
switching I checked: the version I was first pointed at was the Boot 3 line and wouldn't have
worked; the 4.x line targets Boot 4.0 and I'm on 4.1, so I ran the suite against it rather than
trusting it. I kept the dangerous default — auto-creating queues without a DLQ — switched off,
with a test.
