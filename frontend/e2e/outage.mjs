import { chromium } from 'playwright-core';
import { execSync } from 'node:child_process';
const SHOTS = process.argv[2] ?? 'e2e/out';
import { mkdirSync } from 'node:fs';
mkdirSync(SHOTS, { recursive: true });
const docker = (cmd) => execSync(`docker ${cmd} chess-valkey`, { stdio: 'pipe' });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH ?? '/usr/bin/chromium', headless: true });
async function user(name) {
  const page = await (await browser.newContext({ viewport: { width: 1100, height: 800 } })).newPage();
  await page.goto('http://localhost:5173');
  await page.getByRole('button', { name: 'Create an account' }).click();
  await page.getByPlaceholder('username').fill(name);
  await page.getByPlaceholder('password').fill('correct-horse-battery');
  await page.getByRole('button', { name: 'Register' }).click();
  await page.getByText(`Hello, ${name}`).waitFor({ timeout: 15000 })   // generous: a cold backend's first bcrypt + JIT;
  return page;
}
const label = (p) => p.locator('.connection').innerText();
async function play(mover, watcher, from, to, san, note) {
  const t = Date.now();
  await mover.getByRole('button', { name: from, exact: true }).click();
  await mover.getByRole('button', { name: to, exact: true }).click();
  await mover.locator('ol.moves li', { hasText: san }).waitFor({ timeout: 20000 });
  const moverMs = Date.now() - t;
  await watcher.locator('ol.moves li', { hasText: san }).waitFor({ timeout: 20000 });
  console.log(`${san.padEnd(4)} ${note.padEnd(9)} mover sees it ${String(moverMs).padStart(5)} ms, opponent ${String(Date.now() - t).padStart(5)} ms` +
              ` | labels: "${await label(mover)}" / "${await label(watcher)}"`);
}
try {
  const seed = Date.now().toString(36).slice(-5);
  const a = await user('va' + seed), b = await user('vb' + seed);
  await a.getByRole('button', { name: '5+3 blitz' }).click();
  await b.getByRole('button', { name: '5+3 blitz' }).click();
  await Promise.all([a.locator('.board').waitFor({ timeout: 10000 }), b.locator('.board').waitFor({ timeout: 10000 })]);
  const aIsWhite = (await a.locator('.clock-name', { hasText: '(you)' }).innerText()).includes('white');
  const [w, bl] = aIsWhite ? [a, b] : [b, a];

  await play(w, bl, 'e2', 'e4', 'e4', 'healthy');
  docker('pause'); console.log('--- Valkey paused ---');
  await play(bl, w, 'e7', 'e5', 'e5', 'outage');
  await play(w, bl, 'g1', 'f3', 'Nf3', 'outage');
  await play(bl, w, 'b8', 'c6', 'Nc6', 'outage');
  await w.screenshot({ path: `${SHOTS}/4-degraded.png` });
  docker('unpause'); console.log('--- Valkey unpaused ---');
  // The server's circuit (ValkeyGuard, 5 s) does not know Valkey is back until its window
  // lapses and a call probes. Moves inside the window still go by polling — measured, and
  // the price of not paying a timeout per call during the outage. Wait it out, then live
  // events must be back.
  await w.waitForTimeout(6000);
  await play(w, bl, 'f1', 'b5', 'Bb5', 'recovered');
  await play(bl, w, 'a7', 'a6', 'a6', 'recovered');
  const finalLabels = [await label(w), await label(bl)];
  console.log('final labels:', finalLabels.join(' / '));
  if (finalLabels.some((l) => l !== 'Live')) throw new Error('did not recover to live events');
} finally {
  try { docker('unpause'); } catch { /* already running */ }
  await browser.close();
}
