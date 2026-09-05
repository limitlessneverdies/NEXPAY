import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { newKey, exportKey, walletId, pack, unpack, request, hash, canonical } from '../src/protocol.mjs';
import { Ledger } from '../src/ledger.mjs';

const dir = mkdtempSync(path.join(tmpdir(), 'dbg-'));
let now = 1000000;
const l = new Ledger({ dir, clock: () => now });
const mk = (name) => {
  const k = newKey(), pub = exportKey(k.publicKey), id = walletId(pub);
  l.register(pack('register', { v: 1, publicKey: pub, name, opId: randomUUID(), ts: now }, k.privateKey));
  return { ...k, pub, id, act: (op, d = {}, opid = randomUUID()) => l.action(request(k.privateKey, id, op, d, opid, now)) };
};
const payOffline = (sender, recipient, note, now, extra = {}) => pack('payment', { v: 1, voucher: note.certificate, to: recipient.id, requestId: randomUUID(), createdAt: now, ...extra }, sender.privateKey);
const forwardPayment = (sender, parentEnvelope, toId, n, now, extra = {}) => {
  const pd = unpack(parentEnvelope).data;
  return pack('payment', { v: 1, fromKey: exportKey(sender.publicKey), to: toId, requestId: randomUUID(), createdAt: now, amountMinor: n, hop: (pd.hop ?? 0) + 1, prev: hash(canonical(pd)), chain: [...(pd.chain || []), parentEnvelope], ...extra }, sender.privateKey);
};
const a = mk('a'), b = mk('b'), c = mk('c'), d = mk('d'), e = mk('e');
const note = a.act('reserve', { amountMinor: 10000 });
const p0 = payOffline(a, b, note, now, { amountMinor: 4000 });
console.log('c:', JSON.stringify(c.act('redeem', { payment: forwardPayment(b, p0, c.id, 3000, now) })));
console.log('d:', JSON.stringify(d.act('redeem', { payment: forwardPayment(b, p0, d.id, 1000, now) })));
console.log('settlements:', JSON.stringify(l.db.prepare('SELECT sender, recipient, amount FROM settlements').all()));
try {
  console.log('e:', JSON.stringify(e.act('redeem', { payment: forwardPayment(b, p0, e.id, 1000, now) })));
} catch (err) { console.log('e threw:', err.code, err.message); }
l.close(); rmSync(dir, { recursive: true, force: true });
