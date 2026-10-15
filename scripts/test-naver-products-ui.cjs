// 실제 목록 템플릿과 스크립트를 모의 API로 검사한다. 외부 마켓 호출 없음.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../molebutter-app/src/main/resources');
const template=fs.readFileSync(path.join(root,'templates/marketplaces.html'),'utf8');
const start=template.indexOf('<section id="marketplace-naver-panel"'),end=template.indexOf('</section>',start)+10;
const fragment=template.slice(start,end).replace(' hidden>','>');
const html='<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/css/app.css"></head><body><main>'+fragment+'</main><script src="/js/app-ui.js"></script><script src="/js/form-actions.js"></script><script src="/js/api.js"></script><script src="/js/naver-products.js"></script></body></html>';
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:900}}),page=await context.newPage(),requests=[],errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await context.route('**/*',async route=>{
   const url=new URL(route.request().url());if(url.hostname!=='fixture.test')return route.abort();
   if(url.pathname==='/api/marketplaces/naver/products'){
    requests.push(url);const current=Number(url.searchParams.get('page'))||1;
    return route.fulfill({json:{success:true,data:{products:[{originProductNo:'9001',channelProductNo:'9002',name:'모의 상품',statusType:url.searchParams.get('statusType')||'SALE',salePrice:1000,stockQuantity:0}],page:current,totalPages:3,hasNext:current<3}}});
   }
   if(url.pathname.startsWith('/js/')||url.pathname.startsWith('/css/')){
    const file=path.join(root,'static',url.pathname);
    return fs.existsSync(file)?route.fulfill({path:file}):route.fulfill({body:'',status:404});
   }
   if(url.pathname.endsWith('/edit'))return route.fulfill({contentType:'text/html',body:'<p>모의 수정 화면</p>'});
   return route.fulfill({contentType:'text/html',body:html});
  });
  await page.goto('https://fixture.test/marketplaces');assert.equal(requests.length,0);
  await page.locator('#naver-products-status').selectOption('SUSPENSION');
  await page.locator('#naver-products-seller-code').fill('SELLER');await page.locator('#naver-products-seller-code').press('Enter');
  await page.waitForFunction(()=>document.querySelector('#naver-products-summary').textContent==='1개 상품',null,{timeout:5000}).catch(async error=>{console.error({errors,requests:requests.map(String),body:await page.locator('body').innerText()});throw error;});
  assert.equal(requests.at(-1).searchParams.get('statusType'),'SUSPENSION');assert.equal(requests.length,1);
  await page.locator('#naver-products-next').click();await page.waitForFunction(()=>document.querySelector('#naver-products-page').textContent==='2 / 3');assert.equal(requests.at(-1).searchParams.get('statusType'),'SUSPENSION');
  await page.getByRole('link',{name:'수정',exact:true}).click();await page.goto('https://fixture.test/marketplaces');
  await page.waitForFunction(()=>document.querySelector('#naver-products-page').textContent==='2 / 3');assert.equal(await page.locator('#naver-products-status').inputValue(),'SUSPENSION');
  await page.locator('#naver-products-status').selectOption('OUTOFSTOCK');await page.getByRole('button',{name:'검색',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#naver-products-page').textContent==='1 / 3');assert.equal(requests.at(-1).searchParams.get('statusType'),'OUTOFSTOCK');
  await page.locator('#naver-products-reset').click();await page.waitForFunction(()=>!document.querySelector('#naver-products-reset').disabled);assert.equal(await page.locator('#naver-products-status').inputValue(),'');assert.equal(requests.at(-1).searchParams.get('statusType'),'');assert.deepEqual(errors,[]);
  console.log(`PASS ${width}px 판매상태·Enter 검색·페이지 이동·수정 후 복귀·초기화`);await context.close();
 }}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
