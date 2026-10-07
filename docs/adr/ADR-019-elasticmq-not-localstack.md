# ADR-019: ElasticMQ, not LocalStack, as the SQS stand-in

**Status:** Accepted · **Date:** 2026-09-28
**Amends:** the Phase 5 roadmap line "LocalStack Testcontainers tests".

## Context

Phase 5 needs SQS locally and in integration tests: duplicate delivery, dead-letter routing,
visibility timeouts. The roadmap (written in Phase 0) assumed LocalStack. Checked before
building: **since March 2026 the LocalStack image requires an account and
`LOCALSTACK_AUTH_TOKEN`, even for the free tier**, and the tokenless grace period ended in
April 2026 ([LocalStack announcement](https://blog.localstack.cloud/localstack-single-image-next-steps/),
[testcontainers-java #11568](https://github.com/testcontainers/testcontainers-java/issues/11568)).

## Decision

**ElasticMQ** (`softwaremill/elasticmq-native:1.7.1`, Apache-2.0) in docker-compose and as a
Testcontainers `GenericContainer`. Queues are created by the application on first use when
`chess.messaging.create-queues=true` (ElasticMQ); in AWS, Terraform owns them (Phase 7).

Verified, not assumed: the relay's integration tests run AWS SDK 2.55.6 — which speaks SQS's
newer JSON protocol — against ElasticMQ: send batch, receive, delete, queue creation with a
`RedrivePolicy`, and attribute reads all work.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **LocalStack with a free Hobby token** | An account and a secret for every developer and in CI, and test runs tied to a vendor login — for one service. |
| **A pinned pre-2026.03 LocalStack image** | Works today without a token, but frozen: no fixes, no security updates, and the gap to real SQS only grows. |
| **Real SQS in a sandbox account** | Real behaviour, but tests would need AWS credentials and network, cost money per run, and could not run offline. Real SQS is verified once, in Phase 7. |
| **Mocking the SQS client** | Tests the calls we make, not the behaviour we depend on (visibility, redelivery, redrive). |

## Consequences

- No account, no CI secret, ~30 MB image, starts in about a second.
- It emulates SQS only. Fine: SQS is the only AWS service used before Phase 7. If Phase 7
  needed S3 or Secrets Manager emulation, this is revisited then.
- Emulator fidelity is a risk for edge cases (exact redrive timing, attribute formats). The
  Phase 7 deployment includes a smoke test against real SQS.

## Questions this decision raises

**Q:** "Your roadmap said LocalStack. Why isn't it there?"
**A:** I checked before building and found LocalStack had started requiring an account and
auth token even for free use. For the one service I needed, ElasticMQ does the job with no
account, and I verified the current AWS SDK's JSON protocol against it in the first test
rather than assuming. Real SQS gets a smoke test when I deploy.
