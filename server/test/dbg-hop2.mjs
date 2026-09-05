import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { newKey, exportKey, walletId, pack, unpack, request, hash, canonical } from '../src/protocol.mjs';
import { Ledger } from '../src/ledger.mjs';

const dir = mkdtempSync(path.join(tmpdir(), 'dbg3-'));
let now = 1000000;
const l = new Ledger({ dir, clock: () => now });
const mk = (name) => {
  const k = newKey(), pub = exportKey(k.publicKey), id = walletId(pub);
  l.register(pack('register', { v: 1, publicKey: pub, name, opId: randomUUID(), ts: now }, k.privateKey));
  return { ...k, pub, id };
};
const payOffline = (s, r, note, now, extra = {}) => pack('payment', { v: 1, voucher: note.certificate, to: r.id, requestId: randomUUID(), createdAt: now, ...extra }, s.privateKey);
const ws = Array.from({ length: 9 }, (_, i) => mk('w' + i));
const note = l.action(request(ws[0].privateKey, ws[0].id, 'reserve', { amountMinor: 10000 }, randomUUID(), now));
let pkt = payOffline(ws[0], ws[1], note, now, { amountMinor: 9000 });
for (let i = 1; i <= 4; i++) {
  const pd = unpack(pkt).data;
  pkt = pack('payment', { v: 1, fromKey: exportKey(ws[i].publicKey), to: ws[i + 1].id, requestId: randomUUID(), createdAt: now, amountMinor: 9000 - i, hop: (pd.hop ?? 0) + 1, prev: hash(canonical(pd)), chain: [...(pd.chain || []), pkt] }, ws[i].privateKey);
}
console.log('hop4 head', pkt.slice(0, 20));
console.log('hop4 tail', pkt.slice(-20));
console.log('dots', (pkt.match(/\./g) || []).length);
console.log('badchars', /[^A-Za-z0-9\-_.]/.test(pkt));
console.log('len', pkt.length);
l.close(); rmSync(dir, { recursive: true, force: true });
