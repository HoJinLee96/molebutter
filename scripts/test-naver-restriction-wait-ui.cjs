// Synthetic read-only status responses; never opens the running app or contacts Naver.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];
  let reason='SECURITY_CHECK',blocked=false,writes=0;
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>{if(r.url().includes('/api/')&&r.method()!=='GET')writes++;});
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/product-refresh?*',r=>{
   const current={id:'7',status:blocked?'BLOCKED':'RETRY_WAIT',triggerType:'MANUAL',createdAt:new Date().toISOString(),total:315,done:106,
    searchFailureCode:reason,searchRetryCount:blocked?5:1,loginRetryCount:0,nextRetryAt:blocked?null:'2030-01-01T01:30:00',
    message:'네이버 검색 접속 제한·보안 확인 · 자동 재개 중단, 수동 확인 필요',retryable:0};
   return r.fulfill({json:{code:'OK',data:{items:[current],active:[current],page:0,totalPages:1,totalElements:1,selected:null,
    searchGate:{version:1,untilAt:current.nextRetryAt,manualResumeRequired:blocked,runId:'7'},stockLookupBlock:null}}});
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/product-refresh');
  const rows=page.locator('#run-rows');await rows.getByText('보안 확인·자동입력 방지',{exact:false}).waitFor();
  assert.match(await rows.textContent(),/자동 재개 대기/);assert.match(await rows.textContent(),/01:30.*재개 예정/);
  assert.doesNotMatch(await rows.textContent(),/로그인/);
  assert.equal(await rows.locator('[data-action="resume"]').count(),0);
  await rows.scrollIntoViewIfNeeded();await page.screenshot({path:'/tmp/naver-restriction-wait-'+width+'.png'});
  reason='ACCESS_RESTRICTED';await page.locator('#runs-reload').click();await rows.getByText('네이버 검색 접속 제한',{exact:false}).waitFor();
  assert.match(await rows.textContent(),/01:30.*재개 예정/);
  blocked=true;await page.locator('#runs-reload').click();await rows.locator('[data-action="resume"]').waitFor();
  assert.match(await rows.textContent(),/수동 확인 필요/);assert.doesNotMatch(await rows.textContent(),/재개 예정/);
  await rows.scrollIntoViewIfNeeded();await page.screenshot({path:'/tmp/naver-restriction-stop-'+width+'.png'});
  assert.equal(writes,0);assert.deepEqual(errors,[]);
  console.log('Security/access restriction wait and final manual stop, zero writes: '+width);
  await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
