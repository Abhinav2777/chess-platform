// Phase 8.3 (ADR-024): live games across a rolling deploy.
//
// Each VU plays ONE real game: two players, two WebSockets, random legal moves (the server sends
// the legal list). When a socket is closed — 1001 from SocketDrain during a rollout — the player
// does what the browser does: reconnect after 0–500 ms of jitter, AUTH, SUBSCRIBE, resync from
// GAME_SNAPSHOT. A move in flight at the cut is re-sent with the SAME clientMoveId: the server's
// idempotency key (ADR-005) must apply it exactly once.
//
// Verified per game, at the end, against GET /api/games/{id}:
//   - every move either player saw acknowledged is on the server, at that ply, unchanged
//   - nothing beyond what was acknowledged (no phantom moves)
// Verified live, per player:
//   - clocks never go up (increment 0): a clock rebuilt wrongly on another pod would show as a jump
//
// Run with loadtest/rolling-deploy.sh, which triggers `kubectl rollout restart` mid-test.
import http from 'k6/http';
import { check } from 'k6';
import { WebSocket } from 'k6/websockets';
import { setTimeout, clearTimeout } from 'k6/timers';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost';
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';
const GAMES = Number(__ENV.GAMES || 40);
const PLAY_SECONDS = Number(__ENV.PLAY_SECONDS || 180);
const CLOCK_TOLERANCE_MS = 5;

const goingAway = new Counter('ws_closes_going_away');      // 1001 — expected during a rollout
const abnormal = new Counter('ws_closes_abnormal');         // anything else we did not ask for
const reconnects = new Counter('ws_reconnects');
const reconnectMs = new Trend('ws_reconnect_ms', true);     // close → AUTH_OK on the new socket
const moveAckMs = new Trend('move_ack_ms', true);           // MOVE sent → own MOVE_MADE
const movesAcked = new Counter('moves_acked');
const movesResent = new Counter('moves_resent');            // same clientMoveId after a reconnect
const movesRejected = new Counter('moves_rejected');        // ERROR other than a resync conflict
const conflicts = new Counter('moves_conflict_resync');
const clockAnomalies = new Counter('clock_anomalies');
const gamesVerified = new Counter('games_verified');
const gamesInconsistent = new Counter('games_inconsistent');
const gamesFinished = new Counter('games_finished_naturally');

export const options = {
  // p99 too: docs/perf/README.md requires p50/p95/p99, and k6's default summary stops at p95.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    games: { executor: 'per-vu-iterations', vus: GAMES, iterations: 1, maxDuration: `${PLAY_SECONDS + 120}s` },
  },
  thresholds: {
    games_inconsistent: ['count==0'],
    clock_anomalies: ['count==0'],
    ws_closes_abnormal: ['count==0'],
    moves_rejected: ['count==0'],
    games_verified: [`count==${GAMES}`],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };
const auth = (token) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function register(name) {
  const res = http.post(`${BASE}/api/auth/register`,
    JSON.stringify({ username: name, email: `${name}@example.com`, password: 'correct-horse-battery' }),
    { headers: JSON_HEADERS });
  check(res, { 'registered': (r) => r.status === 201 });
  return res.json('accessToken');
}

export default function () {
  const tag = `${__VU}${Date.now().toString(36).slice(-6)}`;
  const names = { WHITE: `lw${tag}`, BLACK: `lb${tag}` };
  const tokens = { WHITE: register(names.WHITE), BLACK: register(names.BLACK) };
  const created = http.post(`${BASE}/api/games`, JSON.stringify({
    opponentUsername: names.BLACK, playAs: 'WHITE', initialSeconds: 600, incrementSeconds: 0,
  }), auth(tokens.WHITE));
  check(created, { 'game created': (r) => r.status === 201 });

  const game = {
    id: created.json('id'), ply: 0, sideToMove: 'WHITE', legal: null,
    over: false, ended: false, deadline: Date.now() + PLAY_SECONDS * 1000,
  };
  const players = ['WHITE', 'BLACK'].map((side) => makePlayer(side, tokens[side], game));
  game.players = players;
  players.forEach((p) => p.connect());
}

