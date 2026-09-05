import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { newKey, exportKey, walletId, pack, unpack, request, hash, canonical } from '../src/protocol.mjs';
import { Ledger } from '../src/ledger.mjs';

const dir = mkdtempSync(path.join(tmpdir(), 'dbg2-'));
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
console.log('p0 len', pkt.length);
for (let i = 1; i <= 7; i++) {
  const pd = unpack(pkt).data;
  pkt = pack('payment', { v: 1, fromKey: exportKey(ws[i].publicKey), to: ws[i + 1].id, requestId: randomUUID(), createdAt: now, amountMinor: 9000 - i, hop: (pd.hop ?? 0) + 1, prev: hash(canonical(pd)), chain: [...(pd.chain || []), pkt] }, ws[i].privateKey);
  console.log('hop', (pd.hop ?? 0) + 1, 'len', pkt.length);
}
l.close(); rmSync(dir, { recursive: true, force: true });
