import http from 'node:http';
import { readFileSync, existsSync, statSync, createReadStream } from 'node:fs';
import { resolve, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { Ledger } from './ledger.mjs';
import { Fault, fail, LIMITS } from './protocol.mjs';
const ROOT=resolve(fileURLToPath(new URL('..',import.meta.url)));
const WEB=resolve(ROOT,'..','web');
const TYPES={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.webp':'image/webp','.png':'image/png','.json':'application/json','.apk':'application/vnd.android.package-archive'};
export function createApp({dir=process.env.DATA_DIR||join(ROOT,'data'),clock=Date.now,rateLimit=true,mode=process.env.MONEY_MODE||'test',allowedOrigin=process.env.PUBLIC_ORIGIN||'',enableWebWallet=process.env.ENABLE_WEB_WALLET==='true'}={}){
 const ledger=new Ledger({dir,clock,mode});const buckets=new Map();
 function reply(res,status,data){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data));}
 function limited(req){if(!rateLimit)return false;const ip=req.socket.remoteAddress;const now=clock();let b=buckets.get(ip);if(!b||now-b.at>=60000){b={at:now,n:0};buckets.set(ip,b);}b.n++;if(buckets.size>10000)for(const[k,v]of buckets)if(now-v.at>=60000)buckets.delete(k);return b.n>180;}
 async function body(req){
   fail((req.headers['content-type']||'').split(';')[0]==='application/json','CONTENT_TYPE','Use application/json.',415);
   fail(!req.headers['content-encoding'],'CONTENT_ENCODING','Compressed request bodies are not supported.',415);
   fail(!req.headers['content-length']||Number(req.headers['content-length'])<=LIMITS.requestBytes,'TOO_LARGE','Request is too large.',413);
   let size=0;const pieces=[];for await(const c of req){size+=c.length;fail(size<=LIMITS.requestBytes,'TOO_LARGE','Request is too large.',413);pieces.push(c);}let data;
   try{data=JSON.parse(Buffer.concat(pieces).toString('utf8'));}catch{throw new Fault('BAD_JSON','Invalid JSON.');}
   fail(data&&typeof data.envelope==='string','BAD_INPUT','A signed envelope is required.');return data.envelope;
 }
 const server=http.createServer(async(req,res)=>{
   const trace=randomUUID();res.setHeader('X-Request-Id',trace);res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');res.setHeader('X-Frame-Options','DENY');
   res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
   res.setHeader('Permissions-Policy','camera=(), microphone=(), geolocation=()');
   try{
     if(limited(req)){res.setHeader('Retry-After','60');throw new Fault('RATE_LIMIT','Too many requests. Wait one minute.',429);}
     const path=new URL(req.url,'http://internal').pathname;
     if(path.startsWith('/v1/')){
       const origin=req.headers.origin;
       // Native requests omit Origin. Browser testing is allowed only explicitly and only same-origin.
       if(origin){fail(enableWebWallet,'WEB_WALLET_DISABLED','Browser testing is disabled on this server.',403);fail(allowedOrigin&&origin===allowedOrigin,'ORIGIN_DENIED','Origin is not allowed.',403);}
       if(path==='/v1/config'&&req.method==='GET')return reply(res,200,ledger.config());
       if(path==='/v1/wallets'&&req.method==='POST')return reply(res,200,ledger.register(await body(req)));
       if(path==='/v1/actions'&&req.method==='POST')return reply(res,200,ledger.action(await body(req)));
       throw new Fault('NOT_FOUND','API route not found.',404);
     }
     if(path==='/health'&&req.method==='GET')return reply(res,200,{status:'ok',mode:'test',version:'0.1.0'});
     fail(req.method==='GET'||req.method==='HEAD','METHOD_NOT_ALLOWED','Method not allowed.',405);
     let file;
     if(path==='/')file=join(WEB,'index.html');
     else if(path==='/lab'||path==='/lab/') {fail(enableWebWallet,'NOT_FOUND','Not found.',404);file=join(WEB,'lab.html');}
     else if(/^\/(app\.js|app\.css|qr\.js|landing\.css|logo\.svg|wallet-art\.webp)$/.test(path))file=join(WEB,path.slice(1));
     else if(path==='/downloads/paila-test.apk')file=process.env.APK_PATH||join(ROOT,'downloads','paila-test.apk');
     else throw new Fault('NOT_FOUND','Not found.',404);
     fail(existsSync(file)&&statSync(file).isFile(),'NOT_FOUND',path.endsWith('.apk')?'No APK has been built and published yet.':'Not found.',404);
     const headers={'Content-Type':TYPES[extname(file)]||'application/octet-stream','Content-Length':statSync(file).size,'Cache-Control':'no-store'};
     if(path.endsWith('.apk'))headers['Content-Disposition']='attachment; filename="paila-test.apk"';
     res.writeHead(200,headers);if(req.method==='HEAD')return res.end();createReadStream(file).pipe(res);
   }catch(e){if(!res.headersSent)reply(res,e instanceof Fault?e.status:500,{error:{code:e instanceof Fault?e.code:'INTERNAL',message:e instanceof Fault?e.message:'Server error. Retry with the same operation ID.',requestId:trace}});else res.end();if(!(e instanceof Fault))console.error(JSON.stringify({event:'server_error',requestId:trace,message:e.message}));}
 });
 server.requestTimeout=15000;server.headersTimeout=10000;server.keepAliveTimeout=5000;server.maxHeadersCount=40;
 return {server,ledger};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 process.umask(0o077);const {server,ledger}=createApp();const port=Number(process.env.PORT||8787);const host=process.env.HOST||'127.0.0.1';
 server.listen(port,host,()=>console.log(JSON.stringify({event:'ready',host,port,mode:'test',publiclyDeployed:false})));
 for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{server.close(()=>{ledger.close();process.exit(0);});setTimeout(()=>process.exit(1),5000).unref();});
}
