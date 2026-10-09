const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage();
  const pending=[],cancelled=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/marketplaces/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname;
   if(path==='/api/marketplaces/drafts')return route.fulfill({json:{code:'OK',data:{items:[],page:0,totalPages:0,totalElements:0}}});
   if(path.endsWith('/cancel')){cancelled.push(path);return route.fulfill({json:{code:'OK',data:null}});}
   assert.equal(req.method(),'GET');assert.match(req.headers()['x-coupang-request-id'],/^[0-9a-f-]{36}$/);
   if(/\/products\/\d+$/.test(path)){pending.push({route,id:req.headers()['x-coupang-request-id']});return;}
   return route.fulfill({json:{code:'OK',data:{items:[{sellerProductId:'1',sellerProductName:'취소 테스트 상품'}],hasNext:false,nextToken:''}}});
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/marketplaces');await page.locator('#marketplace-coupang-tab').click();
  await page.locator('#marketplace-query button[type=submit]').click();await page.getByText('취소 테스트 상품',{exact:true}).waitFor();
  await page.getByRole('button',{name:'취소 테스트 상품',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#marketplace-detail-close')?.offsetParent!==null);
  await page.waitForTimeout(100);assert.equal(pending.length,1);await page.locator('#marketplace-detail-close').click();
  await page.waitForTimeout(150);assert.deepEqual(cancelled,['/api/marketplaces/coupang/requests/'+pending[0].id+'/cancel']);
  await pending[0].route.fulfill({json:{code:'OK',data:{product:{sellerProductId:'1',sellerProductName:'늦은 응답'},items:[]}}}).catch(()=>{});
  await page.waitForTimeout(100);assert.equal(await page.locator('#marketplace-detail').isVisible(),false);
  await page.getByRole('button',{name:'취소 테스트 상품',exact:true}).click();await page.waitForTimeout(100);assert.equal(pending.length,2);assert.notEqual(pending[0].id,pending[1].id);
  await page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));await page.waitForTimeout(150);assert.equal(cancelled.length,2);
  await page.goto('http://127.0.0.1:'+server.address().port+'/marketplaces');await page.locator('#marketplace-coupang-tab').click();
  await pending[1].route.abort().catch(()=>{});assert.deepEqual(errors,[]);
  console.log('CANCELLATION_UI width='+width+' passed');await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;server.close();});
