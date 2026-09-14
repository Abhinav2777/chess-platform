# ADR-008: SQS Standard + idempotent consumers, not Kafka and not SQS FIFO

**Status:** Accepted · **Date:** 2026-09-06

## Context

Finishing a game triggers work that must not block the response: Elo recalculation and
notification. This work must survive consumer crashes and must not corrupt ratings if
a message is delivered twice.

## Decision

**AWS SQS Standard**, with a redrive policy to a DLQ at `maxReceiveCount: 3`, consumed
by idempotent handlers.

Idempotency mechanism: a `processed_events (event_id UUID PRIMARY KEY, processed_at)`
table. The consumer inserts the event id **in the same transaction** as its side effect.
A duplicate hits the primary key, the transaction rolls back, and the message is
acknowledged without re-applying. This matters specifically because rating updates are
not naturally idempotent — `rating += delta` applied twice is silently wrong.

Producers use a **transactional outbox**: the domain event is written to an `outbox`
table inside the game transaction, and a relay publishes it after commit. This closes
the window where a game commits but the process dies before the SQS call.

## Alternatives considered

**Kafka (self-managed or MSK).** Rejected. Kafka earns its cost through log replay for
rebuilding read models, many independent consumer groups reading one ordered stream, or
sustained high-throughput stream processing. This system has one producer, two
consumers, and no replay requirement. Self-managed Kafka is significant operational
work; MSK is roughly $150+/month (estimate, us-east-1 published pricing) against a total
project AWS budget under $50. Adding it would be the "technology as a checklist item"
the spec forbids.

**SQS FIFO.** Rejected, deliberately, even though it offers exactly-once processing
within a deduplication window. Two reasons. First, we don't need ordering: game-finished
events for different games are independent, and a single game finishes once. Second —
and this is the real reason — FIFO would let a managed guarantee *hide* the duplicate
problem, whereas handling at-least-once delivery correctly is the skill worth
demonstrating and the situation most production systems are actually in.

**Synchronous rating update inside the game transaction.** Rejected. It couples game
completion to rating availability, and a rating bug would fail the move that ended the
game. It also blocks the response on work nobody is waiting for.

**Spring `@Async` / in-process events only.** Rejected: no durability. A pod restart
loses queued work, so a finished game could never get rated.

## Consequences

- Ratings are eventually consistent. `GAME_FINISHED` is sent immediately; the rating
  delta may arrive a moment later. The UI is designed for this.
- We must build and test the duplicate-delivery path. LocalStack in Testcontainers lets
  us deliver the same message twice in an integration test and assert the rating moved
  exactly once.
- DLQ depth > 0 needs an alarm. A poison message must be visible, not silently dropped.
- Outbox adds a small relay component and a table. Justified: without it, "game finished
  but never rated" is a real, silent data bug.

## Interview angle

**Q:** "Why SQS and not Kafka?"
**A:** Kafka's value is the durable, replayable, ordered log — replay for rebuilding
read models, multiple independent consumer groups, high-throughput stream processing. I
have one producer and two consumers with no replay requirement, so I'd be paying Kafka's
operational cost, or MSK's roughly $150 a month, for a queue. SQS is managed, free at my
volume, and has DLQ redrive built in.

**Q:** "SQS Standard is at-least-once. How do you avoid double-applying a rating?"
**A:** Each consumer writes the event ID into a `processed_events` table in the same
transaction as its side effect. A duplicate violates the primary key, the transaction
rolls back, and I ack the message. That's the important detail — rating updates are
`rating += delta`, which is not idempotent by nature, so I had to make it idempotent
explicitly rather than hope.

**Q:** "SQS FIFO gives you exactly-once. Why not use it?"
**A:** I don't need the ordering, and I deliberately wanted the at-least-once problem
rather than a managed guarantee that papers over it. Most real systems are at-least-once
somewhere, and the dedup table is a pattern I'd reach for regardless. FIFO also has
lower throughput limits and would have made the duplicate path untested.

**Q:** "What if the game commits but the SQS send fails?"
**A:** That's why I use a transactional outbox — the event row is written inside the
game transaction, and a relay publishes it afterwards. Without that there's a window
where a game is finished and permanently unrated, and it's silent.
