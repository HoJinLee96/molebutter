const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await context.addInitScript(()=>{
   sessionStorage.setItem('molebutter.coupang.products.navigation.v46',JSON.stringify({submitted:{sellerProductName:'검색 입력'},inputs:{sellerProductName:'검색 입력'},tokens:[''],index:0,size:'10'}));
   sessionStorage.setItem('molebutter.coupang.working-copies.navigation.v47',JSON.stringify({page:0,query:'',tab:'WORKING'}));
  });
  let productReads=0;
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',r=>{
   const path=new URL(r.request().url()).pathname;
   if(path==='/api/coupang/products'){productReads++;return r.fulfill({json:{code:'OK',data:{items:[],nextToken:'',hasNext:false}}});}
   if(path==='/api/coupang/working-copies')return r.fulfill({json:{code:'OK',data:{items:[],total:0,page:0,size:20}}});
   throw Error('Unexpected '+path);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang');
  await page.waitForFunction(()=>document.querySelector('#coupang-summary').textContent.includes('0개'));
  assert.equal(await page.locator('#coupang-coupang-tab').getAttribute('aria-selected'),'true');
  assert.equal(await page.locator('#filter-sellerProductName').inputValue(),'검색 입력');
  assert.equal(productReads,1);assert.equal(await page.evaluate(()=>sessionStorage.getItem('molebutter.coupang.products.navigation.v46')),null);assert.deepEqual(errors,[]);
  console.log('Coupang product-tab navigation restore passed: '+width);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
