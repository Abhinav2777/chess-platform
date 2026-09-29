# Browser checks

Headless-Chromium scripts that drive the real UI as two (or three) isolated users. They are
**manual checks**, not CI: they need the backend, Valkey and `npm run dev` running.

| Script | Proves | Needs |
|---|---|---|
| `npm run e2e:lobby` | Matchmaking end to end: both players land in one game with opposite colours, a move reaches the opponent, clocks tick; the game is resigned and **both players see their rating change** (outbox → relay → ElasticMQ → worker → push), then the lobby shows it; cancel works. Needs `docker compose` up incl. ElasticMQ. | `bootRun` (local profile), `npm run dev` |
| `npm run e2e:outage` | Phase 4's done-when in a browser: Valkey is **paused** mid-game, play continues via the client's polling fallback ("Live · updates delayed"), and live events resume after unpause. Prints per-move latency. | `bootRun --args='--spring.profiles.active=local --chess.realtime.fanout=valkey'` (in `local` fanout mode Valkey carries no moves, so there is nothing to degrade), `npm run dev`, the compose container named `chess-valkey` |

Chromium defaults to `/usr/bin/chromium`; set `CHROMIUM_PATH` otherwise. Screenshots go to
`e2e/out/` (git-ignored). The outage script always unpauses Valkey, even on failure.
