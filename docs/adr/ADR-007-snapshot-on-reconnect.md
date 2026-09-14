# ADR-007: Snapshot-on-reconnect instead of event replay

**Status:** Accepted · **Date:** 2026-09-06

## Context

A client that disconnects and reconnects has missed some number of server events. It
must be brought back into sync. Valkey Pub/Sub is fire-and-forget with no delivery
guarantee, so this also covers messages simply lost in flight.

## Decision

On every `SUBSCRIBE` — first connection or reconnection — the server sends a complete
`GAME_SNAPSHOT` (FEN, both clocks, ply, turn deadline, players, last move, status). The
client **discards its local state and adopts the snapshot unconditionally.**

No replay buffer, no per-client cursor, no `lastSeq` negotiation.

## Alternatives considered

**Delta replay (`RESUME {lastSeq}` → replay events since).** Rejected for this domain.
It requires the server to retain a per-game event buffer with a retention policy, handle
the "cursor is older than the buffer" fallback (which needs a snapshot path anyway, so
you build both), and reason about ordering across the buffer/live boundary. All of that
to avoid sending ~120 bytes: a FEN string, two integers, and a timestamp.

Delta replay earns its complexity when state is large or events are not
state-convergent. A chess position is neither — the full state is tiny and every event
is derivable from it.

**Durable transport (Redis Streams) instead of Pub/Sub.** Rejected. It would guarantee
delivery, but snapshot-on-reconnect already repairs any gap, so we'd be paying for
consumer groups, acknowledgement, and stream trimming to solve a problem we've already
solved more cheaply.

## Consequences

- Message loss is not a correctness concern anywhere in the real-time layer. This is
  what licenses the use of unguaranteed Pub/Sub in ADR-003, and the two decisions should
  be defended together.
- The client is simple: render whatever the last snapshot said, apply deltas
  optimistically for responsiveness, and let the next snapshot overwrite.
- Snapshot generation is a hot path on reconnection storms (e.g. a pod restart drops
  1,000 sockets at once). Mitigation: the `game:{id}:state` Valkey cache serves it, and
  clients reconnect with jittered exponential backoff so they don't arrive in lockstep.
- **This would not scale to a domain with large state** — a strategy game with a
  10 MB world would need deltas. The decision is domain-specific and should be presented
  as such.

## Interview angle

**Q:** "How do clients recover missed messages?"
**A:** They don't — they resync. On reconnect the server sends a full snapshot and the
client throws away whatever it had. A chess position is a FEN string plus two clock
values, about 120 bytes, so replaying deltas would be more machinery than just sending
the state.

**Q:** "But you're using Redis Pub/Sub, which doesn't guarantee delivery."
**A:** Right, and that's deliberate. Because reconnection always resyncs from
authoritative state, a dropped message causes a temporary display gap, not divergence.
Guaranteed delivery would let me skip the resync path — but I need that path anyway for
new connections and for clients that were offline longer than any buffer would retain.
So I'd be building both and paying for one of them twice.

**Q:** "When would you build delta replay instead?"
**A:** When the state is too large to resend cheaply, or when events carry information
the state doesn't — an action game with a large world, or an audit stream where the
sequence itself matters. Here the state is small and fully convergent.
