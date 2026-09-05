import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, readFileSync, writeFileSync, existsSync, chmodSync } from 'node:fs';
import { join } from 'node:path';
import { createPrivateKey, createPublicKey, randomUUID } from 'node:crypto';
import { LIMITS, Fault, fail, hash, unb64, publicKey, exportKey, walletId, newKey, pack, unpack, open, text, amount, fresh, operationId, canonical, readPayment } from './protocol.mjs';
export class Ledger {
  constructor({dir, clock=Date.now, mode='test'}={}) {
    fail(mode==='test','REAL_MONEY_DISABLED','This server only supports test credit. Real-money mode is not implemented.',500);
    this.clock=clock; mkdirSync(dir,{recursive:true,mode:0o700});this.dir=dir;
    const keyPath=join(dir,'issuer-private.pem');
    if(!existsSync(keyPath))writeFileSync(keyPath,newKey().privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600,flag:'wx'});
    chmodSync(keyPath,0o600);this.key=createPrivateKey(readFileSync(keyPath));this.pub=createPublicKey(this.key);this.publicKey=exportKey(this.pub);this.issuer=hash(unb64(this.publicKey));
    this.db=new DatabaseSync(join(dir,'ledger.sqlite'));
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, pub TEXT UNIQUE, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0 CHECK(typeof(balance)='integer' AND (id='TEST_ISSUER' OR balance>=0)), created INTEGER NOT NULL, topped INTEGER);
      CREATE TABLE IF NOT EXISTS vouchers(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(id), amount INTEGER NOT NULL CHECK(amount>0), certificate TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('reserved','redeemed','reclaimed')), recipient TEXT REFERENCES accounts(id), transfer_hash TEXT, tx_id TEXT);
      CREATE TABLE IF NOT EXISTS journal(seq INTEGER PRIMARY KEY AUTOINCREMENT, tx_id TEXT NOT NULL, account TEXT NOT NULL REFERENCES accounts(id), delta INTEGER NOT NULL CHECK(typeof(delta)='integer' AND delta!=0), kind TEXT NOT NULL, peer TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS journal_account ON journal(account,seq DESC);
      CREATE INDEX IF NOT EXISTS voucher_owner ON vouchers(owner,status);
      CREATE TABLE IF NOT EXISTS operations(wallet TEXT NOT NULL REFERENCES accounts(id), op_id TEXT NOT NULL, digest TEXT NOT NULL, response TEXT NOT NULL, PRIMARY KEY(wallet,op_id));
      CREATE TRIGGER IF NOT EXISTS journal_immutable_update BEFORE UPDATE ON journal BEGIN SELECT RAISE(ABORT,'Journal is immutable'); END;
      CREATE TRIGGER IF NOT EXISTS journal_immutable_delete BEFORE DELETE ON journal BEGIN SELECT RAISE(ABORT,'Journal is immutable'); END;
      PRAGMA user_version=1;`);
    const ins=this.db.prepare('INSERT OR IGNORE INTO accounts(id,name,balance,created) VALUES(?,?,0,?)');
    ins.run('TEST_ISSUER','Test credit issuer',this.clock());ins.run('OFFLINE_ESCROW','Reserved offline credit',this.clock());
    chmodSync(join(dir,'ledger.sqlite'),0o600);
  }
  config(){return {protocol:1,mode:'test',currency:'tNPR',initialCreditMinor:LIMITS.grant,offlineLimitMinor:LIMITS.offline,noteLifetimeMs:LIMITS.noteLife,redemptionGraceMs:LIMITS.redeemGrace,issuerPublicKey:this.publicKey,issuerFingerprint:this.issuer};}
  atomic(work){this.db.exec('BEGIN IMMEDIATE');try{const result=work();this.db.exec('COMMIT');return result;}catch(e){this.db.exec('ROLLBACK');throw e;}}
  account(id){const a=this.db.prepare('SELECT * FROM accounts WHERE id=? AND pub IS NOT NULL').get(id);fail(a,'WALLET_NOT_FOUND','Wallet not found. Create it on this server first.',404);return a;}
  move(from,to,value,kind,note='',tx=randomUUID()) {
    amount(value);const now=this.clock();
    const debit=this.db.prepare("UPDATE accounts SET balance=balance-? WHERE id=? AND (id='TEST_ISSUER' OR balance>=?)").run(value,from,value);
    fail(debit.changes===1,'INSUFFICIENT_FUNDS','Not enough available balance.',409);
    fail(this.db.prepare('UPDATE accounts SET balance=balance+? WHERE id=?').run(value,to).changes===1,'WALLET_NOT_FOUND','Recipient not found.',404);
    const j=this.db.prepare('INSERT INTO journal(tx_id,account,delta,kind,peer,note,created) VALUES(?,?,?,?,?,?,?)');
    j.run(tx,from,-value,kind,to,note,now);j.run(tx,to,value,kind,from,note,now);return tx;
  }
  register(raw){
    const p=unpack(raw).data; publicKey(p.publicKey);const r=open('register',raw,p.publicKey);fresh(r.ts,this.clock());operationId(r.opId);const name=text(r.name,'Name',48);const id=walletId(r.publicKey);
    return this.atomic(()=>{if(!this.db.prepare('SELECT id FROM accounts WHERE id=?').get(id)){
      this.db.prepare('INSERT INTO accounts(id,pub,name,balance,created) VALUES(?,?,?,0,?)').run(id,r.publicKey,name,this.clock());
      this.move('TEST_ISSUER',id,LIMITS.grant,'welcome','Welcome test credit');
    }return this.state(id);});
  }
  state(id){const a=this.account(id);return {walletId:a.id,name:a.name,balanceMinor:a.balance,mode:'test',currency:'tNPR',serverTime:this.clock(),nextTopupAt:a.topped===null?0:a.topped+86400000,
    vouchers:this.db.prepare('SELECT id,amount,certificate,expires,status,recipient,tx_id AS txId FROM vouchers WHERE owner=? ORDER BY created DESC LIMIT 500').all(id),
    activity:this.db.prepare(`SELECT j.tx_id AS id,j.delta AS amountMinor,j.kind,j.peer,COALESCE(a.name,j.peer) AS peerName,j.note,j.created FROM journal j LEFT JOIN accounts a ON a.id=j.peer WHERE j.account=? ORDER BY j.seq DESC LIMIT 100`).all(id)};}
  action(raw){
    const p=unpack(raw).data; const actor=this.account(p.walletId);const r=open('request',raw,actor.pub);fresh(r.ts,this.clock());operationId(r.opId);
    fail(r.data&&typeof r.data==='object'&&!Array.isArray(r.data),'BAD_INPUT','Data must be an object.');
    fail(['state','pay','topup','reserve','redeem','reclaim'].includes(r.op),'UNKNOWN_OPERATION','Unknown wallet action.');
    if(r.op==='state')return this.state(actor.id);
    const digest=hash(canonical({op:r.op,data:r.data}));
    return this.atomic(()=>{
      const old=this.db.prepare('SELECT * FROM operations WHERE wallet=? AND op_id=?').get(actor.id,r.opId);
      if(old){fail(old.digest===digest,'IDEMPOTENCY_CONFLICT','This operation ID was already used for a different request.',409);return JSON.parse(old.response);}
      const d=r.data;let result;
      switch(r.op){
        case 'pay':{
          amount(d.amountMinor);this.account(d.to);fail(d.to!==actor.id,'SELF_PAYMENT','Choose another wallet.');
          const note=d.note===undefined||d.note===''?'':text(d.note,'Payment note',120);
          const tx=this.move(actor.id,d.to,d.amountMinor,'payment',note);
          result={status:'settled',txId:tx,amountMinor:d.amountMinor,to:d.to};break;
        }
        case 'topup':{
          const a=this.account(actor.id);fail(a.topped===null||this.clock()-a.topped>=86400000,'TOPUP_LIMIT','Test credit can be added once every 24 hours.',429);
          const tx=this.move('TEST_ISSUER',actor.id,LIMITS.grant,'test_topup','Additional test credit');
          this.db.prepare('UPDATE accounts SET topped=? WHERE id=?').run(this.clock(),actor.id);result={status:'settled',txId:tx,amountMinor:LIMITS.grant};break;
        }
        case 'reserve':{
          const n=amount(d.amountMinor,LIMITS.offline);const outstanding=this.db.prepare("SELECT COALESCE(SUM(amount),0) AS total FROM vouchers WHERE owner=? AND status='reserved'").get(actor.id).total;
          fail(outstanding+n<=LIMITS.offline,'OFFLINE_LIMIT','Outstanding offline notes are limited to Rs 500 test credit.',409);
          const now=this.clock(),id=randomUUID();const note={v:1,issuer:this.issuer,id,owner:actor.id,ownerKey:actor.pub,amount:n,createdAt:now,expiresAt:now+LIMITS.noteLife};const cert=pack('voucher',note,this.key);
          this.move(actor.id,'OFFLINE_ESCROW',n,'offline_reserve','Reserved offline note');
          this.db.prepare("INSERT INTO vouchers(id,owner,amount,certificate,created,expires,status) VALUES(?,?,?,?,?,?,'reserved')").run(id,actor.id,n,cert,now,note.expiresAt);
          result={status:'reserved',id,certificate:cert,amountMinor:n,expiresAt:note.expiresAt};break;
        }
        case 'redeem':{
          const {note,payment,paymentHash}=readPayment(d.payment,this.pub,actor.id,this.clock(),true);
          const row=this.db.prepare('SELECT * FROM vouchers WHERE id=?').get(note.id);
          fail(row&&row.certificate===payment.voucher,'UNKNOWN_NOTE','Note is not present in this issuer ledger.',409);
          if(row.status==='redeemed'){
            fail(row.recipient===actor.id&&row.transfer_hash===paymentHash,'DOUBLE_SPEND','This note was already redeemed for another payment.',409);
            result={status:'settled',txId:row.tx_id,amountMinor:row.amount,noteId:row.id};break;
          }
          fail(row.status==='reserved','NOTE_UNAVAILABLE','This note is no longer available.',409);
          const tx=this.move('OFFLINE_ESCROW',actor.id,row.amount,'offline_payment','Offline payment settled');
          this.db.prepare("UPDATE vouchers SET status='redeemed',recipient=?,transfer_hash=?,tx_id=? WHERE id=? AND status='reserved'").run(actor.id,paymentHash,tx,row.id);
          result={status:'settled',txId:tx,amountMinor:row.amount,noteId:row.id};break;
        }
        case 'reclaim':{
          operationId(d.noteId);const row=this.db.prepare('SELECT * FROM vouchers WHERE id=? AND owner=?').get(d.noteId,actor.id);
          fail(row&&row.status==='reserved','NOTE_UNAVAILABLE','This note is no longer reserved.',409);
          fail(this.clock()>=row.expires+LIMITS.redeemGrace,'RECLAIM_TOO_EARLY','Refund is available seven days after note expiry, not before.',409);
          const tx=this.move('OFFLINE_ESCROW',actor.id,row.amount,'offline_refund','Expired offline note refund');this.db.prepare("UPDATE vouchers SET status='reclaimed',tx_id=? WHERE id=?").run(tx,row.id);
          result={status:'refunded',txId:tx,amountMinor:row.amount};break;
        }
      }
      this.db.prepare('INSERT INTO operations(wallet,op_id,digest,response) VALUES(?,?,?,?)').run(actor.id,r.opId,digest,JSON.stringify(result));return result;
    });
  }
  audit(){
    const mismatches=this.db.prepare('SELECT a.id,a.balance,COALESCE(SUM(j.delta),0) AS journalBalance FROM accounts a LEFT JOIN journal j ON j.account=a.id GROUP BY a.id HAVING a.balance!=COALESCE(SUM(j.delta),0)').all();
    const unbalanced=this.db.prepare('SELECT tx_id,SUM(delta) AS total,COUNT(*) AS entryCount FROM journal GROUP BY tx_id HAVING SUM(delta)!=0 OR COUNT(*)!=2').all();
    const supply=this.db.prepare('SELECT SUM(balance) AS total FROM accounts').get().total;
    const escrow=this.db.prepare("SELECT balance FROM accounts WHERE id='OFFLINE_ESCROW'").get().balance;
    const reserved=this.db.prepare("SELECT COALESCE(SUM(amount),0) AS total FROM vouchers WHERE status='reserved'").get().total;
    return {ok:mismatches.length===0&&unbalanced.length===0&&supply===0&&escrow===reserved,mismatches,unbalanced,supply,escrow,reserved};
  }
  close(){this.db.close();}
}
