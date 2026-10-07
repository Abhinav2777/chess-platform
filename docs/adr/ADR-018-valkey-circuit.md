# ADR-018: One instance-wide circuit for every degradable Valkey call

**Status:** Accepted · **Date:** 2026-09-28
**Refines:** ADR-004 (Valkey holds nothing unrecoverable), ADR-017 (rate limiter's own circuit, now folded in).

## Context

Phase 4 ended with an unexplained number: with Valkey paused mid-game, the first move took
4.8 s to appear for the mover, against ~1.5–2.5 s expected. A browser trace (WebSocket
frames and polls, wall-clock aligned with the server log) attributed it:

1. **Every Valkey call took 2 s to fail, not 1 s.** `application-local.yml` had set
   `spring.data.redis.timeout: 2000ms` since the first commit, silently overriding the
   "fail fast, always" 1 s in `application.yml`. Tests run without the profile, which is
   why they measured ~1 s.
2. **Only the rate limiter had a circuit.** The fanout publisher, presence and user
   notifications each paid the full timeout on every call. A move made two blocking calls
   in sequence on the socket's thread — the limiter before the commit, the publish after
   it. The client's first poll (at 1.5 s) found the move not yet committed; the next poll
   was 2 s later.

## Decision

- **Remove the local timeout override.** A development profile must not change failure
  timing, or local measurements describe a system that does not exist.
- **`common.resilience.ValkeyGuard` — one circuit per instance, shared by every degradable
  caller:** rate limiter, fanout publisher, user notifier, presence, matchmaking facade and
  tick. The first `DataAccessException` opens it for `chess.valkey.circuit-open-for` (5 s);
  while open, calls go straight to their fallback; the first call after the window probes.
- **Per instance, not per caller**, because "Valkey is unreachable" is a fact about this
  instance's connection. One caller discovering it spares the others one timeout each.
- **Deliberately minimal:** no half-open state machine or failure-rate thresholds. Those earn
  their keep for dependencies that fail partially; a cache that answers or does not needs
  only "stop asking for a few seconds". Non-`DataAccessException`s propagate — they are bugs.

## Consequences — measured (browser, `npm run e2e:outage`)

| | Before | After |
|---|---|---|
| Mover sees their first degraded move | 4.8 s | 1.9 s |
| Degraded steady state | 0.8–3.9 s | 0.9–2.0 s |
| Waiting opponent's first detection | 10.6 s | 10.3 s (the client's 10 s reconcile, by design) |
| Recovery after Valkey returns | < 0.1 s | **up to the window (5 s)**, then < 0.2 s |

**The trade-off:** the circuit swaps one timeout per call during an outage for up to one
window of continued degradation after it — until a probe, the instance does not know Valkey
is back, so publishes are skipped and clients keep polling. First observed as a failing
recovery assertion, then confirmed in the browser. 5 s balances the two; a shorter window
recovers faster but probes (and pays a timeout) more often during a long outage.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Shorter Redis timeout only** (e.g. 200 ms) | Cuts every degraded call's cost but still pays it on every call, and risks false failures under GC or network jitter on a healthy Valkey. |
| **Resilience4j CircuitBreaker** | Correct and featureful; a dependency and configuration surface for a two-state need. Worth it when there are several dependencies with different failure profiles. |
| **Async fire-and-forget publish** (executor) | Removes publish latency from the socket thread but not the pile-up: during an outage the queue fills with doomed work. Complementary, not a substitute. |
| **Per-caller circuits** | Each caller rediscovers the outage with its own timeout — what the first attempt (rate limiter only) left in place. |

## Tests

`ValkeyGuardTest` (unit: healthy passthrough; failure opens and skips; probe after window;
shared across callers; bugs propagate and do not trip). `RateLimiterFailOpenTest` now counts
real attempts via `chess.valkey.circuit.trips`. `ValkeyOutageIntegrationTest` waits one
window after unpause before asserting recovery. `frontend/e2e/outage.mjs` does the same and
fails if the labels do not return to "Live".

## Questions this decision raises

**Q:** "You measured something you couldn't explain. What did you do?"
**A:** I traced it rather than guessing: WebSocket frames and poll responses from the
browser, aligned with server log timestamps. That showed the move hadn't committed when the
first poll ran, and that every Valkey call was taking 2 s — a profile override from the
first commit doubling my timeout. Then I saw the second problem: only one of five Valkey
callers had a circuit. One shared circuit and one deleted line took the first degraded move
from 4.8 to 1.9 seconds.

**Q:** "What does the circuit cost you?"
**A:** Recovery lag. Until the window lapses the instance doesn't know Valkey is back, so for
up to five seconds clients keep polling. A failing recovery assertion told me that before
the browser did.
