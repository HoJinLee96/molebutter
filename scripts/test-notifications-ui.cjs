// Notification entry, navigation, deletion and recovery; local mock APIs only.
const {chromium}=require('playwright');
const {server,notices}=require('./product-ui-preview.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs');
const make=id=>({id:String(id),type:'REFRESH_COMPLETED',severity:'INFO',title:'최신화 완료',message:'처리 결과',occurredAt:'2026-09-27T19:40:00',accessible:true,target:'/product-refresh?run=7'});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true,channel:'chrome'});
 try {
  const base='http://127.0.0.1:'+server.address().port;
  for(const width of [1440,390]) {
   notices.splice(0,notices.length,...[1,2,3].map(id=>id===2?{...make(id),type:'REFRESH_RETRY_WAIT',title:'상품 최신화 자동 재개 대기',message:'자동 재개 2/4단계 · 30분 대기 · 네이버 검색 접속 제한·보안 확인 · 10-21 03:47 재개 예정'}:make(id)));
   const context=await browser.newContext({viewport:{width,height:width===390?844:1080}}),page=await context.newPage(),errors=[];
   page.setDefaultTimeout(8000);page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',route=>route.abort());
   const open=async()=>{if(width===390)await page.locator('#menu-open').click();await page.locator('#notification-bell').click();await page.locator('#notification-drawer').waitFor({state:'visible'});};
   await page.goto(base+'/products');await page.waitForFunction(()=>document.querySelector('#notification-bell')?.getAttribute('aria-label')==='알림 3개');
   await open();const waiting=page.locator('[data-notification-id="2"]');assert.match(await waiting.innerText(),/자동 재개 2\/4단계 · 30분 대기/);assert.match(await waiting.innerText(),/접속 제한·보안 확인.*03:47 재개 예정/);assert(await waiting.evaluate(el=>el.scrollWidth<=el.clientWidth));await page.locator('[data-notification-target="3"]').click();await page.waitForURL('**/product-refresh?run=7');
   assert.equal(notices.filter(n=>!n.dismissed).length,3);
   await open();await page.route('**/api/notifications/3',route=>route.fulfill({status:409,contentType:'application/json',body:JSON.stringify({code:'CONFLICT',message:'삭제 실패'})}));
   await page.locator('[data-notification-dismiss="3"]').click();await page.locator('#notification-error').waitFor({state:'visible'});
   assert.equal(notices.find(n=>n.id==='3').dismissed,undefined);
   await page.unroute('**/api/notifications/3');await page.locator('[data-notification-dismiss="3"]').click();
   await page.waitForFunction(()=>document.querySelector('#notification-total').textContent==='2');
   await page.reload();await open();await page.locator('[data-notification-target="2"]').waitFor();assert.equal(await page.locator('[data-notification-dismiss="3"]').count(),0);
   await page.locator('[data-notification-close]').focus();await page.keyboard.press('Tab');assert(await page.locator('#notification-drawer').evaluate(d=>d.contains(document.activeElement)));
   fs.mkdirSync('target/ui-check',{recursive:true});await page.screenshot({path:`target/ui-check/notification-smoke-${width}.png`});
   await page.keyboard.press('Escape');await page.locator('#notification-drawer').waitFor({state:'hidden'});
   notices.push({...make(4),type:'COUPANG_COLLECTION_RESULT',title:'쿠팡 전체 가져오기 완료',message:'대상 312 · 완료 312 · 실패 0',target:'/coupang/products'});
   await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname==='/api/notifications'),page.evaluate(()=>window.dispatchEvent(new Event('focus')))]);await page.waitForFunction(()=>document.querySelector('#notification-bell').getAttribute('aria-label')==='알림 3개');
   assert.equal(await page.locator('.notification-toast:visible').count(),0);await open();await page.locator('[data-notification-target="4"]').waitFor();assert((await page.locator('#notification-list').innerText()).includes('대상 312'));
   await page.keyboard.press('Escape');notices.push(make(5));await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await page.locator('.notification-toast:visible').waitFor();assert((await page.locator('.notification-toast').innerText()).includes('최신화 완료'));
   assert.deepEqual(errors,[]);await context.close();console.log(`PASS notification smoke: entry, navigation, failure/retry, persistent deletion and keyboard (${width}px)`);
  }
 }finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(err=>{console.error(err);process.exitCode=1;server.close();});
