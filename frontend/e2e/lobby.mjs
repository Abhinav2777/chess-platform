import { chromium } from 'playwright-core';
const SHOTS = process.argv[2] ?? 'e2e/out';
import { mkdirSync } from 'node:fs';
mkdirSync(SHOTS, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH ?? '/usr/bin/chromium', headless: true });
const consoleErrors = [];
async function user(name) {
  const ctx = await browser.newContext({ viewport: { width: 1100, height: 800 } });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`${name}: ${m.text()}`); });
  await page.goto('http://localhost:5173');
  await page.getByRole('button', { name: 'Create an account' }).click();
  await page.getByPlaceholder('username').fill(name);
  await page.getByPlaceholder('password').fill('correct-horse-battery');
  await page.getByRole('button', { name: 'Register' }).click();
  await page.getByText(`Hello, ${name}`).waitFor({ timeout: 5000 });
  return page;
}
const seed = Date.now().toString(36).slice(-5);
const a = await user('ua' + seed), b = await user('ub' + seed);

await a.getByRole('button', { name: '5+3 blitz' }).click();
await a.getByText(/Looking for a 5\+3 opponent/).waitFor({ timeout: 5000 });
await a.screenshot({ path: `${SHOTS}/1-seeking.png` });
console.log('A seeking:', await a.locator('.seek.seeking span').innerText());
await b.getByRole('button', { name: '5+3 blitz' }).click();

const t0 = Date.now();
await Promise.all([a.locator('.board').waitFor({ timeout: 10000 }), b.locator('.board').waitFor({ timeout: 10000 })]);
console.log(`both on a board after ${Date.now() - t0} ms`);
const sideOf = async (p) => (await p.locator('.clock-name', { hasText: '(you)' }).innerText()).includes('white') ? 'WHITE' : 'BLACK';
const [sa, sb] = [await sideOf(a), await sideOf(b)];
console.log('sides:', sa, sb);
const white = sa === 'WHITE' ? a : b, black = sa === 'WHITE' ? b : a;

await white.getByText('Your move').waitFor({ timeout: 5000 });
await white.getByRole('button', { name: 'e2', exact: true }).click();
await white.getByRole('button', { name: 'e4', exact: true }).click();
await black.locator('ol.moves li', { hasText: 'e4' }).waitFor({ timeout: 5000 });
await black.getByText('Your move').waitFor({ timeout: 5000 });
const running = black.locator('.clock.running .clock-time');
const c1 = await running.innerText(); await black.waitForTimeout(2100); const c2 = await running.innerText();
console.log(`black's running clock: ${c1} -> ${c2}`, c1 !== c2 ? '(ticking)' : '(NOT ticking)');
await white.screenshot({ path: `${SHOTS}/2-white.png` });
await black.screenshot({ path: `${SHOTS}/3-black.png` });

const c = await user('uc' + seed);
await c.getByRole('button', { name: '1+0 bullet' }).click();
await c.getByText(/Looking for a 1\+0 opponent/).waitFor({ timeout: 5000 });
await c.getByRole('button', { name: 'Cancel' }).click();
await c.getByRole('button', { name: '1+0 bullet' }).waitFor({ timeout: 5000 });
console.log('C cancelled; play buttons back');

console.log('console errors:', consoleErrors.length ? consoleErrors : 'none');
await browser.close();
