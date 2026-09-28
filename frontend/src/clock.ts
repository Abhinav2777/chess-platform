import type { Side } from './protocol';

/**
 * The client's clock — which, like the server's, does not tick (ADR-006).
 *
 * The obvious implementation is a `setInterval` that subtracts 100 ms from a counter every
 * 100 ms. It is wrong in three ways that all show up in real use:
 *
 *  1. **Drift.** Timers fire late, never early. Every late tick is time the counter never
 *     subtracts, so the display runs slow and slower — seconds behind after a few minutes.
 *  2. **Background tabs.** Browsers throttle timers in hidden tabs to once a second or
 *     less. A decrementing counter simply stops; switch back and your opponent's clock
 *     shows time they no longer have.
 *  3. **Accumulation.** Each error is baked into the counter and carried forward.
 *
 * Instead, every server message ANCHORS the clock: we record the two values it carried and
 * the local instant it arrived. What we display is computed from that anchor and the
 * current time, every frame. The render timer only decides how often we *look*; skipping
 * frames, or a throttled tab, costs smoothness, never accuracy — and the next server
 * message replaces the anchor outright, so no error survives past one move.
 *
 * `performance.now()`, not `Date.now()`: it is monotonic. The wall clock can jump when the
 * OS syncs NTP or the user changes the time, and a jump would add or remove seconds from a
 * running clock in the middle of a game.
 *
 * **The display is advisory.** It lags the server by one-way network latency — a few tens
 * of milliseconds, in the player's favour. The server alone decides flag-falls, against
 * the database clock; when this reaches zero the client just waits for `GAME_FINISHED`.
 */
export interface ClockAnchor {
  whiteMs: number;
  blackMs: number;
  /** Whose time is running, or null when the game is over and both clocks are frozen. */
  running: Side | null;
  /** `performance.now()` when the values above were true. */
  at: number;
}

export function anchor(whiteMs: number, blackMs: number, running: Side | null,
                       at: number = performance.now()): ClockAnchor {
  return { whiteMs, blackMs, running, at };
}

/** Time left for a side at local instant `now`. Never negative. */
export function remainingMs(clock: ClockAnchor, side: Side, now: number): number {
  const stored = side === 'WHITE' ? clock.whiteMs : clock.blackMs;
  if (clock.running !== side) return stored;
  // Clamped at zero elapsed for the same reason as the server: never credit time.
  return Math.max(0, stored - Math.max(0, now - clock.at));
}

/**
 * Stops both clocks where they stand — on resignation, mate, or abort. On a timeout the
 * loser is pinned to exactly zero: the display may have been a few ms behind the server,
 * and a finished game showing "0:00.1" for the flagged player would be a small lie.
 */
export function freeze(clock: ClockAnchor, now: number, flagged: Side | null): ClockAnchor {
  const white = flagged === 'WHITE' ? 0 : remainingMs(clock, 'WHITE', now);
  const black = flagged === 'BLACK' ? 0 : remainingMs(clock, 'BLACK', now);
  return anchor(white, black, null, now);
}

/**
 * 5:03, 0:42, then tenths under ten seconds (0:09.4), where they start to matter.
 * Rounded DOWN: showing 0:01 when 0.6 s remain is honest; showing 0:01 when 0.4 s remain
 * would promise time that is not there.
 */
export function formatClock(ms: number): string {
  const safe = Math.max(0, ms);
  const totalSeconds = Math.floor(safe / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  const base = `${minutes}:${seconds.toString().padStart(2, '0')}`;
  if (safe >= 10_000) return base;
  return `${base}.${Math.floor((safe % 1000) / 100)}`;
}
