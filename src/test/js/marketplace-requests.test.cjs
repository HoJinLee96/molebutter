const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function setup(insecure=false){const requests=[],cancels=[],events={};let id=0;const window={addEventListener:(k,fn)=>events[k]=fn};const crypto=insecure?{getRandomValues:bytes=>require('node:crypto').webcrypto.getRandomValues(bytes)}:{randomUUID:()=>`10000000-1000-4000-8000-${String(++id).padStart(12,'0')}`};const ctx={window,crypto,AbortController,apiGet:(url,options)=>new Promise((resolve,reject)=>{requests.push({url,options,resolve,reject});options.signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')));}),securedFetch:(url,options)=>{cancels.push({url,options});return Promise.resolve();}};ctx.apiRequest=ctx.apiGet;vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/app-ui.js','utf8')+fs.readFileSync('molebutter-app/src/main/resources/static/js/marketplace-requests.js','utf8'),ctx);return {start:window.CoupangRead.start,requests,cancels,events};}
test('reads attach a unique cancellation ID and successful reads are not cancelled',async()=>{const s=setup(),r=s.start('/api/marketplaces/coupang/products');assert(s.requests[0].options.headers['X-Coupang-Request-Id']);s.requests[0].resolve({items:[]});await r.promise;r.cancel();s.events.pagehide();assert.equal(s.cancels.length,0);});
test('cancel aborts fetch and cancels only its server read, exactly once',async()=>{const s=setup(),r=s.start('/read');const rejected=assert.rejects(r.promise,{name:'AbortError'});r.cancel();r.cancel();await rejected;assert.equal(s.cancels.length,1);assert.equal(s.cancels[0].options.method,'POST');assert(s.cancels[0].url.endsWith('/10000000-1000-4000-8000-000000000001/cancel'));assert.equal(s.cancels[0].options.keepalive,true);});
test('page exit cancels all unfinished reads with no browser retry',async()=>{const s=setup(),a=s.start('/one'),b=s.start('/two');const rejected=[assert.rejects(a.promise,{name:'AbortError'}),assert.rejects(b.promise,{name:'AbortError'})];s.events.pagehide();await Promise.all(rejected);assert.equal(s.requests.length,2);assert.equal(s.cancels.length,2);});

test('internal validation reads preserve JSON options and share cancellation',async()=>{const s=setup(),r=s.start('/api/marketplaces/drafts/validate',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});assert.equal(s.requests[0].options.method,'POST');assert.equal(s.requests[0].options.body,'{}');assert.equal(s.requests[0].options.headers['Content-Type'],'application/json');const rejected=assert.rejects(r.promise,{name:'AbortError'});r.cancel();await rejected;assert.equal(s.cancels.length,1);});

test('LAN HTTP reads and cancellation share distinct valid UUIDs without randomUUID',async()=>{
 const s=setup(true),a=s.start('/one'),b=s.start('/two');
 const ids=s.requests.map(request=>request.options.headers['X-Coupang-Request-Id']);
 for(const id of ids)assert.match(id,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
 assert.notEqual(ids[0],ids[1]);
 const rejected=assert.rejects(a.promise,{name:'AbortError'});a.cancel();await rejected;
 assert.equal(s.cancels[0].url,'/api/marketplaces/coupang/requests/'+ids[0]+'/cancel');
 s.requests[1].resolve({items:[]});await b.promise;
});
