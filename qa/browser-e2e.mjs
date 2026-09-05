import { chromium } from 'playwright';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createApp } from '../server/src/server.mjs';
const stateDir=mkdtempSync(path.join(tmpdir(),'paila-browser-'));
const testServer=createApp({dir:stateDir,rateLimit:false,enableWebWallet:true,allowedOrigin:'http://127.0.0.1:8787'});
await new Promise(r=>testServer.server.listen(8787,'127.0.0.1',r));
const base='http://127.0.0.1:8787';
const output=path.resolve(process.env.QA_OUTPUT||'./qa-output');await mkdir(output,{recursive:true});
const css=await readFile(new URL('../web/app.css',import.meta.url),'utf8');
const logo=Buffer.from(await readFile(new URL('../web/logo.svg',import.meta.url))).toString('base64');
const art=Buffer.from(await readFile(new URL('../web/wallet-art.webp',import.meta.url))).toString('base64');
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||(existsSync('/usr/local/bin/chromium')?'/usr/local/bin/chromium':undefined),headless:true,args:['--no-sandbox']});
const contexts=[];const checks=[];const jsErrors=[];
async function check(name,fn){await fn();checks.push({name,status:'passed'});console.log('PASS',name);}
async function profile(){const c=await browser.newContext({viewport:{width:390,height:844},colorScheme:'light',reducedMotion:'reduce'});contexts.push(c);const p=await c.newPage();p.on('pageerror',e=>jsErrors.push(e.message));await p.goto(base+'/lab');await p.getByRole('button',{name:'Create my test wallet'}).waitFor();return {c,p};}
async function idle(p){await p.locator('p[role=status]').waitFor({state:'detached',timeout:20000});}
async function snap(p,name){await idle(p);await p.waitForTimeout(180);const html=await p.evaluate(({css,logo,art})=>{const root=document.documentElement.cloneNode(true);root.querySelectorAll('script,link').forEach(n=>n.remove());const style=document.createElement('style');style.textContent=css;root.querySelector('head').append(style);root.querySelectorAll('img').forEach(img=>img.setAttribute('src',img.getAttribute('src').includes('wallet-art')?'data:image/webp;base64,'+art:'data:image/svg+xml;base64,'+logo));root.querySelectorAll('textarea').forEach((n,i)=>{n.textContent=document.querySelectorAll('textarea')[i].value});root.querySelectorAll('input').forEach((n,i)=>n.setAttribute('value',document.querySelectorAll('input')[i].value));if(root.dataset.theme==='auto')root.dataset.theme=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';const d=root.querySelector('dialog[open]');if(d){const script=document.createElement('script');script.textContent="const d=document.querySelector('dialog[open]');d.removeAttribute('open');d.showModal();";root.querySelector('body').append(script);}return '<!doctype html>'+root.outerHTML;},{css,logo,art});await writeFile(path.join(output,name+'.html'),html);}
async function nav(p,where){let target=p.getByRole('button',{name:where,exact:true}).last();if(await target.count()===0){const back=p.getByRole('button',{name:'Back to wallet',exact:true});if(await back.count())await back.click();else await p.getByRole('button',{name:'Wallet',exact:true}).last().click();await idle(p);}await p.getByRole('button',{name:where,exact:true}).last().click();await idle(p);}
async function confirm(p){await p.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await p.getByRole('dialog').waitFor({state:'hidden'});await idle(p);}
try{
 const a=await profile(),b=await profile();
 await snap(a.p,'01-setup');
  await check('two separate browser wallets receive Rs 5,000 each with half auto-reserved',async()=>{
   await a.p.getByLabel('Your name',{exact:true}).fill('Asha');await a.p.getByRole('button',{name:'Create my test wallet'}).click();await idle(a.p);
   await b.p.getByLabel('Your name',{exact:true}).fill('Bikash');await b.p.getByRole('button',{name:'Create my test wallet'}).click();await idle(b.p);
    assert.equal((await a.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,250000);assert.equal((await b.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,250000);
 });
 await a.p.getByRole('button',{name:'Dismiss message'}).click();await snap(a.p,'02-wallet');
 await check('receive screen supplies a signed, verifiable, scannable QR',async()=>{
   await nav(b.p,'Receive');await b.p.locator('#receive-code').waitFor({state:'attached'});await idle(b.p);
   const raw=await b.p.locator('#receive-code').inputValue();assert(raw.startsWith('p1.'));assert(await b.p.evaluate(async raw=>(await PailaTest.protocol.readReceive(raw)).name==='Bikash',raw));assert.equal(await b.p.getByRole('img',{name:'Scannable Paila signed code'}).count(),1);
 });
 const receiver=await b.p.locator('#receive-code').inputValue();await snap(b.p,'03-receive');
 await check('online payment reviews recipient then updates both real test balances',async()=>{
   await nav(a.p,'Send');await a.p.getByLabel("Receiver's code",{exact:true}).fill(receiver);await a.p.getByLabel('Amount in test rupees').fill('25.75');await a.p.getByLabel('Note (optional)').fill('Tea');await a.p.getByRole('button',{name:'Review payment'}).click();await a.p.getByRole('dialog').waitFor();assert.match(await a.p.getByRole('dialog').innerText(),/Bikash/);await snap(a.p,'13-review');await confirm(a.p);
    assert.equal((await a.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,247425);
    await nav(b.p,'Settings');await b.p.getByRole('button',{name:'Sync now',exact:true}).click();await idle(b.p);assert.equal((await b.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,252575);
 });
 await check('reserve note removes exactly Rs 100 from available balance',async()=>{
   await nav(a.p,'Wallet');await a.p.getByRole('button',{name:'Manage offline notes'}).click();await a.p.getByLabel('Note value in rupees').fill('100');await a.p.getByRole('button',{name:'Reserve this amount'}).click();await confirm(a.p);assert.equal((await a.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,237425);assert.equal((await a.p.evaluate(()=>PailaTest.snapshot())).wallet.vouchers[0].amount,10000);
 });
 await a.p.getByRole('button',{name:'Dismiss message'}).click();await snap(a.p,'04-offline');
 await nav(a.p,'Wallet');await nav(a.p,'Send');await a.p.getByRole('button',{name:'Offline QR',exact:true}).click();await snap(a.p,'05-send');
 await check('both browsers can exchange a signed offline payment without network',async()=>{
   await a.c.setOffline(true);await b.c.setOffline(true);
   await a.p.getByLabel("Receiver's code",{exact:true}).fill(receiver);await a.p.getByLabel('Amount in test rupees').fill('100');await a.p.getByRole('button',{name:'Review payment'}).click();await confirm(a.p);
   const packet=await a.p.locator('#payment-code').inputValue();assert(packet.startsWith('p1.'));
   await nav(b.p,'Receive');await b.p.getByLabel('Payment code',{exact:true}).fill(packet);await b.p.getByRole('button',{name:'Save received payment'}).click();await idle(b.p);
    const state=await b.p.evaluate(()=>PailaTest.snapshot());assert.equal(state.incoming[0].status,'pending');assert.equal(state.wallet.balanceMinor,252575);
 });
 if(await a.p.getByRole('button',{name:'Dismiss message'}).count())await a.p.getByRole('button',{name:'Dismiss message'}).click();await snap(a.p,'06-payment');
 await nav(b.p,'Activity');await snap(b.p,'07-pending');
 await check('reconnect settles receipt once, retries do not mint money',async()=>{
   await b.c.setOffline(false);await idle(b.p);await nav(b.p,'Settings');await b.p.getByRole('button',{name:'Sync now',exact:true}).click();await idle(b.p);
    let state=await b.p.evaluate(()=>PailaTest.snapshot());assert.equal(state.incoming[0].status,'settled');assert.equal(state.wallet.balanceMinor,262575);
    await b.p.getByRole('button',{name:'Sync now',exact:true}).click();await idle(b.p);assert.equal((await b.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,262575);
 });
 await a.c.setOffline(false);await idle(a.p);await nav(a.p,'Settings');await a.p.getByRole('button',{name:'Sync now',exact:true}).click();await idle(a.p);assert.equal((await a.p.evaluate(()=>PailaTest.snapshot())).outgoing[0].status,'settled');
  await check('top-up adds actual server credit and daily limit disables repeats',async()=>{
    await a.p.getByRole('button',{name:'Add Rs 5,000',exact:true}).click();await confirm(a.p);assert.equal((await a.p.evaluate(()=>PailaTest.snapshot())).wallet.balanceMinor,737425);assert(await a.p.getByRole('button',{name:'Add Rs 5,000',exact:true}).isDisabled());
 });
 await a.p.getByRole('button',{name:'Dismiss message'}).click();await snap(a.p,'08-settings');
 await check('reload preserves device keys, wallet and spent-note lock',async()=>{
   const before=await a.p.evaluate(()=>PailaTest.snapshot());await a.p.reload();await idle(a.p);await a.p.getByRole('button',{name:'Manage offline notes'}).waitFor();await idle(a.p);const after=await a.p.evaluate(()=>PailaTest.snapshot());assert.equal(after.wallet.walletId,before.wallet.walletId);assert.equal(after.wallet.balanceMinor,737425);assert.equal(after.outgoing.length,1);
 });
 await check('tampered code is rejected in the browser',async()=>{
   const changed=receiver.split('.');const obj=JSON.parse(Buffer.from(changed[1],'base64url'));obj.name='Attacker';changed[1]=Buffer.from(JSON.stringify(obj)).toString('base64url');
   await nav(a.p,'Send');await a.p.getByLabel("Receiver's code",{exact:true}).fill(changed.join('.'));await a.p.getByLabel('Amount in test rupees').fill('1');await a.p.getByRole('button',{name:'Review payment'}).click();await idle(a.p);assert.match(await a.p.getByRole('alert').innerText(),/Signature verification failed/);
 });
 await snap(a.p,'09-error');
 await check('all browser routes fit narrow, mobile and desktop widths',async()=>{
   for(const width of [320,390,768,1440]){await a.p.setViewportSize({width,height:900});for(const route of ['Wallet','Activity','Settings']){await nav(a.p,route);const sizes=await a.p.evaluate(()=>({w:innerWidth,body:document.documentElement.scrollWidth}));assert(sizes.body<=sizes.w,`${route} overflows at ${width}: ${sizes.body}`);}}
 });
 await nav(a.p,'Wallet');await snap(a.p,'10-desktop');await a.p.setViewportSize({width:390,height:844});await a.p.emulateMedia({colorScheme:'dark'});await snap(a.p,'11-dark');
 await check('no uncaught browser JavaScript exceptions',async()=>assert.deepEqual(jsErrors,[]));

 await a.p.setViewportSize({width:390,height:844});await a.p.emulateMedia({colorScheme:'light',reducedMotion:'reduce'});await nav(a.p,'Settings');
 await check('appearance buttons change theme and persist after reload',async()=>{
   await a.p.getByRole('button',{name:'Dark',exact:true}).click();assert.equal(await a.p.evaluate(()=>document.documentElement.dataset.theme),'dark');
   await a.p.reload();await a.p.getByRole('button',{name:'Manage offline notes'}).waitFor();await idle(a.p);assert.equal(await a.p.evaluate(()=>document.documentElement.dataset.theme),'dark');
   await nav(a.p,'Settings');await a.p.getByRole('button',{name:'Light',exact:true}).click();assert.equal(await a.p.evaluate(()=>document.documentElement.dataset.theme),'light');
   await a.p.getByRole('button',{name:'System',exact:true}).click();assert.equal(await a.p.evaluate(()=>document.documentElement.dataset.theme),'auto');
 });
 await check('nearby help is actionable and does not simulate native radios',async()=>{
   await a.p.getByRole('button',{name:'About phone-to-phone methods'}).click();await a.p.getByRole('dialog').waitFor();
   assert.match(await a.p.getByRole('dialog').innerText(),/This browser can't perform/);assert.match(await a.p.getByRole('dialog').innerText(),/Bluetooth/);assert.match(await a.p.getByRole('dialog').innerText(),/Wi-Fi Direct/);assert.match(await a.p.getByRole('dialog').innerText(),/NFC/);
   await snap(a.p,'14-nearby');await a.p.getByRole('dialog').getByRole('button',{name:'Got it'}).click();assert.equal(await a.p.getByRole('dialog').count(),0);
 });
 await nav(a.p,'Send');await a.p.getByLabel("Receiver's code",{exact:true}).fill(receiver);await a.p.getByLabel('Amount in test rupees').fill('5.50');await a.p.getByLabel('Note (optional)').fill('Cancel test');
 await check('cancel and Escape never submit money or create queued operations',async()=>{
   const before=await a.p.evaluate(()=>PailaTest.snapshot());
   for(const cancel of ['button','escape']){await a.p.getByRole('button',{name:'Review payment'}).click();await a.p.getByRole('dialog').waitFor();if(cancel==='button')await a.p.getByRole('button',{name:'Cancel',exact:true}).click();else await a.p.keyboard.press('Escape');await a.p.getByRole('dialog').waitFor({state:'hidden'});await idle(a.p);}
   const after=await a.p.evaluate(()=>PailaTest.snapshot());assert.equal(after.wallet.balanceMinor,before.wallet.balanceMinor);assert.deepEqual(after.queue,before.queue);assert.equal(await a.p.getByLabel('Amount in test rupees').inputValue(),'5.50');assert.equal(await a.p.getByLabel('Note (optional)').inputValue(),'Cancel test');
 });
 await check('connectivity and viewport changes preserve draft fields',async()=>{
   await a.c.setOffline(true);assert.equal(await a.p.getByLabel('Amount in test rupees').inputValue(),'5.50');await a.p.setViewportSize({width:320,height:700});await a.p.waitForTimeout(200);assert.equal(await a.p.getByLabel('Note (optional)').inputValue(),'Cancel test');await a.c.setOffline(false);await idle(a.p);assert.equal(await a.p.getByLabel("Receiver's code",{exact:true}).inputValue(),receiver);
 });
 await a.p.setViewportSize({width:390,height:844});await a.p.waitForTimeout(200);
 await check('copy feedback copies the actual signed code and disclosure opens',async()=>{
   await nav(a.p,'Receive');await a.c.grantPermissions(['clipboard-read','clipboard-write'],{origin:base});
   await a.p.getByRole('button',{name:'Copy receive code',exact:true}).click();await a.p.getByRole('button',{name:'Copied',exact:true}).waitFor();
   await snap(a.p,'15-copy-feedback');const text=await a.p.evaluate(()=>navigator.clipboard.readText());assert.equal(text,await a.p.locator('#receive-code').inputValue());
   await a.p.getByText('View signed code',{exact:true}).click();assert(await a.p.locator('#receive-code').isVisible());await a.p.getByText('View signed code',{exact:true}).click();assert(!await a.p.locator('#receive-code').isVisible());
 });
 await check('keyboard navigation focuses the page heading and exposes focus styling',async()=>{
   await nav(a.p,'Activity');await snap(a.p,'16-activity-settled');assert.equal(await a.p.evaluate(()=>document.activeElement.tagName),'H1');await a.p.keyboard.press('Tab');
   const focus=await a.p.evaluate(()=>({tag:document.activeElement.tagName,width:getComputedStyle(document.activeElement).outlineWidth}));assert(['BUTTON','A','SUMMARY'].includes(focus.tag));assert(parseFloat(focus.width)>=3);
 });
 await check('normal-motion screen transitions run and reduced motion stops them',async()=>{
   await a.p.emulateMedia({reducedMotion:'no-preference'});await nav(a.p,'Settings');
   const active=await a.p.evaluate(()=>document.getAnimations().filter(a=>a.effect?.target?.classList.contains('screen-content')).length);assert(active>0,'No screen animation observed');
   await a.p.waitForTimeout(350);await a.p.emulateMedia({reducedMotion:'reduce'});await nav(a.p,'Activity');assert.equal(await a.p.evaluate(()=>document.getAnimations().filter(a=>a.playState==='running').length),0);
 });
 await check('note presets fill the form without reserving money prematurely',async()=>{
   await nav(a.p,'Wallet');await a.p.getByRole('button',{name:'Manage offline notes'}).click();const before=await a.p.evaluate(()=>PailaTest.snapshot());
   await a.p.getByRole('button',{name:'Rs 250',exact:true}).click();assert.equal(await a.p.getByLabel('Note value in rupees').inputValue(),'250');const after=await a.p.evaluate(()=>PailaTest.snapshot());assert.equal(after.wallet.balanceMinor,before.wallet.balanceMinor);assert.equal(after.wallet.vouchers.length,before.wallet.vouchers.length);
 });
 const audit=[];
 await check('every route has readable type, 44px targets and no horizontal overflow',async()=>{
  for(const width of [320,390,768,1440]){
   await a.p.setViewportSize({width,height:900});await a.p.waitForTimeout(150);
   for(const where of ['Wallet','Send','Receive','Offline','Activity','Settings']){
    if(where==='Offline'){await nav(a.p,'Wallet');await a.p.getByRole('button',{name:'Manage offline notes'}).click();await idle(a.p);}else await nav(a.p,where);
    const result=await a.p.evaluate(()=>{
     const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width&&r.height&&s.visibility!=='hidden'&&s.display!=='none'&&(!e.closest('details')||e.closest('details').open||e.tagName==='SUMMARY');};
     const targets=[...document.querySelectorAll('button,a,summary,input,textarea,select')].filter(visible).map(e=>({name:(e.getAttribute('aria-label')||e.textContent||e.name).trim().slice(0,60),w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height})).filter(e=>e.w<43.9||e.h<43.9);
     const small=[...document.querySelectorAll('p,span,label,h1,h2,h3,button,a,input,textarea,summary,time,strong')].filter(e=>visible(e)&&e.textContent.trim()&&!e.classList.contains('sr-only')&&parseFloat(getComputedStyle(e).fontSize)<13.99).map(e=>e.textContent.slice(0,50));
     const page=document.querySelector('.page');return{targets,small,bodyOverflow:document.documentElement.scrollWidth>innerWidth,pageOverflow:page.scrollWidth>page.clientWidth+1};
    });
    audit.push({width,route:where,...result});assert.deepEqual(result.targets,[],`Small targets ${width} ${where}`);assert.deepEqual(result.small,[],`Small text ${width} ${where}`);assert.equal(result.bodyOverflow,false,`${where} body overflow ${width}`);assert.equal(result.pageOverflow,false,`${where} page overflow ${width}`);
   }
  }
 });
 await a.p.setViewportSize({width:390,height:844});await nav(a.p,'Wallet');await snap(a.p,'17-wallet-final');await nav(a.p,'Receive');await snap(a.p,'18-receive-own');
 await writeFile(path.join(output,'interface-audit.json'),JSON.stringify(audit,null,2));
 await check('final redesigned browser raises no uncaught exceptions',async()=>assert.deepEqual(jsErrors,[]));
 await writeFile(path.join(output,'browser-results.json'),JSON.stringify({status:'passed',checks,jsErrors,nativeRadioTests:'NOT RUN',androidBuild:'NOT RUN'},null,2));
 console.log('BROWSER E2E:',checks.length,'checks passed; snapshots in',output);
}catch(e){console.error(e.stack);await writeFile(path.join(output,'browser-failure.json'),JSON.stringify({checks,error:e.stack,jsErrors},null,2));process.exitCode=1;}
finally{for(const c of contexts)await c.close();await browser.close();testServer.server.closeAllConnections();await new Promise(r=>testServer.server.close(r));testServer.ledger.close();rmSync(stateDir,{recursive:true,force:true});}

process.exit(process.exitCode||0);
