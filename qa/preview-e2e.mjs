import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {pathToFileURL,fileURLToPath} from 'node:url';
import path from 'node:path';
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||(existsSync('/usr/local/bin/chromium')?'/usr/local/bin/chromium':undefined),headless:true,args:['--no-sandbox']});
const page=await browser.newPage({viewport:{width:1440,height:1000},colorScheme:'light',reducedMotion:'no-preference'}),errors=[],external=[];
page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))external.push(r.url())});
const file=path.resolve(process.env.PREVIEW_FILE||fileURLToPath(new URL('../Paila-interactive-design.html',import.meta.url))),out=process.env.PREVIEW_QA_OUTPUT||fileURLToPath(new URL('./preview-results.json',import.meta.url));
try{
 await page.goto(pathToFileURL(file).href);await page.locator('#app h1').waitFor();const names=await page.evaluate(()=>PailaPreview.scenes);assert.equal(names.length,10);let layouts=0;
 for(const width of [320,390,1440]){await page.setViewportSize({width,height:1000});for(const name of names){await page.locator('#screen-choice').selectOption(name);assert((await page.locator('#app h1').innerText()).length>0);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`Document overflow: ${name}, ${width}`);assert(await page.evaluate(()=>{const p=document.querySelector('.page');return p.scrollWidth<=p.clientWidth+1}),`Content overflow: ${name}, ${width}`);layouts++;}}
 await page.setViewportSize({width:390,height:1000});await page.locator('#screen-choice').selectOption('send');await page.getByLabel('Amount in test rupees').fill('42.25');await page.getByRole('button',{name:'Review payment',exact:true}).click();await page.getByRole('dialog').waitFor();assert.match(await page.locator('.review-amount').innerText(),/42\.25/);await page.getByRole('dialog').getByRole('button',{name:'Cancel',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});
 await page.getByRole('button',{name:'Review payment',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});assert.match(await page.locator('#preview-toast').innerText(),/No payment was sent/);
 await page.locator('#screen-choice').selectOption('settings');await page.getByRole('button',{name:'About phone-to-phone methods'}).click();await page.getByRole('dialog').waitFor();await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
 for(const mode of ['dark','light','auto']){await page.locator('#theme-choice').selectOption(mode);assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),mode);}
 await page.getByRole('button',{name:'Replay motion'}).click();assert(await page.evaluate(()=>document.getAnimations().some(a=>a.playState==='running')),'No preview motion observed');await page.getByRole('checkbox',{name:'Reduce motion'}).check();await page.getByRole('button',{name:'Replay motion'}).click();assert.equal(await page.evaluate(()=>document.getAnimations().filter(a=>a.playState==='running').length),0);
 assert.deepEqual(errors,[]);assert.deepEqual(external,[]);const result={status:'passed',sceneCount:names.length,layoutChecks:layouts,widths:[320,390,1440],reviewCancel:true,reviewConfirmSendsNothing:true,nearbyDialog:true,themeControls:true,normalAndReducedMotion:true,uncaughtErrors:errors,externalRequests:external};await writeFile(out,JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
}finally{await page.close();await browser.close();}
