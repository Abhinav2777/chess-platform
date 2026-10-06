// A move lost with its socket is sent again after the reconnect — and applied exactly once (10.6).
//
// What a server drain does to a move in flight, done on purpose: Playwright sits between the
// browser and the server, swallows white's first MOVE frame, then closes the socket as a draining
// pod would (1001). The client must reconnect, get the snapshot (still white to move, ply 0), and
// re-send the move with the same clientMoveId. Before the fix, the move silently vanished.
//
// MODE=lost-echo covers the other half: the move DID land, but its echo is lost with the socket.
// The snapshot then shows the board past it, and the client must not send it again.
//
//   APP_URL=http://localhost node e2e/move-across-reconnect.mjs            (MODE=lost-move)
//   APP_URL=http://localhost MODE=lost-echo node e2e/move-across-reconnect.mjs
import { chromium } from 'playwright-core';

const APP_URL = process.env.APP_URL ?? 'http://localhost:5173';
const MODE = process.env.MODE ?? 'lost-move';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH ?? '/usr/bin/chromium', headless: true });
const errors = [];
const fail = async (message) => { console.log('FAIL:', message); await browser.close(); process.exit(1); };

async function user(name, intercept) {
  const ctx = await browser.newContext({ viewport: { width: 1000, height: 720 } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  if (intercept) await intercept(page);
  await page.goto(APP_URL);
  await page.getByRole('button', { name: 'Create an account' }).click();
  await page.getByPlaceholder('username').fill(name);
  await page.getByPlaceholder('password').fill('correct-horse-battery');
  await page.getByRole('button', { name: 'Register' }).click();
  await page.getByText(`Hello, ${name}`).waitFor({ timeout: 15000 });
  return page;
}

// Forwards every frame both ways, except: the first MOVE either player sends (white's — white
// moves first) is dropped and that socket closed with 1001, as SocketDrain does. Records every MOVE.
const sentMoves = [];
let dropped = false;
const drainOnFirstMove = (page) => page.routeWebSocket(/\/ws$/, (ws) => {
  const server = ws.connectToServer();
  ws.onMessage((frame) => {
    const message = JSON.parse(frame);
    if (message.type === 'MOVE') {
      sentMoves.push(message.payload.clientMoveId);
      if (MODE === 'lost-move' && !dropped) {
        dropped = true;
        ws.close({ code: 1001, reason: 'Server shutting down; reconnect' });
        return;
      }
    }
    server.send(frame);
  });
  server.onMessage((frame) => {
    // lost-echo: the move is applied; its MOVE_MADE dies with the socket.
    if (MODE === 'lost-echo' && !dropped && sentMoves.length > 0 && JSON.parse(frame).type === 'MOVE_MADE') {
      dropped = true;
      ws.close({ code: 1001, reason: 'Server shutting down; reconnect' });
      return;
    }
    ws.send(frame);
  });
});

const seed = Date.now().toString(36).slice(-5);
const a = await user('ra' + seed, drainOnFirstMove);
const b = await user('rb' + seed, drainOnFirstMove);
await a.getByRole('button', { name: '5+3 blitz' }).click();
await b.getByRole('button', { name: '5+3 blitz' }).click();
await Promise.all([a.locator('.board').waitFor({ timeout: 10000 }), b.locator('.board').waitFor({ timeout: 10000 })]);
const aIsWhite = (await a.locator('.clock-name', { hasText: '(you)' }).innerText()).includes('white');
const [white, black] = aIsWhite ? [a, b] : [b, a];

await white.getByText('Your move').waitFor({ timeout: 5000 });
await white.getByRole('button', { name: 'e2', exact: true }).click();
await white.getByRole('button', { name: 'e4', exact: true }).click();

// The move must reach the opponent's board — through the reconnect and the re-send.
try {
  await black.locator('ol.moves li', { hasText: 'e4' }).waitFor({ timeout: 15000 });
} catch {
  await fail(`the move never arrived (MOVE frames sent: ${sentMoves.length})`);
}
const plies = await black.locator('ol.moves li').count();
console.log(`MOVE frames sent: ${sentMoves.length}; distinct clientMoveIds: ${new Set(sentMoves).size}`);
console.log(`opponent's move list: ${plies} ply`);
if (!dropped) await fail('the interception never fired');
const expectedFrames = MODE === 'lost-move' ? 2 : 1;
if (sentMoves.length !== expectedFrames || new Set(sentMoves).size !== 1) {
  await fail(`${MODE}: expected ${expectedFrames} MOVE frame(s) with one clientMoveId, got ${sentMoves.length}`);
}
if (plies !== 1) await fail(`expected the move applied once, got ${plies} plies`);
console.log('page errors:', errors.length ? errors : 'none');
console.log(MODE === 'lost-move'
  ? 'PASS: the move lost with its socket was re-sent once and applied once'
  : 'PASS: the move that landed (echo lost) was not re-sent, and is on the board once');
await browser.close();
