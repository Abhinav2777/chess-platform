import { useEffect, useState } from 'react';
import { formatClock, remainingMs, type ClockAnchor } from './clock';
import type { Side } from './protocol';

/** Below this, the clock turns red and shows tenths. */
const LOW_TIME_MS = 10_000;

/**
 * A player's bar above or below the board: avatar, name, presence, clock.
 *
 * The clock re-renders every 100 ms while its side is running and not at all otherwise — two
 * frozen clocks cost nothing. 100 ms is the resolution the tenths display needs; a
 * `requestAnimationFrame` loop would re-render sixty times a second to show the same digits.
 *
 * The interval's timing does not matter for correctness: the value shown is computed from the
 * anchor and `performance.now()` on each render, so a late or throttled tick shows the right
 * time, just less often (see `clock.ts`).
 */
export function PlayerClock({ clock, side, name, isYou, online }: {
  clock: ClockAnchor | null;
  side: Side;
  name: string;
  isYou: boolean;
  /** Shown for the opponent only; undefined hides the indicator. */
  online?: boolean;
}) {
  const running = clock?.running === side;
  const now = useNow(running);
  const ms = clock ? remainingMs(clock, side, now) : null;
  const low = ms !== null && ms < LOW_TIME_MS;

  return (
    <div className="player-bar">
      <div className={`clock${running ? ' running' : ''}${low ? ' low' : ''}`}
           // Announced when it changes hands, not every tick — a screen reader reading out
           // tenths of a second would make the page unusable.
           aria-live={running ? 'off' : 'polite'}>
        <div className="player">
          <span className={`avatar ${side.toLowerCase()}`} aria-hidden="true">{name.slice(0, 1)}</span>
          <div style={{ minWidth: 0 }}>
            <div className="clock-name">
              {name}{isYou ? <span className="you"> (you)</span> : null}
              <span className="hint"> · {side.toLowerCase()}</span>
            </div>
            {online !== undefined && (
              <div className="presence">
                <span className={`dot${online ? ' on' : ''}`} />{online ? 'Online' : 'Offline'}
              </div>
            )}
          </div>
        </div>
        <span className="clock-time">{ms === null ? '–:––' : formatClock(ms)}</span>
      </div>
    </div>
  );
}

function useNow(active: boolean): number {
  const [now, setNow] = useState(() => performance.now());
  useEffect(() => {
    if (!active) return;
    // Take a reading immediately, so a clock that has just started running does not show the
    // stale value from when it last stopped for up to one interval.
    setNow(performance.now());
    const timer = window.setInterval(() => setNow(performance.now()), 100);
    return () => window.clearInterval(timer);
  }, [active]);
  return now;
}
