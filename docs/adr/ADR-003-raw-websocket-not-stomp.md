# ADR-003: Raw WebSocket + custom JSON protocol instead of STOMP

**Status:** Accepted · **Date:** 2026-09-06

## Context

Spring offers STOMP over WebSocket with `@MessageMapping`, subscription handling, and a
built-in simple broker. It is the documented default and the fastest path to a working
demo. The spec permits either STOMP or a lower-level protocol.

## Decision

Raw `WebSocketHandler` with a custom versioned JSON envelope
(`{v, type, seq, ts, payload}`), and Valkey Pub/Sub for cross-instance fanout.

## Alternatives considered

**STOMP + Spring's `SimpleBroker`.** Rejected on a hard technical fact: the simple
broker is in-JVM. With two API pods, a move committed on pod A is never delivered to a
client subscribed on pod B. It works in a single-instance demo and breaks the moment
the system is horizontally scaled — which is the entire point of the project.

**STOMP + an external relay (RabbitMQ / ActiveMQ Artemis).** This *does* solve fanout,
correctly. Rejected because it introduces a whole message broker into the architecture
whose only job is relaying WebSocket frames, when Valkey — already in the stack for
caching and matchmaking — does the same fanout with `PUBLISH`/`SUBSCRIBE`. Adding
RabbitMQ here would be exactly the "technology for the checklist" the spec forbids.

**Socket.IO / SockJS fallbacks.** Rejected. Long-polling fallback matters for corporate
proxies in a consumer product; for a portfolio project it adds a second transport to
reason about and test for no engineering value.

## Consequences

- We implement subscription tracking, heartbeats, and reconnection ourselves — roughly
  200–250 lines. This is the *deliverable*, not the cost: protocol design (idempotency
  keys, sequence numbers, expected-ply optimistic concurrency) is where much of this
  project's interview value lives, and STOMP would hide all of it.
- We give up STOMP's ack modes and off-the-shelf JS clients. We don't need ack modes
  because snapshot-on-reconnect (ADR-007) makes delivery guarantees unnecessary, and
  the browser's native `WebSocket` API is adequate.
- The protocol is versioned (`v: 1`) from day one so it can evolve.

## Interview angle

**Q:** "Spring has STOMP support built in. Why write your own protocol?"
**A:** Spring's simple STOMP broker is in-memory per JVM, so it doesn't fan out across
instances — a move on pod A never reaches a subscriber on pod B. Fixing that means
running an external relay like RabbitMQ purely to shuttle frames, when Valkey was
already in my stack for matchmaking and could do the same fanout with pub/sub. So the
choice was "add a broker to keep STOMP" versus "write ~200 lines of protocol and use
infrastructure I already have." I also wanted control over the message envelope,
because my `MOVE` command carries a client-generated idempotency key and an expected
ply for stale-client detection, and those are the mechanisms that make concurrent and
retried moves safe.

**Follow-up:** "When would you have chosen STOMP?"
**A:** If the client team wanted an off-the-shelf JS client, if we needed many
heterogeneous subscription patterns rather than one channel per game, or if RabbitMQ
were already in the architecture for another reason.
