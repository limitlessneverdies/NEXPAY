import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import path from 'node:path';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createApp } from '../server/src/server.mjs';

const dir = mkdtempSync(path.join(tmpdir(), 'nexpay-partial-'));
const app = createApp({ dir, rateLimit: false, enableWebWallet: true, allowedOrigin: 'http://127.0.0.1:8789' });
await new Promise(r => app.server.listen(8789, '127.0.0.1', r));
const browser = await chromium.launch({ executablePath: 'C:\\Users\\HP\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe', headless: true, args: ['--no-sandbox'] });
async function idle(p) { await p.locator('p[role=status]').waitFor({ state: 'detached', timeout: 20000 }); }
async function nav(p, where) { await p.getByRole('button', { name: where, exact: true }).last().click(); await idle(p); }
async function confirm(p) { await p.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click(); await p.getByRole('dialog').waitFor({ state: 'hidden' }); await idle(p); }
const snap = p => p.evaluate(() => PailaTest.snapshot());
try {
  const ca = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' }), cb = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
  const errs = [];
  const a = await ca.newPage(), b = await cb.newPage();
  a.on('pageerror', e => errs.push('A: ' + e.message)); b.on('pageerror', e => errs.push('B: ' + e.message));
  await a.goto('http://127.0.0.1:8789/lab'); await b.goto('http://127.0.0.1:8789/lab');
  await a.getByLabel('Your name', { exact: true }).fill('Sender');
  await a.getByRole('button', { name: 'Create my test wallet' }).click(); await idle(a);
  console.log('a registered:', (await snap(a)).wallet.balanceMinor);
  await b.getByLabel('Your name', { exact: true }).fill('Shop');
  await b.getByRole('button', { name: 'Create my test wallet' }).click(); await idle(b);
  console.log('b registered:', (await snap(b)).wallet.balanceMinor);
  console.log('b buttons:', await b.evaluate(() => Array.from(document.querySelectorAll('button')).map(x => x.textContent.trim().slice(0, 24)).join(' | ')));
  console.log('b headings:', await b.evaluate(() => Array.from(document.querySelectorAll('h1,h2')).map(x => x.textContent.trim().slice(0, 40)).join(' | ')));
  let s = await snap(a);
  console.log('sender total:', s.wallet.balanceMinor + s.wallet.vouchers.filter(v => v.status === 'reserved').reduce((x, v) => x + v.amount, 0), 'auto-note:', s.wallet.vouchers[0].amount);
  await nav(b, 'Receive');
  const code = await b.locator('#receive-code').inputValue();
  console.log('code len:', code.length, 'head:', code.slice(0, 8));
  // go offline on both, send Rs 20 from the Rs 2,500 pool note
  await ca.setOffline(true); await cb.setOffline(true);
  await nav(a, 'Send');
  await a.getByRole('button', { name: 'Offline QR', exact: true }).click(); await idle(a);
  await a.getByLabel("Receiver's code", { exact: true }).fill(code);
  await a.getByLabel('Amount in test rupees').fill('20');
  console.log('a send buttons:', await a.evaluate(() => Array.from(document.querySelectorAll('button')).map(x => x.textContent.trim().slice(0, 24)).join(' | ')));
  console.log('a alerts:', await a.evaluate(() => Array.from(document.querySelectorAll('[role=alert]')).map(x => x.textContent.trim().slice(0, 120)).join(' || ')));
  console.log('a review disabled:', await a.evaluate(() => { const b = Array.from(document.querySelectorAll('button')).find(x => x.textContent.trim() === 'Review payment'); return b ? b.disabled : 'missing'; }), 'queue:', JSON.stringify((await snap(a)).queue));
  await a.evaluate(() => { document.getElementById('send-form').requestSubmit(); });
  await a.waitForTimeout(1500);
  console.log('dialogs:', await a.locator('[role=dialog]').count(), 'alerts:', await a.evaluate(() => Array.from(document.querySelectorAll('[role=alert]')).map(x => x.textContent.trim().slice(0, 150)).join(' || ')));
  console.log('pageerrors:', JSON.stringify(errs));
  console.log('qr-pressed:', await a.evaluate(() => { const t = Array.from(document.querySelectorAll('.tab')).find(x => x.textContent.includes('Offline')); return t ? t.getAttribute('aria-pressed') : 'no-tab'; }));
  await confirm(a);
  const packet = await a.locator('#payment-code').inputValue();
  await nav(b, 'Receive');
  await b.getByLabel('Payment code', { exact: true }).fill(packet);
  await b.getByRole('button', { name: 'Save received payment' }).click(); await idle(b);
  s = await snap(b);
  console.log('recipient incoming:', s.incoming[0].status, 'amount:', s.incoming[0].amount);
  assert.equal(s.incoming[0].amount, 2000);
  // reconnect + settle
  await cb.setOffline(false); await idle(b);
  await nav(b, 'Settings');
  await b.getByRole('button', { name: 'Sync now', exact: true }).click(); await idle(b);
  s = await snap(b);
  console.log('recipient settled:', s.incoming[0].status, 'balance:', s.wallet.balanceMinor);
  assert.equal(s.incoming[0].status, 'settled');
  await ca.setOffline(false); await idle(a);
  await nav(a, 'Settings');
  await a.getByRole('button', { name: 'Sync now', exact: true }).click(); await idle(a);
  s = await snap(a);
  const notes = s.wallet.vouchers.filter(v => v.status === 'reserved');
  console.log('sender remainder notes:', notes.map(v => v.amount).join(','));
  assert(notes.some(v => v.amount === 248000), 'remainder 248000 missing');
  console.log('PARTIAL-POOL-DEMO PASS');
} finally { await browser.close(); app.server.close(); app.ledger.close(); rmSync(dir, { recursive: true, force: true }); }
