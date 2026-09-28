# ADR-006: Computed (non-ticking) server-authoritative clock, timestamped by PostgreSQL

**Status:** Accepted · **Date:** 2026-09-06

## Context

The clock decides game outcomes, so it must be server-authoritative and must survive
disconnection, reconnection to a different pod, pod death, and rolling deployments.

## Decision

The clock **does not tick.** The server persists `(white_ms_left, black_ms_left,
last_move_at, side_to_move, turn_deadline)` and derives remaining time on read:

```
remaining(mover) = stored_ms_left − (now − last_move_at)
remaining(other) = stored_ms_left            // frozen
```

`now` is **PostgreSQL's `now()`**, taken inside the move transaction — not the
application server's clock.

Flag-fall detection has two layers: lazy evaluation on any move or read, plus an active
sweeper querying the partial index `(turn_deadline) WHERE status='ACTIVE'` with
`FOR UPDATE SKIP LOCKED`.

## Alternatives considered

**A `ScheduledExecutorService` timer per active game.** Rejected. It dies with the pod;
two pods can hold timers for one game and double-fire; N thousand games means N thousand
timers; GC pauses make the tick interval a fiction; and it cannot be restored on restart.

**Client-side clock with server verification.** Rejected outright — the client is never
authoritative about anything that determines a result. A modified client would simply
never flag.

**A dedicated clock service.** Rejected. It centralises what is already a pure function
over three columns, and creates a new single point of failure.

## Consequences

- **The reconnection scenario is free.** A player who disconnects for 10 seconds and
  reconnects to a *different* pod gets a correct clock, because the clock was never in
  any pod's memory. This is the payoff for making it a computed value.
- Rolling deployments do not disturb clocks.
- Using the database as the time authority eliminates inter-pod clock skew. Two pods
  under NTP still differ by tens of milliseconds; measuring successive moves of one game
  against different clocks accumulates error and can produce *negative* elapsed time.
  One clock for all games removes the class of bug entirely, and it costs nothing.
- Network latency is charged to the moving player. This is a policy, and it is
  documented. Lag compensation is out of scope: it needs per-connection RTT measurement
  and opens an abuse vector where a client fakes latency to gain time.
- `turn_deadline` must be maintained on every move so the sweeper index stays useful.

## Implemented, 2026-09-14 (Milestone 3.1)

Five columns on `games`, one partial index, and a pure function. No timer exists anywhere
in the codebase.

- `ClockCalculator` — static, dependency-free, 14 unit tests covering flag-fall at exactly
  zero, increment at the boundary, and a clock that moved backwards. No database, no
  sleeping.
- `ServerClock` — `SELECT now()`, so one machine is the time authority for every game.
- `games.turn_deadline` with a partial index on ACTIVE games, so the sweeper is an index
  scan bounded by *expired* games.
- `TimeoutSweeper` — `FOR UPDATE SKIP LOCKED`, so replicas take disjoint batches with no
  leader election and no distributed lock.

**The reconnect-to-another-instance case required no code at all.** That is the result the
design was chosen for: there was nothing to restore, because nothing was ever held.

**The one thing that went wrong was not the clock.** Every timeout write — from the move path
and from the sweeper — was initially lost: one to a rollback, one to self-invocation. The
arithmetic was right from the first run. Both writes now go through `GameTimeouts`, with
deliberately different transaction shapes; see DEVELOPMENT_LOG and TROUBLESHOOTING.

## Verified, 2026-09-28 (Milestone 3.3)

Phase 3's "done when" checks, measured rather than argued (PROJECT_STATE §12):

- **Skewed application clock has no effect.** `ClockSkewIntegrationTest` runs a context
  whose `Clock` bean is ten minutes fast and plays a game: moves are charged
  milliseconds, the sweeper expires nothing, and REST shows full clocks.
- **Server killed mid-game.** `kill -9` on the application JVM with White on move, cold
  restart, reconnect: White's clock had dropped by 31,769 ms over 31,798 ms of wall time;
  Black's was unchanged to the millisecond. The clock ran while no server existed.

### Scope of the time authority

`ServerClock` (PostgreSQL `now()`) is required for every value that **decides or displays
a game's clock**: charging moves, deadlines, expiry, and remaining time in REST responses
and snapshots. Until 3.3, `GameSummary` computed REST remaining time from `Instant.now()`;
it now takes `now` as a parameter, so the choice of clock is visible at every call site.

The injected `Clock` bean remains correct for `created_at`, token expiry and metrics, where
inter-instance skew is irrelevant. The remaining bare `Instant.now()` calls in main code are
timestamps on error bodies and protocol envelopes — informational, never compared.

A skewed `Clock` bean cannot catch a bare `Instant.now()`, which reads the OS clock. That
class of leak is closed by construction (the parameter) and by review, not by the skew
test.

## Interview angle

**Q:** "How does your chess clock work?"
**A:** It doesn't tick. I store each player's remaining milliseconds, the timestamp of
the last move, and whose turn it is, then compute remaining time as a function of those
and the current time. There are no timers and no in-memory state, so the clock is
identical whether it's read a millisecond or an hour later, and from any pod.

**Q:** "A player disconnects for ten seconds and reconnects to a different server
instance. Is the clock still right?"
**A:** Yes, and nothing special happens — the clock was never on the first instance.
The new instance reads the same three columns and computes the same value. The player's
clock also correctly kept running during the disconnect, because chess doesn't pause
for network problems.

**Q:** "If nothing ticks, how do you notice someone ran out of time?"
**A:** Two ways. Lazily, on the next move or read. And actively, with a sweeper that
queries games whose stored deadline has passed. The deadline is a stored, indexed
column with a partial index on active games, so that query is an index scan bounded by
the number of *expired* games, not a scan of every game. Multiple sweeper replicas use
`FOR UPDATE SKIP LOCKED`, so they partition the work without coordinating and can never
finalise the same game twice.

**Q:** "How did you test something time-dependent without slow, flaky tests?"
**A:** Because the clock is a pure function, most of it needed no time at all — 14 unit
tests pass instants in as arguments and compare numbers, so flag-fall at exactly zero or a
clock that moved backwards are ordinary assertions. The database-level tests push
`turn_deadline` into the past with SQL rather than sleeping. Nothing in the suite waits for
real time to pass, which is the practical payoff of a clock with no state of its own.

**Q:** "Why take the timestamp from the database instead of the application?"
**A:** Clock skew. Two pods on different hosts differ by tens of milliseconds even
under NTP. If move 1 is timestamped by pod A and move 2 by pod B, the elapsed time for
move 2 is measured against a different clock and the error accumulates — it can even go
negative and hand a player free time. Taking `now()` from Postgres makes one machine the
sole time authority for every game in the system.

---

**Addendum (2026-09-28, Milestone 3.2):** `turn_deadline` now means "the next instant this
game needs the server's attention" — the mover's flag-fall, or, until both players have
moved, the end of the 30-second first-move window if that is sooner. Expiry before both
players have moved aborts the game instead of flagging it. The computed-clock decision is
unchanged; see ADR-014.
