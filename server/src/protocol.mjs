import { createHash, createPublicKey, sign, verify, randomUUID, generateKeyPairSync } from 'node:crypto';
export const LIMITS = Object.freeze({ requestBytes:32768, grant:100000, offline:50000, online:10000000, noteLife:86400000, redeemGrace:604800000, clockSkew:300000 });
export class Fault extends Error { constructor(code, message, status=400) { super(message); this.code=code; this.status=status; } }
export const fail=(ok,code,message,status=400)=>{if(!ok)throw new Fault(code,message,status);};
export const hash=s=>createHash('sha256').update(s).digest('hex');
export const b64=b=>Buffer.from(b).toString('base64url');
export function unb64(s) {
  fail(typeof s==='string' && s.length>0 && s.length<=24000 && /^[A-Za-z0-9_-]+$/.test(s),'BAD_ENCODING','Invalid base64url.');
  const b=Buffer.from(s,'base64url'); fail(b64(b)===s,'BAD_ENCODING','Non-canonical base64url.'); return b;
}
export function publicKey(encoded) {
  try {
    const der=unb64(encoded); const key=createPublicKey({key:der,format:'der',type:'spki'});
    fail(key.asymmetricKeyType==='ec' && key.asymmetricKeyDetails?.namedCurve==='prime256v1','BAD_KEY','Use an ECDSA P-256 public key.');
    fail(b64(key.export({type:'spki',format:'der'}))===encoded,'BAD_KEY','Use canonical SPKI key encoding.');
    return key;
  } catch(e) { if(e instanceof Fault)throw e; throw new Fault('BAD_KEY','Invalid public key.'); }
}
export const exportKey=key=>b64(key.export({type:'spki',format:'der'}));
export const walletId=pub=>'pa_'+hash(unb64(pub)).slice(0,32);
export const newKey=()=>generateKeyPairSync('ec',{namedCurve:'prime256v1'});
export function pack(kind, data, privateKey) {
  const encoded=b64(JSON.stringify(data));
  const signature=sign('sha256',Buffer.from(`paila:${kind}:v1:${encoded}`),privateKey);
  return `p1.${encoded}.${b64(signature)}`;
}
export function unpack(envelope) {
  fail(typeof envelope==='string' && envelope.length<=23000,'BAD_ENVELOPE','Payment message is too large or invalid.');
  const pieces=envelope.split('.'); fail(pieces.length===3 && pieces[0]==='p1','BAD_ENVELOPE','Invalid signed message.');
  const raw=unb64(pieces[1]); const signature=unb64(pieces[2]);
  fail(signature.length>=8 && signature.length<=72,'BAD_SIGNATURE','Invalid ECDSA signature size.',401);
  let data; try{data=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(raw));}catch{throw new Fault('BAD_ENVELOPE','Invalid message JSON.');}
  fail(data && typeof data==='object' && !Array.isArray(data) && data.v===1,'BAD_VERSION','Unsupported protocol version.');
  return {data,encoded:pieces[1],signature};
}
export function open(kind,envelope,key) {
  const p=unpack(envelope);
  let valid=false;try{valid=verify('sha256',Buffer.from(`paila:${kind}:v1:${p.encoded}`),typeof key==='string'?publicKey(key):key,p.signature);}catch{}
  fail(valid,'BAD_SIGNATURE','Signature verification failed.',401); return p.data;
}
export function text(value, label, max=120) {
  fail(typeof value==='string' && value.trim().length>0 && value.length<=max && !/[\u0000-\u001F\u007F-\u009F\u202A-\u202E\u2066-\u2069]/u.test(value),'BAD_INPUT',`${label} is invalid.`);return value.trim();
}
export function amount(n,max=LIMITS.online) {fail(Number.isSafeInteger(n)&&n>0&&n<=max,'BAD_AMOUNT','Amount must be positive integer paisa within the limit.');return n;}
export function fresh(ts, now) {fail(Number.isSafeInteger(ts)&&Math.abs(now-ts)<=LIMITS.clockSkew,'STALE_REQUEST','Check your device clock and retry.',401);}
export function operationId(s){fail(typeof s==='string'&&/^[A-Za-z0-9_-]{16,80}$/.test(s),'BAD_OPERATION_ID','Invalid operation identifier.');return s;}
export const canonical=o=>o===null||typeof o!=='object'?JSON.stringify(o):Array.isArray(o)?`[${o.map(canonical).join(',')}]`:`{${Object.keys(o).sort().map(k=>`${JSON.stringify(k)}:${canonical(o[k])}`).join(',')}}`;
export function request(key,wallet,op,data={},id=randomUUID(),now=Date.now()) {return pack('request',{v:1,walletId:wallet,op,opId:id,ts:now,data},key);}
export function receiveRequest(key,pub,name,now=Date.now()) {return pack('receive',{v:1,walletId:walletId(pub),publicKey:pub,name,requestId:randomUUID(),createdAt:now,expiresAt:now+86400000},key);}
export function readReceive(raw,now=Date.now()) {
  const p=unpack(raw).data; publicKey(p.publicKey);const r=open('receive',raw,p.publicKey);
  fail(walletId(r.publicKey)===r.walletId,'BAD_RECIPIENT','Recipient key mismatch.');text(r.name,'Name',48);operationId(r.requestId);
  fail(Number.isSafeInteger(r.createdAt)&&Number.isSafeInteger(r.expiresAt)&&r.createdAt<=now+LIMITS.clockSkew&&r.expiresAt>now&&r.expiresAt-r.createdAt<=86400000,'EXPIRED_REQUEST','Ask for a fresh receive code.');return r;
}
export function readPayment(raw,issuerKey,recipient,now=Date.now(),settlement=false) {
  const outer=unpack(raw).data;
  const note=open('voucher',outer.voucher,issuerKey);
  const issuer=hash(typeof issuerKey==='string'?unb64(issuerKey):issuerKey.export({type:'spki',format:'der'}));
  fail(note.issuer===issuer,'WRONG_ISSUER','This note belongs to another server.');
  amount(note.amount,LIMITS.offline);publicKey(note.ownerKey);operationId(note.id);
  fail(walletId(note.ownerKey)===note.owner,'BAD_OWNER','Note owner does not match key.');
  const payment=open('payment',raw,note.ownerKey);
  fail(payment.to===recipient&&payment.to!==note.owner,'WRONG_RECIPIENT','This payment is not addressed to your wallet.',403);operationId(payment.requestId);
  fail(Number.isSafeInteger(note.createdAt)&&Number.isSafeInteger(note.expiresAt)&&note.expiresAt-note.createdAt===LIMITS.noteLife,'BAD_NOTE','Invalid note lifetime.');
  fail(Number.isSafeInteger(payment.createdAt)&&payment.createdAt>=note.createdAt-LIMITS.clockSkew&&payment.createdAt<=note.expiresAt&&payment.createdAt<=now+LIMITS.clockSkew,'BAD_TIME','Invalid payment time.');
  fail(now<note.expiresAt+(settlement?LIMITS.redeemGrace:0),'EXPIRED_NOTE',settlement?'Redemption window has closed.':'This offline note has expired.');
  return {note,payment,paymentHash:hash(canonical(payment))};
}
