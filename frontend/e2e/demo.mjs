// The README demo (Phase 10.6): two browsers, recorded. Clip 1 — seek, pair, play, checkmate, the
// rating arrives asynchronously. Clip 2 — mid-game, `kubectl rollout restart deployment/api`
// replaces both API pods; the players reconnect and play on from the same position.
//
//   APP_URL=http://localhost node e2e/demo.mjs e2e/out/demo     (kind, k8s/deploy.sh, HPA removed)
//
// Writes one .webm per player and marks.json (phase timestamps, ms since recording started), which
// docs/demo/make-gif.sh cuts into GIFs. Captions are an overlay drawn by this script, not the app.
import { chromium } from 'playwright-core';
import { execSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readdirSync, renameSync } from 'node:fs';

const APP_URL = process.env.APP_URL ?? 'http://localhost';
const OUT = process.argv[2] ?? 'e2e/out/demo';
// Two columns at this width: the board, and the side panel with the always-visible connection
// badge — the visual proof of the reconnect. The caption sits in the panel's empty lower half.
const VIEW = { width: 1000, height: 720 };
let PACE = Number(process.env.PACE_MS ?? 900);            // between a player's actions; slower in clip 2
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH ?? '/usr/bin/chromium', headless: true });
const errors = [];
const t0 = Date.now();                                      // ≈ both recordings' start
const marks = {};
const videoStart = {};
const mark = (name) => { marks[name] = Date.now() - t0; console.log(`[${(marks[name] / 1000).toFixed(1)}s] ${name}`); };
const pause = (ms = PACE) => new Promise((r) => setTimeout(r, ms));

async function player(name) {
  const ctx = await browser.newContext({ viewport: VIEW, recordVideo: { dir: `${OUT}/${name}`, size: VIEW } });
  // Each recording starts with its page, not at t0: the second player's starts seconds later.
  // Kept, so the cutter can line the two videos up (a frame "at 18 s" is otherwise two moments).
  videoStart[name] = Date.now() - t0;
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`${name}: ${m.text()}`); });
  await page.goto(APP_URL);
  await page.getByRole('button', { name: 'Create an account' }).click();
  await page.getByPlaceholder('username').fill(name);
  await page.getByPlaceholder('password').fill('correct-horse-battery');
  await page.getByRole('button', { name: 'Register' }).click();
  await page.getByText(`Hello, ${name}`).waitFor({ timeout: 15000 });
  // A reconnect can last well under a second — too short to catch by looking between moves — so
  // the page records that it happened.
  // It also times each one: from the badge leaving "Live" to "Live" again.
  await page.evaluate(() => {
    window.__sawReconnect = false;
    window.__reconnectMs = [];
    let since = null;
    new MutationObserver(() => {
      const live = document.querySelector('.connection.live');
      if (!live && document.querySelector('.connection') && since === null) {
        since = performance.now(); window.__sawReconnect = true;
      } else if (live && since !== null) {
        window.__reconnectMs.push(Math.round(performance.now() - since)); since = null;
      }
    }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] });
  });
  return { ctx, page, name };
}

