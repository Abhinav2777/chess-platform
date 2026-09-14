# ADR-002: Use `chesslib` rather than writing a rules engine

**Status:** Accepted · **Date:** 2026-09-06

## Context

Correct chess rules require legal move generation, check/checkmate/stalemate detection,
castling rights (including through-check and rook-moved cases), en passant, promotion,
the fifty-move rule, threefold repetition, and insufficient-material draws. A correct
implementation is 1,500+ lines and 15–25 hours including the tests that make it
trustworthy.

## Decision

Use **`com.github.bhlangonijr:chesslib`** (Apache 2.0), wrapped behind our own
`ChessRules` port in the `chess` module. Application code never imports a chesslib
type; it works with our `Position`, `MoveRequest`, `MoveOutcome` records.

## Alternatives considered

**Write it from scratch.** Rejected. The spec is explicit that the project's purpose is
engineering, not proving that chess rules can be implemented. Those 20 hours are worth
far more spent on the clock, concurrency, and observability. A hand-rolled engine also
carries silent-correctness risk: a subtly wrong en-passant rule produces games that
look fine and are invalid.

**Run Stockfish as a subprocess for validation.** Rejected — enormous overhead per move
for a task that needs no search, and out of scope per the spec.

## Distribution (added 2026-09-06)

chesslib is **not on Maven Central** — it is distributed via JitPack, which the
`com.github.<user>` groupId indicates. Current version **1.3.7**. This was discovered
when the first build failed to resolve, and it makes the dependency source its own
decision: see **ADR-012**.

## Consequences

- Legal-move validation is a library call; the engineering work is the pipeline around
  it (§7 of ARCHITECTURE.md).
- **The wrapper is not ceremony.** It buys three concrete things: (1) chesslib's `Board`
  is mutable and not thread-safe, so the adapter enforces "construct from FEN, use,
  discard" and that rule cannot leak; (2) the library becomes swappable; (3) domain
  tests run against our types, so a library upgrade cannot silently change semantics
  without a test failing.
- We must still verify the library. **Perft tests** at depths 1–4 from standard
  positions (initial, Kiwipete, position 3–5) against published node counts. If the
  counts match, move generation is correct. This is ~2 hours and it is not optional.

## Interview angle

**Q:** "Why didn't you write the chess engine yourself?"
**A:** Because the interesting problem in this system isn't chess, it's making a
turn-based mutation of shared state correct under concurrency, reconnection, and pod
death. I used a mature MIT-licensed library and spent that time on the move pipeline
instead. I did wrap it behind a port — partly for swappability, but mainly because
chesslib's `Board` is mutable and not thread-safe, and I wanted that constraint
enforced in one place rather than trusted across the codebase. I verified the library
with perft tests rather than assuming it was correct.
