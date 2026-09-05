import { createHash, createPublicKey, sign, verify, randomUUID, generateKeyPairSync } from 'node:crypto';
export const LIMITS = Object.freeze({ requestBytes:32768, grant:500000, offline:500000, online:10000000, noteLife:86400000, redeemGrace:604800000, clockSkew:300000, maxHops:3, chainBytes:48000, qrHops:1 });
export class Fault extends Error { constructor(code, message, status=400) { super(message); this.code=code; this.status=status; } }
export const fail=(ok,code,message,status=400)=>{if(!ok)throw new Fault(code,message,status);};
export const hash=s=>createHash('sha256').update(s).digest('hex');
export const b64=b=>Buffer.from(b).toString('base64url');
export function unb64(s) {
  fail(typeof s==='string' && s.length>0 && s.length<=48000 && /^[A-Za-z0-9_-]+$/.test(s),'BAD_ENCODING','Invalid base64url.');
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
  fail(typeof envelope==='string' && envelope.length<=65536,'BAD_ENVELOPE','Payment message is too large or invalid.');
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
  const hop=outer.hop===undefined?0:outer.hop;
  fail(Number.isSafeInteger(hop)&&hop>=0&&hop<=LIMITS.maxHops,'BAD_HOP','Transfer chain is too long or invalid.');
  const links=outer.chain===undefined?[]:outer.chain;
  fail(Array.isArray(links)&&links.length===hop,'BAD_CHAIN','Transfer chain does not match hop count.');
  const noteCert=hop===0?outer.voucher:unpack(links[0]).data.voucher;
  const note=open('voucher',noteCert,issuerKey);
  const issuer=hash(typeof issuerKey==='string'?unb64(issuerKey):issuerKey.export({type:'spki',format:'der'}));
  fail(note.issuer===issuer,'WRONG_ISSUER','This note belongs to another server.');
  amount(note.amount,LIMITS.offline);publicKey(note.ownerKey);operationId(note.id);
  fail(walletId(note.ownerKey)===note.owner,'BAD_OWNER','Note owner does not match key.');
  fail(Number.isSafeInteger(note.createdAt)&&Number.isSafeInteger(note.expiresAt)&&note.expiresAt-note.createdAt===LIMITS.noteLife,'BAD_NOTE','Invalid note lifetime.');
  const ancestors=[];let parentHash=null,parentAmount=note.amount,parentTo=null;
  for(let i=0;i<hop;i++){
    fail(typeof links[i]==='string'&&links[i].length<=LIMITS.chainBytes,'BAD_CHAIN','Broken chain link.');
    const ld=unpack(links[i]).data;
    fail((ld.hop===undefined?0:ld.hop)===i,'BAD_CHAIN','Broken chain continuity.');
    const lfrom=typeof ld.fromKey==='string'?ld.fromKey:(i===0?note.ownerKey:null);
    fail(typeof lfrom==='string','BAD_CHAIN','Chain link is missing its sender key.');
    publicKey(lfrom);operationId(ld.requestId);
    if(i===0)fail(walletId(lfrom)===note.owner,'WRONG_SENDER','Only the note owner can start a transfer.');
    else{fail(walletId(lfrom)===parentTo,'BAD_CHAIN','Chain sender does not follow the previous hop.');fail(ld.prev===parentHash,'BAD_CHAIN','Chain link does not reference the previous transfer.');}
    open('payment',links[i],lfrom);
    const lto=text(ld.to,'Recipient',128);
    fail(lto!==walletId(lfrom),'SELF_PAYMENT','Choose another wallet.');
    const lamt=amount(ld.amountMinor,parentAmount);
    fail(Number.isSafeInteger(ld.createdAt)&&ld.createdAt>=note.createdAt-LIMITS.clockSkew&&ld.createdAt<=note.expiresAt,'BAD_TIME','Invalid chain time.');
    const lhash=hash(canonical(ld));
    ancestors.push({sender:walletId(lfrom),to:lto,amount:lamt,hash:lhash,createdAt:ld.createdAt});
    parentHash=lhash;parentAmount=lamt;parentTo=lto;
  }
  const senderKey=typeof outer.fromKey==='string'?outer.fromKey:note.ownerKey;
  if(hop===0)fail(walletId(senderKey)===note.owner,'WRONG_SENDER','Only the note owner can start a transfer.');
  else fail(walletId(senderKey)===parentTo,'BAD_CHAIN','Transfer sender does not follow the chain.');
  const payment=open('payment',raw,senderKey);operationId(payment.requestId);
  fail(payment.to===text(payment.to,'Recipient',128),'BAD_INPUT','Recipient is invalid.');
  if(hop===0)fail(payment.to!==note.owner,'SELF_PAYMENT','Choose another wallet.');
  else fail(payment.to!==walletId(senderKey),'SELF_PAYMENT','Choose another wallet.');
  fail(payment.to===recipient,'WRONG_RECIPIENT','This payment is not addressed to your wallet.',403);
  if(hop>0)fail(payment.prev===parentHash,'BAD_CHAIN','Transfer does not reference the previous transfer.');
  const cap=hop===0?note.amount:parentAmount;
  const amountMinor=payment.amountMinor===undefined?cap:amount(payment.amountMinor,cap);
  const earliest=hop===0?note.createdAt:ancestors[hop-1].createdAt;
  fail(Number.isSafeInteger(payment.createdAt)&&payment.createdAt>=earliest-LIMITS.clockSkew&&payment.createdAt<=note.expiresAt&&payment.createdAt<=now+LIMITS.clockSkew,'BAD_TIME','Invalid payment time.');
  fail(now<note.expiresAt+(settlement?LIMITS.redeemGrace:0),'EXPIRED_NOTE',settlement?'Redemption window has closed.':'This offline note has expired.');
  return {note,noteCert,payment,paymentHash:hash(canonical(payment)),amountMinor,hop,ancestors,senderId:walletId(senderKey)};
}