function makePlayer(side, token, game) {
  const p = {
    side, token, ws: null, pending: null, moveTimer: null, closingByUs: false,
    reconnectFrom: null, acked: {}, lastClock: null,
  };

  p.send = (type, payload) => {
    if (p.ws && p.ws.readyState === 1) {
      p.ws.send(JSON.stringify({ v: 1, type, ts: new Date().toISOString(), payload }));
      return true;
    }
    return false;
  };

  p.connect = () => {
    const ws = new WebSocket(WS_URL);
    p.ws = ws;
    ws.onopen = () => p.send('AUTH', { token: p.token });
    ws.onmessage = (event) => onMessage(p, game, JSON.parse(event.data));
    ws.onclose = (event) => onClose(p, game, event.code);
    ws.onerror = () => {};
  };
  return p;
}

function onMessage(p, game, msg) {
  const body = msg.payload;
  switch (msg.type) {
    case 'AUTH_OK':
      if (p.reconnectFrom !== null) {
        reconnectMs.add(Date.now() - p.reconnectFrom);
        reconnects.add(1);
        p.reconnectFrom = null;
      }
      p.send('SUBSCRIBE', { gameId: game.id });
      break;

    case 'GAME_SNAPSHOT':
      observeClocks(p, body.ply, body.whiteMsLeft, body.blackMsLeft, 'snapshot');
      (body.moves || []).forEach((san, i) => { p.acked[i + 1] = san; });
      if (body.ply >= game.ply) {
        game.ply = body.ply;
        game.sideToMove = body.sideToMove;
        game.legal = body.legalMoves;
      }
      if (body.status !== 'ACTIVE') { game.over = true; return endGame(game); }
      if (p.pending) {
        if (body.ply > p.pending.expectedPly) {
          p.pending = null; // applied before the cut; the echo was lost with the socket
        } else if (body.ply === p.pending.expectedPly && game.sideToMove === p.side) {
          movesResent.add(1); // never reached the server, or did and was not acknowledged
          p.send('MOVE', p.pending.payload);
        }
      }
      schedule(p, game);
      break;

    case 'MOVE_MADE':
      p.acked[body.ply] = body.san;
      observeClocks(p, body.ply, body.whiteMsLeft, body.blackMsLeft, 'move');
      if (body.ply > game.ply) {
        game.ply = body.ply;
        game.sideToMove = body.sideToMove;
        game.legal = body.legalMoves;
      }
      if (p.pending && body.ply === p.pending.expectedPly + 1) {
        moveAckMs.add(Date.now() - p.pending.sentAt);
        movesAcked.add(1);
        p.pending = null;
      }
      game.players.forEach((q) => schedule(q, game));
      break;

    case 'GAME_FINISHED':
      game.over = true;
      gamesFinished.add(1);
      endGame(game);
      break;

    case 'ERROR':
      if (body && body.code === 'CONFLICT') {
        conflicts.add(1); // board was stale; resync like the client does
        p.pending = null;
        p.send('SUBSCRIBE', { gameId: game.id });
      } else {
        movesRejected.add(1);
        console.warn(`game ${game.id} ${p.side}: ERROR ${JSON.stringify(body)}`);
      }
      break;
  }
}

function onClose(p, game, code) {
  if (p.closingByUs) return;
  if (code === 1001) goingAway.add(1);
  else { abnormal.add(1); console.warn(`game ${game.id} ${p.side}: socket closed with ${code}`); }
  if (p.moveTimer) { clearTimeout(p.moveTimer); p.moveTimer = null; }
  p.reconnectFrom = Date.now();
  setTimeout(() => { if (!game.ended) p.connect(); }, Math.random() * 500);
}

