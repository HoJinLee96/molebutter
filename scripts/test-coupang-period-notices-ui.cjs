// Synthetic APIs only. No operating app, database or Coupang requests.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try {for(const width of [1440,390]){
  const context=await browser.newContext({timezoneId:'America/Los_Angeles',viewport:{width,height:1000}}),page=await context.newPage(),errors=[];
  page.setDefaultTimeout(15000);page.setDefaultNavigationTimeout(15000);
  page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());let document=fixture('UPDATE').doc,revision=1,saves=[],failure=true,pending=null,release,mode='UPDATE',waiting=false;
  document.settings.saleStartedAt='2026-10-19T22:51:37';document.settings.saleEndedAt='2099-12-31T00:00:00';
  document.options[0].noticeCategory='패션잡화';document.options[0].notices=[{category:'패션잡화',name:'소재',content:'상품 상세페이지 참조'}];
  const metadata={...rules,notices:[{name:'패션잡화',fields:[{name:'소재',required:'MANDATORY'}]},{name:'가방',fields:[{name:'종류',required:'MANDATORY'}]}]};
  const entry=()=>({mode,workingCopyId:'42',revision,productId:'123',document:structuredClone(document),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:null,blocked:false,changes:[],accountKey:'a'.repeat(64)});
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{const req=r.request(),path=new URL(req.url()).pathname,reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/42'){
    if(req.method()==='PUT'){saves.push(req.postDataJSON());document=req.postDataJSON().document;revision++;}return reply(entry());
   }
   if(path.endsWith('/rules')){if(pending){const wait=pending;pending=null;waiting=true;await wait;}
    if(failure)return r.fulfill({status:502,json:{code:'COUPANG_RESPONSE',message:'규격 응답 확인 실패'}});return reply({...metadata,categoryCode:path.split('/').at(-2)});}
   if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
   if(path==='/api/coupang/submissions')return reply({items:[],totalPages:0});
   if(path.endsWith('/cancel'))return reply(null);
   throw Error('Unexpected '+req.method()+' '+path);
  });
  const base='http://127.0.0.1:'+server.address().port,url=base+'/coupang/working-copies/42/edit';await page.goto(url);
  const type=page.locator('[data-field="options.0.noticeCategory"]'),start=page.locator('[data-field="settings.saleStartedAt"]'),end=page.locator('[data-field="settings.saleEndedAt"]');
  await page.locator('#editor-notice-fields').getByRole('status').filter({hasText:'규격 응답 확인 실패'}).waitFor();
  assert.equal(await type.inputValue(),'패션잡화');assert(await type.isDisabled());assert.equal(await start.inputValue(),'2026-10-19T22:51:37');assert.equal(await end.getAttribute('value'),'2099-12-31T00:00:00');
  assert.equal(await page.locator('[data-settings-action="sale-period"]').count(),0);assert(!((await page.locator('#editor-settings').textContent()).includes('기간 제한 없음')));
  console.log('saving '+width);await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent.includes('임시 저장 완료'));
  assert.deepEqual(saves.at(-1).changes,[]);assert.equal(saves.at(-1).document.settings.saleEndedAt,'2099-12-31T00:00:00');
  await page.locator('[data-field="options.0.notices.0.content"]').fill('보존할 미저장 입력');
  console.log('retrying '+width);failure=false;pending=new Promise(r=>release=r);await page.locator('#editor-notice-retry').click();assert(await type.isDisabled());release();await type.waitFor();await page.waitForFunction(()=>!document.querySelector('[data-field="options.0.noticeCategory"]').disabled);
  assert.equal(await page.locator('[data-field="options.0.notices.0.content"]').inputValue(),'보존할 미저장 입력');assert.equal(await type.inputValue(),'패션잡화');
  console.log('changing type '+width);await type.selectOption('가방');await page.locator('#editor-notice-reference').check();await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent.includes('임시 저장 완료'));
  await page.reload();await page.waitForFunction(()=>{const el=document.querySelector('[data-field="options.0.noticeCategory"]');return el && !el.disabled;});assert.equal(await type.inputValue(),'가방');assert(await page.locator('#editor-notice-reference').isChecked());
  // Current type absent from new metadata remains visible, never defaults to another type.
  metadata.notices=metadata.notices.filter(n=>n.name!=='가방');await page.locator('#editor-notice-retry').click();await page.locator('#editor-notice-fields').getByRole('status').filter({hasText:'최신 규격에 없습니다'}).waitFor();assert.equal(await type.inputValue(),'가방');
  // Multiple option types stay distinct, including individual field editing.
  await context.clearCookies();await page.evaluate(()=>sessionStorage.clear());document.options[0].noticeCategory='패션잡화';document.options[0].notices=[{category:'패션잡화',name:'소재',content:'가죽'}];
  const second=structuredClone(document.options[0]);second.id='22222222-2222-4222-8222-222222222222';second.itemName='두번째 옵션';second.noticeCategory='가방';second.notices=[{category:'가방',name:'종류',content:'지갑'}];document.options.push(second);
  metadata.notices.push({name:'가방',fields:[{name:'종류',required:'MANDATORY'}]});await page.reload();await page.waitForFunction(()=>{const el=document.querySelector('[data-field="options.0.noticeCategory"]');return el && !el.disabled;});
  assert.equal(await type.inputValue(),'');assert.equal(await page.locator('.coupang-notice-option').count(),2);
  await page.locator('[data-field="options.1.notices.0.content"]').fill('두번째만 수정');await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent.includes('임시 저장 완료'));
  assert.equal(document.options[0].notices[0].content,'가죽');assert.equal(document.options[1].notices[0].content,'두번째만 수정');assert.equal(document.options[0].noticeCategory,'패션잡화');assert.equal(document.options[1].noticeCategory,'가방');
  for(const value of ['2027-03-01T13:14:15','2099-01-01T23:59:59','2099-12-31T23:59:00']){
   await page.evaluate(()=>sessionStorage.clear());document.settings.saleEndedAt=value;await page.reload();await end.waitFor();assert.equal(await end.getAttribute('value'),value);
  }
  await page.evaluate(()=>sessionStorage.clear());document.settings.saleEndedAt='잘못된 원문 날짜';await page.reload();await end.waitFor();assert.equal(await end.getAttribute('type'),'text');assert.equal(await end.inputValue(),'잘못된 원문 날짜');
  await page.evaluate(()=>sessionStorage.clear());document.settings.saleEndedAt='';await page.reload();await end.waitFor();assert.equal(await end.inputValue(),'');
  await end.fill('2099-12-31T23:59:59');await page.locator('[data-coupang-long-period]').focus();await page.keyboard.press('Enter');
  assert.equal(await end.getAttribute('value'),'2099-12-31T23:59:00');assert(Math.abs(Date.now()-Date.parse((await start.getAttribute('value'))+'+09:00'))<60000);
  await page.waitForFunction(()=>{const el=document.querySelector('[data-field="options.0.noticeCategory"]');return el && !el.disabled;});
  await page.locator('#editor-settings').scrollIntoViewIfNeeded();await page.screenshot({path:'/tmp/coupang-sale-period-'+width+'.png'});
  await page.locator('#editor-notices').scrollIntoViewIfNeeded();await page.screenshot({path:'/tmp/coupang-period-notices-'+width+'.png'});assert.deepEqual(errors,[]);
  // An older rules request must not replace a new category or its direct input.
  await page.evaluate(()=>sessionStorage.clear());mode='CREATE';pending=new Promise(r=>release=r);waiting=false;await page.reload();
  await page.locator('#editor-category-load').click();
  for(let i=0;i<100 && !waiting;i++)await new Promise(r=>setTimeout(r,30));assert(waiting);
  const code=page.locator('[data-field="basic.displayCategoryCode"]');await code.fill('124');await code.press('Enter');
  await page.waitForFunction(()=>{const el=document.querySelector('[data-field="options.0.noticeCategory"]');return el && !el.disabled;});
  await page.locator('[data-field="options.0.notices.0.content"]').fill('새 카테고리 입력');release();
  await page.waitForTimeout(100);assert.equal(await code.inputValue(),'124');assert.equal(await page.locator('[data-field="options.0.notices.0.content"]').inputValue(),'새 카테고리 입력');assert.deepEqual(errors,[]);
  console.log('Periods and notices: date preservation, explicit action, failure/retry, missing/mixed types and reentry '+width);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
