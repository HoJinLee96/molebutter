const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const {doc,source}=fixture('UPDATE');let document=structuredClone(doc),revision=1,changes=[],writes=0,failSave=false,expire=false;
  const entry=()=>({mode:'UPDATE',workingCopyId:'42',revision,productId:'123',document:structuredClone(document),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:source,blocked:false,changes});
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{const req=r.request(),path=new URL(req.url()).pathname;const reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/42'&&req.method()==='GET')return reply(entry());
   if(path==='/api/coupang/working-copies/42'&&req.method()==='PUT'){
    writes++;if(expire){expire=false;return r.fulfill({status:409,json:{code:'COUPANG_WORKING_COPY_EXPIRED',message:'세션 만료'}});}if(failSave){failSave=false;return r.fulfill({status:400,json:{code:'VALIDATION',message:'합성 저장 실패'}});}
    const body=req.postDataJSON();assert.equal(body.sessionId,'77');assert.equal(body.expectedRevision,revision);assert(!('reference' in body));assert(!('configuration' in body.document));document=body.document;changes=body.changes;revision++;return reply(entry());
   }
   if(path.endsWith('/rules'))return reply(rules);
   if(path==='/api/coupang/submissions')return reply({items:[],totalPages:0});
   if(path.endsWith('/cancel'))return reply(null);
   throw Error('Unexpected '+req.method()+' '+path);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/working-copies/42/edit');
  const name=page.locator('[data-field="basic.sellerProductName"]');await name.waitFor();await page.waitForTimeout(1300);
  assert.equal(await page.locator('[role="tab"]').filter({hasText:'내부 기준'}).count(),0);
  await name.fill('내가 수정한 상품명');await name.press('Enter');await page.waitForTimeout(120);assert.equal(writes,0);assert.equal(await name.inputValue(),'내가 수정한 상품명');
  await page.locator('#editor-save-working-copy').focus();await page.keyboard.press('Enter');await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent==='임시 저장 완료');assert.equal(writes,1);assert(changes.some(c=>c.path==='basic.sellerProductName'&&c.value==='내가 수정한 상품명'));
  await name.fill('실패 후 남는 입력');failSave=true;await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-save-error').textContent.includes('합성 저장 실패'));assert.equal(await name.inputValue(),'실패 후 남는 입력');
  expire=true;await page.locator('#editor-save-working-copy').focus();await page.keyboard.press('Space');await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent==='임시 저장 완료');assert.equal(document.basic.sellerProductName,'실패 후 남는 입력');
  await page.reload();await name.waitFor();assert.equal(await name.inputValue(),'실패 후 남는 입력');assert.deepEqual(errors,[]);console.log('Coupang working copy PC/mobile Enter, failed save, expired session and re-entry passed: '+width);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
