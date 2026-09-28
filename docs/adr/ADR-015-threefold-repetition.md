# ADR-015: Threefold repetition from the persisted move log, applied automatically

**Status:** Accepted · **Date:** 2026-09-28
**Builds on:** ADR-002 (stateless `ChessRules` port), ADR-004 (the `moves` table is the
record of a game).

## Context

Positions are rebuilt from FEN on every move, and the rules engine discards its board
afterwards (ADR-002 — chesslib's `Board` is mutable and not thread-safe). FEN carries no
history, so a position cannot know it has occurred before. Until Milestone 3.3 threefold
repetition never fired: two players could shuffle knights until a clock ran out.

Worse, `ChesslibRules.outcomeOf` mapped any otherwise-unexplained `isDraw()` to
`DRAW_REPETITION`. The branch was unreachable — every other draw was tested first, and
`isRepetition()` is always false on a history-less board — but it was a label that would
have been wrong the day anything reached it.

Every move's `fen_after` is already persisted in `moves`, keyed by `(game_id, ply)`.

## Decision

**The game module supplies the history; the chess module decides what "the same position"
means.**

- `ChessRules.isThreefoldRepetition(current, earlier)` — history is a parameter, so the
  port stays stateless and the rule stays in the module that owns chess knowledge.
- **"Same" per FIDE 9.2.3:** placement, side to move, castling rights, and en-passant
  possibilities. Move counters are ignored. The en-passant square counts **only if a
  legal en-passant capture exists**. chesslib writes it after *every* double pawn push
  (`e3` after 1.e4 — verified empirically, not assumed), so a naive FEN prefix would make
  identical positions look different and miss real draws.
- **Bounded history.** A capture or pawn move is irreversible, so no earlier position can
  recur. The FEN halfmove clock is exactly the length of the relevant window: at most 100
  plies (at 100 the fifty-move draw has already ended the game). The query is one
  primary-key range scan, a projection of `fen_after` only. Below 8 reversible plies a
  third occurrence is impossible and nothing is read.
- **The starting position counts.** It is ply 0, not a row in `moves`, and is added
  explicitly when the window reaches it.
- **Automatic at the third occurrence, not claimed.** The draw ends the game on the move
  that produces the third occurrence.
- Read inside the move transaction, before the move's own row is written. A concurrent move
  bumps `games.version`, so a history read that raced another move is discarded with the
  rest of that attempt (ADR-005).
- The dead `isDraw() → DRAW_REPETITION` branch is removed. `apply()` never reports
  repetition.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Keep a `Board` (with history) per game in memory** | Breaks statelessness and every property it buys: any instance can serve any move, a restart loses nothing, and reconnects need no affinity (ADR-004, ADR-007). |
| **A `position_key` column + index, `COUNT(*)` in SQL** | A migration and backfill to speed up a query that already reads at most 100 rows by primary key. It would also move the FIDE definition — including the en-passant legality check, which needs move generation — into SQL or into a value computed at write time that must never drift. Revisit only if profiling shows this query matters. |
| **Replay the whole game from move 1** | Correct but unbounded; the halfmove clock already says how far back to look. |
| **Naive FEN prefix (first four fields) as the key** | Misses genuine repetitions whenever the first occurrence followed a double pawn push. This was the plan recorded in Phase 1; testing chesslib's FEN output is what ruled it out. |
| **Draw by claim (FIDE over-the-board rule)** | Needs a claim command, UI, and a rule for claims on the opponent's move. There are no draw offers of any kind in this project yet. Automatic is simpler, cannot be forgotten by a player under time pressure, and matches how this codebase already treats the fifty-move rule. |
| **Fivefold (FIDE automatic)** | Lets a known-drawn shuffle continue twice as long for no benefit online. |

## Consequences

- Games can no longer be stalled indefinitely by repetition.
- One extra query per move, but only once 8+ reversible plies have accumulated — i.e. in
  quiet manoeuvring, not in openings or tactical sequences.
- A position right after a double pawn push costs one move generation to normalise. At
  most one such position exists per window, because a pawn move resets it.
- The fifty-move rule and threefold repetition are both automatic. A player cannot choose
  to play on, which is a deliberate simplification over FIDE.

## Tests

- `ChessRulesTest$Repetition` (8, pure): second vs third occurrence, castling rights
  distinguishing positions, uncapturable en-passant ignored, capturable en-passant
  distinguishing, side to move, counters ignored, `apply()` history-less, halfmove clock.
- `GameplayIntegrationTest` — a knight shuffle through the real pipeline draws on ply 8,
  counting the starting position, and not on plies 1–7.

## Interview angle

**Q:** "Your rules engine is stateless and FEN carries no history. How do you detect
threefold repetition?"
**A:** The history is already in the database — every move's resulting position is
persisted. The game service passes the relevant earlier positions into a stateless rules
function. "Relevant" is bounded: captures and pawn moves are irreversible, so only
positions since the last one can match, and the FEN's halfmove clock tells me exactly how
many that is — never more than 100, read by a primary-key range scan.

**Q:** "What counts as the same position?"
**A:** FIDE's definition: same placement, side to move, castling rights, and en-passant
possibilities. The trap is that the library writes an en-passant square after every
double pawn push, even when no capture is possible, so comparing FEN prefixes misses real
repetitions. I normalise the square away unless a legal en-passant capture exists. I found
that by running the library, not by reading its docs.

**Q:** "Why not store a position hash column and count in SQL?"
**A:** It would need a migration and backfill to optimise a query that reads at most 100
rows by primary key, and it would put half of a chess rule into the database. If profiling
ever showed this query mattered I would revisit it; so far it's the wrong trade.
