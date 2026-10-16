const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const {doc,source}=fixture('UPDATE');let document=structuredClone(doc),revision=1,changes=[],savedResponse,releaseSave,settlementRead;
  const saving=new Promise(r=>savedResponse=r),release=new Promise(r=>releaseSave=r),settled=new Promise(r=>settlementRead=r);
  const entry=()=>({mode:'UPDATE',workingCopyId:'42',revision,productId:'123',document:structuredClone(document),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:source,blocked:false,changes,accountKey:'a'.repeat(64)});
  let completed=false;
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{const req=r.request(),path=new URL(req.url()).pathname,reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/42'&&req.method()==='GET'){if(completed){document.options[0].registration.salePrice='150000';changes=changes.filter(c=>c.path!=='options.registration.salePrice');settlementRead();}return reply(entry());}
   if(path==='/api/coupang/working-copies/42'&&req.method()==='PUT'){const b=req.postDataJSON();document=b.document;changes=b.changes;revision++;const response=entry();savedResponse();await release;return reply(response);}
   if(path.endsWith('/rules'))return reply(rules);
   if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
   if(path==='/api/coupang/submissions')return reply({items:completed?[{id:'101',workingCopyId:'42',revision:1,status:'SUCCEEDED',target:{steps:[{id:'write',type:'PRICE',status:'SUCCEEDED'}]}}]:[],totalPages:completed?1:0});
   throw Error('Unexpected '+req.method()+' '+path);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/working-copies/42/edit');
  const name=page.locator('[data-field="basic.sellerProductName"]');await name.waitFor();await page.waitForTimeout(1200);
  await page.locator('[data-field="options.0.currentChanges.salePrice"]').fill('130000');
  await name.fill('저장 응답 전 입력');await page.locator('#editor-save-working-copy').click();await saving;
  completed=true;await page.locator('#coupang-submission-refresh').click();await settled;
  await page.waitForFunction(()=>document.querySelector('#coupang-submission-refresh').disabled===false);
  assert.equal(await name.isDisabled(),true,'completion refresh must not release the in-flight local save lock');
  releaseSave();await page.waitForFunction(()=>document.querySelector('[data-field="basic.sellerProductName"]').disabled===false);
  await page.waitForFunction(()=>document.querySelector('[data-field="options.0.currentChanges.salePrice"]').value==='150000');
  assert.equal(await name.inputValue(),'저장 응답 전 입력');assert.deepEqual(errors,[]);
  console.log('Coupang save/completion overlapping locks passed: '+width);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
