# ADR-017: Rate limiting — a Lua token bucket in Valkey, failing open behind a circuit

**Status:** Accepted · **Date:** 2026-09-28
**Supersedes:** the "Bucket4j backed by Valkey" line in ARCHITECTURE.md §11 (Phase 0 plan).

## Context

ARCHITECTURE.md §11 asks for 5/min on login and 20/s on moves per user, shared across
instances. Matchmaking (4.1) adds a third surface: `SEEK`, which the client repeats every
15 s by design. The limits must hold across instances, so they live in Valkey — which
can be down, and ADR-004 says a Valkey outage must never stop chess.

## Decision

- **Algorithm: token bucket.** `capacity` at once, refilling at `capacity / period`. One
  Lua script refills lazily, takes a token, and sets a TTL of one full refill — two numbers
  per key, nothing running between requests, time from Valkey `TIME`.
- **Implementation: our script, not Bucket4j.** Evaluated: Bucket4j 8.20's Lettuce module
  is built against Lettuce 6.1 (`provided`) while Boot 4.1 ships 7.5, and its proxy manager
  wants a native connection outside Spring's `LettuceConnectionFactory` — a second
  connection lifecycle beside the fail-fast timeouts tuned in 2.2. The bucket is ~20 lines
  on the script machinery matchmaking already uses. Callers see only
  `RateLimiter.enforce(limit, subject)`, so Bucket4j can replace it inside one class.
  Decided with the project owner.
- **Limits** (`chess.ratelimit.policies`): login per IP 10/min and per username 5/min
  (a botnet defeats the first, a single attacker the second); register per IP 5/min; moves
  per user 20/s, **one bucket for REST and WebSocket**; seeks per user 10 per 30 s. The
  `local` profile raises the auth limits for development; tests use the real ones.
- **Enforced explicitly at each entry point** (`AuthController`, `GameController.move`,
  the WebSocket `MOVE`/`SEEK` handlers), before any other work, so a flood never costs a
  database transaction. Not a path-matching servlet filter: the username limit needs the
  parsed body, and the socket path needs the same call anyway.
- **Lives in `common`.** `identity` uses it and `platform` depends on `identity`; placing
  it in `platform` would recreate the cycle removed in 4.1b (and ArchUnit would now say so).
- **Response:** `429` + `Retry-After` (whole seconds, rounded up) with problem+json
  `RATE_LIMITED`; on the socket, `ERROR {code: RATE_LIMITED}`.
- **Failure: fail open, behind a circuit.** If Valkey is unreachable the request is
  allowed (the owner chose this for login too: bcrypt cost 12 still costs an attacker
  ~250 ms of server time per guess). After a failure the limiter stops consulting Valkey for
  `circuit-open-for` (5 s), so an outage costs **one slow request per 5 s per instance**
  instead of a timeout on every move — measured: the first move after Valkey dies commits
  in 1,037 ms (the 1 s command timeout), later ones pay nothing.
- **Client address:** `getRemoteAddr()`. Behind the ALB (Phase 7) this becomes
  `server.forward-headers-strategy=native` with only the balancer trusted. Reading
  `X-Forwarded-For` directly would let a client choose its own bucket.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Fixed window counter** (`INCR` + `EXPIRE`) | Simplest, but admits 2× the limit across a window boundary, and a whole window's quota can be spent in the first millisecond. |
| **Sliding log** (ZSET of timestamps) | Exact, but memory and work proportional to requests per window per key. |
| **Sliding window counter** | Close to exact and cheap; the token bucket is equally cheap and additionally expresses "allow a short burst" directly, which suits premoves. |
| **In-memory limiter per instance** (Guava/Resilience4j) | Limit multiplies with instance count, and a client can spread across instances. |
| **Limit at the ALB / WAF** | The right layer for volumetric abuse at scale (Phase 7 discussion), but it cannot key on username or user id, which is what the login and move limits need. |
| **Fail closed** | Turns a cache outage into a sign-in and gameplay outage. |
| **Limit only failed logins** | Kinder to legitimate users, but needs a peek-then-consume split with its own race; bcrypt already makes each attempt expensive. Revisit if real users hit the limit. |

## Consequences

- One extra Valkey round trip per move (sub-millisecond locally; not yet measured under
  load — Phase 9).
- During an outage there is no rate limiting at all, by choice. The
  `chess.ratelimit.unavailable` counter makes the window visible.
- Contexts that do not configure Valkey default to `localhost:6379`, which on a developer
  machine is the compose Valkey. Test contexts not about rate limiting switch the limiter
  off, or they pass in CI and fail locally (found while building this).

## Tests

`RateLimiterFailOpenTest` (unit, no Docker — dead port, controllable clock: allowed,
Valkey consulted once, probed again after the window). `RateLimitIntegrationTest` (real
Valkey, production limits: burst then refusal with retry time, independent buckets,
refill, TTL, HTTP 429 + `Retry-After` on the sixth login). Realtime: 25 `MOVE` frames →
`RATE_LIMITED` after about one bucket. `ValkeyFanoutIntegrationTest` measures the
post-outage delay.

## Interview angle

**Q:** "How does your rate limiter work across instances?"
**A:** A token bucket per key in Valkey, updated by a Lua script so refill-and-take is
atomic across instances. Two numbers per key, refilled lazily from Valkey's clock, expiring
once they'd be full again.

**Q:** "What happens when Valkey is down?"
**A:** It fails open — a limiter is a guard, not a dependency. But failing open naively
still makes every request wait out the Redis timeout, so after one failure it stops asking
for five seconds. I measured it: the first move after an outage takes about a second, the
rest take nothing extra.

**Q:** "Why not Bucket4j?"
**A:** I looked. Its Lettuce module targets Lettuce 6 and Boot 4.1 ships 7, and it wants its
own connection outside Spring's pool. Twenty lines of Lua on infrastructure I already had
was lower risk, and it's behind one method so Bucket4j can drop in later.