// A caption bar over the page — the same text on both sides of the side-by-side video.
async function caption(text) {
  await Promise.all([a, b].map((p) => p.page.evaluate((t) => {
    let el = document.getElementById('demo-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'demo-caption';
      el.style.cssText = 'position:fixed;left:587px;width:340px;top:612px;z-index:9999;'
        + 'padding:14px 16px;border-radius:10px;font:600 16px/1.4 system-ui,sans-serif;color:#fff;'
        + 'background:#1f2a1c;border:2px solid #6aa84f;box-shadow:0 4px 18px rgba(0,0,0,.4)';
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text)));
}

async function play(page, uci) {
  await page.getByRole('button', { name: uci.slice(0, 2), exact: true }).click();
  await pause(250);
  await page.getByRole('button', { name: uci.slice(2, 4), exact: true }).click();
}

// Waits until this page's move list has `count` moves.
const seen = (page, count) => page.locator('ol.moves li').nth(count - 1).waitFor({ timeout: 20000 });

async function pair() {
  await a.page.getByRole('button', { name: '5+3 blitz' }).click();
  await pause();
  await b.page.getByRole('button', { name: '5+3 blitz' }).click();
  await Promise.all([a.page.locator('.board').waitFor({ timeout: 10000 }), b.page.locator('.board').waitFor({ timeout: 10000 })]);
  const white = (await a.page.locator('.clock-name', { hasText: '(you)' }).innerText()).includes('white') ? a : b;
  return { white, black: white === a ? b : a };
}

// Plays a line, alternating sides; each move waits for its echo on both boards.
async function line(sides, moves, beforeEach = async () => {}) {
  let count = await sides.white.page.locator('ol.moves li').count();
  for (const uci of moves) {
    await beforeEach();
    const mover = count % 2 === 0 ? sides.white : sides.black;
    await mover.page.locator('.connection.live').waitFor({ timeout: 60000 });  // after a reconnect
    await play(mover.page, uci);
    count++;
    await Promise.all([seen(sides.white.page, count), seen(sides.black.page, count)]);
    await pause();
  }
}

// A take is only valid if nothing *underneath* the demo failed. On a laptop, a rolling restart's
// extra JVM once pushed the host into the kernel's OOM killer, which took the ingress controller —
// the browsers' errors then came from the machine, not the system shown. Compared at the end.
const infraRestarts = () => execSync(
  "kubectl get pods -A -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.labels.app\\.kubernetes\\.io/name}={.status.containerStatuses[0].restartCount}{\"\\n\"}{end}'")
  .toString().split('\n').filter((l) => /ingress-nginx|valkey|postgres|elasticmq/.test(l)).sort().join(' ');
const restartsBefore = infraRestarts();

const seed = Date.now().toString(36).slice(-4);
const a = await player('alice' + seed);
const b = await player('bob' + seed);

// ---------------------------------------------------------------- clip 1: the product
mark('clip1-start');
await caption('Two players seek a 5+3 game — matched by rating through a Valkey queue');
let sides = await pair();
await pause(1200);
await caption('Moves travel over WebSockets; the clock is computed by the server from database time');
await line(sides, ['e2e4', 'e7e5', 'f1c4', 'b8c6', 'd1h5', 'g8f6']);
await play(sides.white.page, 'h5f7');
await caption('Checkmate. The rating update arrives asynchronously: outbox → SQS → worker → WebSocket');
await Promise.all([
  sides.white.page.locator('.rating', { hasText: '+16' }).waitFor({ timeout: 20000 }),
  sides.black.page.locator('.rating', { hasText: '-16' }).waitFor({ timeout: 20000 }),
]);
mark('rated');
await pause(3000);
mark('clip1-end');

// ---------------------------------------------------------------- clip 2: the engineering
await Promise.all([a, b].map((p) => p.page.getByRole('button', { name: /Back/ }).click()));
await pause(800);
sides = await pair();
mark('clip2-start');
const pods = () => execSync("kubectl -n chess get pods -l app.kubernetes.io/name=api --field-selector=status.phase=Running -o jsonpath='{.items[*].metadata.name}'").toString().trim().split(' ').map((n) => n.slice(-5)).join(', ');
const before = pods();
await caption(`A new game. API pods serving it: ${before}`);
const restartLine = ['e2e4', 'e7e5', 'g1f3', 'b8c6', 'f1c4', 'f8c5', 'b1c3', 'g8f6', 'd2d3', 'd7d6', 'c1g5', 'h7h6',
  'g5h4', 'c8g4', 'h2h3', 'g4h5', 'a2a3', 'a7a6', 'b2b4', 'c5a7', 'e1g1', 'e8g8', 'f1e1', 'f8e8',
  'd1d2', 'd8d7', 'a1b1', 'a8b8', 'h4g3', 'h5g6', 'b1a1', 'b8a8', 'g3h4', 'g6h5', 'a1b1', 'a8b8'];
// (all 36 plies checked legal with chesslib, no mate or draw)
await line(sides, restartLine.slice(0, 4));
// Only reconnects from here on count — not the game page's first connection.
await Promise.all([sides.white, sides.black].map((p) => p.page.evaluate(() => {
  window.__reconnectMs = []; window.__sawReconnect = false;
})));
execSync('kubectl -n chess rollout restart deployment/api');
mark('restart');
await caption('kubectl rollout restart deployment/api — new pods start; the old ones keep serving');
PACE = Number(process.env.PACE2_MS ?? 1500);
let drained = false;
let replaced = false;
const sawReconnect = async () => (await Promise.all([sides.white, sides.black]
  .map((p) => p.page.evaluate(() => window.__sawReconnect)))).some(Boolean);
async function progress() {
  if (!drained && await sawReconnect()) {
    drained = true; mark('drain');
    await caption('Old pods drain: sockets closed with 1001 GOING_AWAY — the browsers reconnect to a new pod');
  } else if (drained && !replaced) {
    const now = pods();
    if (!now.split(', ').some((p) => before.includes(p))) {
      replaced = true; mark('replaced');
      const times = (await Promise.all([sides.white, sides.black]
        .map((p) => p.page.evaluate(() => window.__reconnectMs)))).flat();
      marks.reconnectMs = times;
      const back = times.length ? `; browsers back within ${Math.max(...times)} ms` : '';
      await caption(`Both API pods replaced (${before} → ${now})${back}. Same game, same clocks, no move lost.`);
    }
  }
}
await line(sides, restartLine.slice(4, -2), progress);
// If the rollout outlasts the line, wait for it (bounded), then show the game still moving.
for (let i = 0; i < 60 && !replaced; i++) { await progress(); await pause(1000); }
await line(sides, restartLine.slice(-2), progress);
await pause(3000);
mark('clip2-end');
if (!drained || !replaced) console.log(`WARNING: drained=${drained} replaced=${replaced}`);

writeFileSync(`${OUT}/marks.json`, JSON.stringify({ marks,
  videoStart: { left: videoStart[a.name], right: videoStart[b.name] } }, null, 2));
await Promise.all([a, b].map((p) => p.ctx.close()));      // flushes the videos
for (const p of [a, b]) {
  const [file] = readdirSync(`${OUT}/${p.name}`);
  renameSync(`${OUT}/${p.name}/${file}`, `${OUT}/${p === a ? 'left' : 'right'}.webm`);
}
console.log('page errors:', errors.length ? errors : 'none');
const restartsAfter = infraRestarts();
if (restartsAfter !== restartsBefore) {
  console.log(`INVALID TAKE: an infrastructure container restarted during the recording\n  before: ${restartsBefore}\n  after:  ${restartsAfter}`);
  process.exitCode = 2;
} else {
  console.log('infrastructure: no container restarted during the take');
}
await browser.close();
