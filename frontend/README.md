# Frontend

React + TypeScript + Vite. **No chess library and no chess rules.**

```bash
npm install
npm run dev          # http://localhost:5173
```

Requires the backend on `localhost:8080` with Docker Compose up. Port 5173 is one of the
origins the backend permits for CORS and the WebSocket handshake
(`chess.realtime.allowed-origins`) — changing it here means changing it there.

## Scope

Deliberately thin. The roadmap caps the frontend at ~6 hours across the whole project,
because this is a backend portfolio piece and an elaborate UI would consume time that
belongs to Phase 3's clock work. What exists: sign in, challenge by username, play, resign,
a move list, and a connection indicator.

## The four things worth reading

**`GameSocket.ts`** — the protocol client. First-frame auth, application heartbeat, and
reconnection with **exponential backoff plus full jitter**. The jitter is the part that
matters: when a server restarts it drops every connection at the same instant, and without
randomisation every client retries in lockstep and the reconnect storm can stop the server
coming back at all.

Note where the backoff resets — on `AUTH_OK`, not on socket open. A server that accepts TCP
connections but rejects every token would otherwise look healthy and be hammered at full
rate forever.

**`useGame.ts`** — one line carries the weight:

```ts
onSnapshot: (incoming) => setSnapshot(incoming)
```

The client adopts the server's snapshot unconditionally, discarding whatever it believed.
That is what lets the transport be unreliable: a dropped message is a display gap, never
divergence — so there is no replay buffer and no per-client cursor anywhere in this
codebase (ADR-007).

**`Board.tsx`** — contains **no chess logic**. It cannot distinguish a legal move from an
illegal one. Every square it will accept comes from the server's `legalMoves`, so "the
client is never authoritative" is structural rather than a convention someone could break.

Promotion is detected without any rules knowledge: the plain move is absent from
`legalMoves` while the five-character forms are present. And there is no default to
queen — underpromotion to a knight is a real tactic.

**`api.ts`** — the access token lives in a module variable, never `localStorage`. Anything
in web storage is readable by any script on the page. The refresh token is never seen by
this code at all: it is an httpOnly cookie the browser attaches itself, which is why every
request sets `credentials: 'include'`.

## Why the board is hand-written

`react-chessboard` is the obvious choice and its v5 API (a single `options` prop) is
perfectly reasonable. Two reasons it is not used:

1. Rendering a board is an 8×8 grid and a FEN parser — about eighty lines. Integrating,
   configuring and version-tracking a dependency to do that is not obviously less work.
2. ADR-002's principle is about not reimplementing a **rules engine**, which is genuinely
   hard and wrong in subtle ways. A grid of `div`s is not that.

Swapping one in later is a single component change; nothing else depends on it.

## Testing with two players

Use **one normal window and one private window**, or two different browsers — not two tabs.

The refresh token is an httpOnly cookie, and cookies are per-browser, not per-tab. Signing
in as a second user in a second tab overwrites the first user's cookie. Access tokens are
unaffected (they live in a module variable, one per tab), but the first session cannot
refresh once its 15-minute token expires.

This is the design working rather than a flaw: an httpOnly cookie is scoped to the browser
precisely so script cannot juggle identities. The cost is that two users need two cookie
jars.

**"Opponent offline" right after challenging is correct.** Presence means "connected to
this game", not "signed in". There is no invitation system — the other player opens the
game from their lobby, which polls every ten seconds, and the badge flips then.

## Why the pieces are six glyphs, not twelve

The obvious mapping uses U+2654–2659 for white pieces and U+265A–265F for black. It does
not survive real font stacks — plenty of systems ship the outline set and not the filled
one, and the black pieces render as tofu boxes. Partial coverage of a Unicode block is
normal and there is no way to feature-detect it.

So one glyph per piece type serves both colours, tinted with `color` and
`-webkit-text-stroke`. Half the surface area, and a white piece reads as genuinely white
rather than hollow.

## Known gaps

- **The move list resets on reconnect.** The snapshot carries the position but not the
  move log, so a reconnect mid-game shows an empty list rather than one contradicting the
  board. `GET /api/games/{id}` returns the full log; wiring it in is a small follow-up.
- No clock display — the clock is Phase 3.
- No spectators, no draw offers, no takebacks. P2 at best.
- **No invitation or notification.** A challenged player finds the game by looking at their
  lobby, which polls. A lobby-wide WebSocket channel is real design work — who subscribes
  to what, and when — for a screen people look at for five seconds.
- **No automatic token refresh.** A session ends after 15 minutes and requires signing in
  again. `api.refresh()` exists; calling it on a timer or on the first 401 is a small
  follow-up, and the interesting part is doing it without a thundering herd of refreshes
  when many tabs wake at once.