// Clocks only go down (increment 0). Compared per player, against that player's previous
// observation at a lower ply, or a snapshot against the same ply's move (a snapshot is taken
// later, so the running clock can only be lower). Stale, lower-ply events are ignored.
function observeClocks(p, ply, white, black, kind) {
  const last = p.lastClock;
  if (last && (ply > last.ply || (ply === last.ply && kind === 'snapshot'))) {
    if (white > last.white + CLOCK_TOLERANCE_MS || black > last.black + CLOCK_TOLERANCE_MS) {
      clockAnomalies.add(1);
      console.warn(`${p.side}: clock went up at ply ${ply}: ${JSON.stringify(last)} -> w=${white} b=${black} (${kind})`);
    }
  }
  if (!last || ply > last.ply || (ply === last.ply && kind === 'snapshot')) {
    p.lastClock = { ply, white, black };
  }
}

function schedule(p, game) {
  if (game.ended) return;
  if (Date.now() > game.deadline) return endGame(game);
  if (game.over || game.sideToMove !== p.side || p.pending || p.moveTimer || !game.legal) return;
  p.moveTimer = setTimeout(() => { p.moveTimer = null; move(p, game); }, 800 + Math.random() * 1200);
}

function move(p, game) {
  if (game.ended || game.over || game.sideToMove !== p.side || p.pending || !game.legal || game.legal.length === 0) return;
  const quiet = game.legal.filter((u) => u.length === 4);
  const uci = (quiet.length ? quiet : game.legal)[Math.floor(Math.random() * (quiet.length || game.legal.length))];
  const promotion = uci.length === 5 ? { q: 'QUEEN', r: 'ROOK', b: 'BISHOP', n: 'KNIGHT' }[uci[4]] : null;
  const payload = { gameId: game.id, clientMoveId: uuid(), expectedPly: game.ply,
    from: uci.slice(0, 2), to: uci.slice(2, 4), promotion };
  p.pending = { payload, expectedPly: game.ply, sentAt: Date.now() };
  p.send('MOVE', payload); // if the socket is down, the snapshot after reconnect re-sends it
}

function endGame(game) {
  if (game.ended) return;
  game.ended = true;
  game.players.forEach((p) => { if (p.moveTimer) clearTimeout(p.moveTimer); });
  verify(game);
  game.players.forEach((p) => { p.closingByUs = true; if (p.ws) p.ws.close(); });
}

function verify(game) {
  const res = http.get(`${BASE}/api/games/${game.id}`, auth(game.players[0].token));
  if (res.status !== 200) { gamesInconsistent.add(1); console.error(`game ${game.id}: GET ${res.status}`); return; }
  const server = {};
  res.json('moves').forEach((m) => { server[m.ply] = m.san; });
  const serverPly = res.json('game.ply');
  let ok = true;
  for (const p of game.players) {
    for (const [ply, san] of Object.entries(p.acked)) {
      if (server[ply] !== san) { ok = false; console.error(`game ${game.id}: ${p.side} saw ply ${ply}=${san}, server has ${server[ply]}`); }
    }
  }
  const maxAcked = Math.max(0, ...game.players.flatMap((p) => Object.keys(p.acked).map(Number)));
  // The server may be at most one ply ahead: a move applied in the last instant whose echo the
  // test stopped listening for. More than that would be moves nobody saw — phantoms.
  if (serverPly > maxAcked + 1 || serverPly < maxAcked) {
    ok = false; console.error(`game ${game.id}: server at ply ${serverPly}, players saw up to ${maxAcked}`);
  }
  const last = game.players[0].lastClock;
  if (last && (res.json('game.whiteMsLeft') > last.white + CLOCK_TOLERANCE_MS
      || res.json('game.blackMsLeft') > last.black + CLOCK_TOLERANCE_MS)) {
    clockAnomalies.add(1); console.warn(`game ${game.id}: final clock above last seen`);
  }
  if (ok) gamesVerified.add(1); else gamesInconsistent.add(1);
}
