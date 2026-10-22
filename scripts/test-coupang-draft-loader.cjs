// Real Thymeleaf + local mock API only. No marketplace transmissions.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[],queries=[],writes=[];
  page.on('pageerror',e=>errors.push(e.message));let rejectList=false,dismissExit=true;
  page.on('dialog',d=>dismissExit?d.dismiss():d.accept());const {doc,source}=fixture('CREATE');
  const entry=(id=null,mode='CREATE')=>({mode,workingCopyId:id,revision:id?0:null,productId:mode==='UPDATE'?'123':null,document:structuredClone(doc),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:mode==='UPDATE'?source:null,blocked:false,changes:[],accountKey:'a'.repeat(64)});
  await context.route('https://**/*',r=>r.abort());await context.route('**/api/coupang/**',r=>{
   const req=r.request(),u=new URL(req.url()),path=u.pathname,reply=data=>r.fulfill({json:{code:'OK',data}});if(req.method()!=='GET')writes.push(path);
   if(path==='/api/coupang/working-copies/entry')return reply(entry(null,u.searchParams.has('productId')?'UPDATE':'CREATE'));
   if(path==='/api/coupang/working-copies/42')return reply(entry('42'));
   if(path==='/api/coupang/working-copies'){queries.push(u.searchParams);if(rejectList)return r.fulfill({status:500,json:{message:'합성 조회 실패'}});assert.equal(u.searchParams.get('mode'),'CREATE');assert.equal(u.searchParams.get('size'),'20');return reply({items:[{id:'42',name:'신규 임시저장',mode:'CREATE',updatedAt:'2026-10-16T10:00:00',blocked:true}],page:Number(u.searchParams.get('page')),totalPages:2,totalElements:21});}
   if(path.endsWith('/rules'))return reply(rules);if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});if(path==='/api/coupang/submissions')return reply({items:[],totalPages:0});if(path.endsWith('/cancel'))return reply(null);throw Error('Unexpected '+path);
  });
  const base='http://127.0.0.1:'+server.address().port;await page.goto(base+'/coupang/products/new');const name=page.locator('[data-field="basic.sellerProductName"]');await name.waitFor();await name.fill('미저장 신규 입력');
  assert.equal(await page.locator('#editor-save-working-copy + #editor-load-working-copy').count(),1);
  await page.locator('#editor-load-working-copy').click();await page.locator('#coupang-working-copies-rows a').waitFor();assert((await page.locator('#coupang-working-copies-rows').innerText()).includes('전송 결과 확인 필요'));
  await page.locator('#coupang-working-copies-search').fill('신규');await page.locator('#coupang-working-copies-search').press('Enter');await page.waitForFunction(()=>!document.querySelector('#coupang-working-copies-search').disabled);assert.equal(queries.at(-1).get('query'),'신규');assert.equal(writes.length,0);
  await page.locator('#coupang-working-copies-next').click();await page.waitForFunction(()=>document.querySelector('#coupang-working-copies-page').textContent==='2 / 2');assert.equal(queries.at(-1).get('page'),'1');
  await page.locator('#coupang-working-copy-close').click();assert.equal(await name.inputValue(),'미저장 신규 입력');rejectList=true;await page.locator('#editor-load-working-copy').click();await page.locator('#coupang-working-copies-error').waitFor({state:'visible'});await page.locator('#coupang-working-copy-close').click();assert.equal(await name.inputValue(),'미저장 신규 입력');
  rejectList=false;await page.locator('#editor-load-working-copy').click();await page.waitForFunction(()=>!document.querySelector('#coupang-working-copies-search').disabled);await page.locator('#coupang-working-copies-rows a').click();assert(page.url().endsWith('/products/new'));await page.locator('#coupang-working-copy-close').click();assert.equal(await name.inputValue(),'미저장 신규 입력');
  dismissExit=false;await page.locator('#editor-load-working-copy').click();await page.locator('#coupang-working-copies-rows a').click();await page.waitForURL('**/working-copies/42/edit');await name.waitFor();assert.equal(await name.inputValue(),'합성 상품');assert.equal(writes.length,0);
  await page.goto(base+'/coupang/products/new');await name.waitFor();assert.equal(await name.inputValue(),'미저장 신규 입력');
  await page.goto(base+'/coupang/products/123/edit');await name.waitFor();assert.equal(await page.locator('#editor-load-working-copy').isHidden(),true);assert.deepEqual(errors,[]);await context.close();console.log('PASS draft loader CREATE filter, search/paging, cancel/error, dirty exit, recovery and UPDATE visibility '+width);
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
