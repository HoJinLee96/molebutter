const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const {doc,source}=fixture('UPDATE');let document=structuredClone(doc),revision=1,changes=[],saves=0,previews=0,executions=0,checks=0,keys=[],lost=false,current=null;
  const entry=()=>({mode:'UPDATE',workingCopyId:'42',revision,productId:'123',document:structuredClone(document),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:source,blocked:false,changes,accountKey:'a'.repeat(64)});
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{const req=r.request(),path=new URL(req.url()).pathname;const reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/42'&&req.method()==='GET')return reply(entry());
   if(path==='/api/coupang/working-copies/42'&&req.method()==='PUT'){saves++;const b=req.postDataJSON();document=b.document;changes=b.changes;revision++;return reply(entry());}
   if(path.endsWith('/rules'))return reply(rules);
   if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
   if(path==='/api/coupang/submissions')return reply({items:current?[current]:[],totalPages:current?1:0});
   if(path.endsWith('/prepare')){previews++;const b=req.postDataJSON();assert.equal(b.workingCopyId,'42');assert(!('draftId' in b));assert(b.changes.some(c=>c.path==='options.registration.salePrice'));return reply({id:String(previews),workingCopyId:'42',revision,executable:true,expiresAt:'2030-01-01T00:00:00Z',target:{mode:'UPDATE',issues:[],changes:[{path:'salePrice',before:'80000',after:'90000'}],steps:[{id:'price',type:'PRICE',optionId:doc.options[0].id,label:'현재 가격 변경'}]}});}
   if(path.endsWith('/execute')){const key=req.postDataJSON().idempotencyKey;keys.push(key);if(!current){executions++;current={id:'101',workingCopyId:'42',revision,status:'UNKNOWN',target:{mode:'UPDATE',status:'UNKNOWN',steps:[{id:'price',type:'PRICE',status:'UNKNOWN',label:'현재 가격 변경',attempts:1}]}};}if(lost){lost=false;return r.abort();}return reply(current);}
   if(path.endsWith('/reconcile')){checks++;current.status='SUCCEEDED';current.target.status='SUCCEEDED';current.target.steps[0].status='SUCCEEDED';return reply(current);}
   if(path.endsWith('/cancel'))return reply(null);
   throw Error('Unexpected '+req.method()+' '+path);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/working-copies/42/edit');
  const price=page.locator('[data-field="options.0.currentChanges.salePrice"]');await price.waitFor();await page.waitForTimeout(1500);await price.fill('90000');await price.press('Enter');assert.equal(saves,0);assert.equal(executions,0);
  await page.locator('#coupang-save').click();await page.locator('#coupang-submission-dialog[open]').waitFor();assert.equal(await page.locator('#coupang-submission-preview .table-scroll').evaluate(el=>getComputedStyle(el).overflowX),'auto');assert.equal(previews,1);assert.equal(saves,1);assert.equal(executions,0);assert.equal(changes.filter(c=>c.path==='options.registration.salePrice').length,1);
  lost=true;await page.locator('#coupang-submission-confirm').click();await page.waitForFunction(()=>!document.querySelector('#coupang-submission-dialog-error').hidden);assert.equal(executions,1);assert.equal(await price.inputValue(),'90000');
  await page.locator('#coupang-submission-confirm').focus();await page.keyboard.press('Space');await page.waitForFunction(()=>!document.querySelector('#coupang-submission-dialog').open);assert.equal(executions,1);assert.equal(new Set(keys).size,1);
  await page.locator('[data-submission-action="reconcile"]').waitFor();assert.equal(await page.locator('#coupang-save').isDisabled(),true);assert.equal(await page.locator('[data-submission-action="retry"]').count(),0);await page.locator('[data-submission-action="reconcile"]').click();await page.waitForFunction(()=>document.querySelector('#coupang-submission-list').textContent.includes('성공'));assert.equal(checks,1);assert.equal(executions,1);assert.deepEqual(errors,[]);console.log('Coupang preview, explicit confirmation, response loss, idempotency and UNKNOWN reconciliation passed: '+width);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
