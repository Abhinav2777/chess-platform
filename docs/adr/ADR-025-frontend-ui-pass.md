# ADR-025: A time-boxed frontend UI pass

**Status:** Accepted · **Date:** 2026-10-01
**Builds on:** ADR-002 (no chess logic on the client), ADR-023 (same-origin SPA in the image).

## Context

After Phase 8 the goal was a great-looking frontend with the best UX the budget allowed. The UI was a
functional prototype: Unicode-glyph pieces (font-dependent; white pieces washed out on light
squares), no coordinates, no app shell or sign-out, a lobby made of default buttons and long
explanatory paragraphs, no game-over moment, and a desktop column squeezed onto phones.

The project's priority list ranks the frontend 10th of 10, and the budget stood at ~110 h of 135–175 with
Phases 9 and 10 (10–14 h each) ahead. A polished UI has real portfolio value — it is what a
recruiter clicks and what the Phase 10 demo video shows — and almost no backend-interview value.

## Decision

**A focused pass, time-boxed at 6–8 h (chosen over a 12–15 h redesign or a 3 h polish),
in a modern dark, lichess-like style.**

In scope: app shell (brand, rating chip, sign-out); redesigned sign-in, lobby (time-control cards,
seeking state, challenge card, game list with status badges) and game screen (player bars with
avatar, presence and clock; move list paired by move number; status banner; resign confirmation;
game-over dialog with the rating change); SVG pieces; coordinates; selected, legal-target, capture,
last-move and check highlights; phone layout; focus-visible rings; reduced-motion support.

Out of scope (recorded so it is not mistaken for forgotten): drag-and-drop, premoves, sounds, move
animation, captured material, game replay, profiles, theming.

**Constraints kept:**
- **No UI framework, no chess-board library.** One CSS file of design tokens; the board stays our
  component — it still contains no chess logic. Even the new check highlight reads the server's SAN
  (`+`/`#`) and finds the king in the FEN; nothing is computed.
- **Every protocol behaviour unchanged:** server-driven legal moves, degraded-mode label, async
  rating, no colour picker for challenges, first-move window.
- **The browser checks guard the redesign:** accessible names and classes they rely on were kept;
  the one deliberate change — resigning now asks for confirmation — updated `e2e/lobby.mjs`.
- **Pieces: the Cburnett set, CC BY-SA 3.0** (licence read from the Wikimedia API, not assumed),
  unmodified, in `frontend/src/pieces/` with a README, credited in the footer. ShareAlike covers the
  images, not the code that displays them. Each SVG is < 1.5 KB, so Vite inlines them — no new
  static paths to open in `SecurityConfig`.

## Verification

`npm run build` (type-check) clean; `e2e:lobby` green on the Vite dev server, on the production
image through ingress-nginx on kind (after a full rolling deploy), and `e2e:outage` green against
`bootRun` (labels Live → Live · updates delayed → Live, timings unchanged). Screenshots of every
state reviewed (sign-in, lobby, seeking, selection with legal targets, check, resign confirmation,
game over, phone) and kept in `docs/screenshots/`.

One unexplained timeout: the first `e2e:lobby` run after deploying the new image to kind timed out
at a step I cannot name (I kept only the output's tail). Four runs since — including a reproduced
full redeploy and a rollout with pods still terminating — were green. Recorded for the nightly CI to
watch, not claimed as understood.

## Consequences

- ~4 h spent (inside the box). Bundle: 263 kB JS (80 kB gzip) including the inlined pieces.
- The README now carries screenshots; Phase 10's demo video starts from a presentable UI.
