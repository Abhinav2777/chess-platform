# ADR-014: Abort games nobody started, through the timeout machinery

**Status:** Accepted · **Date:** 2026-09-28
**Extends:** ADR-006 (computed clock). Reuses its stored deadline, partial index and
sweeper rather than adding anything beside them.

## Context

Until Milestone 3.2 every game that ended was FINISHED with a result. Three situations
produced results nobody would call fair:

1. **A player never shows up.** A challenge is created; the opponent never opens it. After
   five minutes White's flag falls and Black is awarded a win for a game in which neither
   player moved.
2. **A mistaken challenge.** Both players click Challenge at once (observed in Phase 2),
   creating two games. The unwanted one lingers in both lobbies and eventually "ends".
3. **An instant resignation.** Resigning at ply 0 is a rated win for the opponent. Two
   accounts, one challenge, one resignation, repeat: the textbook rating-farming loop.

`GameStatus.ABORTED` and `Termination.ABANDONED` existed from V1. Nothing set them.

## Decision

**Until both players have made a move, a game can be aborted but never won or lost.**

- Each player has **30 seconds** (`Game.FIRST_MOVE_WINDOW`) from when their clock starts
  to make their first move. White's window starts at creation; Black's starts at White's
  first move.
- While `ply < 2`, **any** expiry — the window closing *or* the clock running out — aborts.
  A 10-second game flags before the window closes; that must not become a win either.
- Resigning while `ply < 2` aborts instead. The UI labels the button "Abort".
- Aborted = `status ABORTED`, `termination ABANDONED`, `result NULL`. Never rated.
- The rule lives in one method, `Game.expiryAt(now) → NONE | ABORT | FLAG`, used by both
  the move pipeline and the sweeper.

### One deadline, one index, one sweeper

`turn_deadline` changes meaning from "the mover's flag-fall" to **"the next instant this
game needs the server's attention if nobody moves"** — `min(flag-fall, window end)` while
`ply < 2`, flag-fall afterwards. The existing partial index, `claimExpired` query and
`TimeoutSweeper` then find unstarted games with no changes at all; `expiryAt` decides what
to do with each one. `V5` backfills the new meaning onto games already in progress.

### Decided on the move path too, not only by the sweeper

A first move arriving at 30.4 s is refused with `422 GAME_ABORTED` and the abort is written
in `REQUIRES_NEW` (the same write-then-throw shape as a flag-fall — ADR-006, 3.1 fixes).
Leaving it to the sweeper would make the outcome depend on when the sweeper last ran: the
same move accepted or refused according to a one-second scheduling race.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **A second sweeper / query for aborts** | Two background jobs able to end the same game in different ways, double the polling load, and a second index to keep correct. One deadline column already expresses "needs attention at T". |
| **Compute the abort in the sweeper's `WHERE` clause** (`ply < 2 AND last_move_at < now() - 30s`) | Not index-friendly without a second partial index, and splits the rule between SQL and Java where they can drift. The stored deadline keeps the SQL rule-free. |
| **No abort; let the clock run out** | Awards rated wins for unplayed games; lobbies fill with five-minute zombies. |
| **Abort only if *nobody* moved (ply 0)** | Leaves "White moves, Black never appears" as a rated win for White. The window must apply to each player's first move. |
| **A "not started" status distinct from ACTIVE** | Adds a state transition to every read and write of status for something `ply < 2` already encodes. |
| **Window as configuration** | No second value has ever been needed. A constant plus a literal in V5 is honest about that. Making it a property is a five-minute change when a reason appears. |

## Consequences

- **Rating safety by construction.** `ck_games_result_consistency` (V1) already forbids a
  result on any non-FINISHED row, so "an abort is never rated" is enforced by the database,
  not only by the entity. Phase 5's rating consumer filters on `GameEnded.status`, which was
  added for exactly that reason rather than letting consumers infer it from a null result.
- `GAME_FINISHED` gains a `status` field and a nullable `result`. Additive; protocol stays v1.
  **The broadcaster called `result().name()` unconditionally** — correct for every ending
  that existed before, and a `NullPointerException` on the first abort, thrown in an
  `AFTER_COMMIT` listener after the abort had committed, with no client ever told. Caught in
  review, guarded by an end-to-end test.
- Three existing tests expired or resigned games at ply 0 and asserted TIMEOUT/RESIGNATION.
  They now play 1.e4 e5 first. The change in expected outcome is the rule working.
- Metrics: `chess.game.aborts` (games aborted), `chess.move.late_first_moves` (moves refused
  because the window had closed).

## Interview angle

**Q:** "How do you stop someone farming rating with a second account?"
**A:** The cheapest loop — challenge, instantly resign, repeat — is closed structurally: until
both players have moved, a game can only be aborted, and an aborted game has no result. The
database constraint that already forbade a result on a non-finished game means no code path,
including a future bug, can rate one. Farming by actually playing games is a different problem
(anomaly detection), deliberately out of scope.

**Q:** "You added a new kind of timeout. What did it cost?"
**A:** Almost nothing structurally, which was the point of the choice. The sweeper finds work
through one stored deadline column with a partial index. I redefined that column as "the next
time this game needs attention" and took the minimum of the two deadlines, so the index, the
`SKIP LOCKED` query and the job are unchanged. The decision of *what* to do moved into one
pure method on the entity that has thirteen boundary tests.

**Q:** "What was the riskiest part?"
**A:** Not the rule — the consumers. Every piece of code that had only ever seen a finished game
assumed a result existed. The WebSocket broadcaster would have thrown on the first abort, in an
after-commit listener, so the database would have been right and every client silently wrong.
I added an explicit `status` to the event rather than making consumers infer "aborted" from
null, and an end-to-end test that lets the real scheduled sweeper abort a game and asserts both
sockets hear about it.
