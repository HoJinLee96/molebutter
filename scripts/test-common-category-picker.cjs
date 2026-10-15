// Isolated UI checks. All marketplace responses and draft storage are synthetic.
globalThis.MarketplaceChannels=require('../test-fixtures/marketplace-channels.json');
const {chromium}=require('playwright'),assert=require('node:assert/strict'),M=require('../molebutter-app/src/main/resources/static/js/common-marketplace-editor-model.js'),{server}=require('./product-ui-preview.cjs');
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true,channel:'chrome'}),base='http://127.0.0.1:'+server.address().port;
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage(),errors=[],writes=[];let reference=null,pending=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  const session=()=>({id:'category-session',draftId:'42',revision:reference.revision,expiresAt:'2099-01-01T00:00:00Z',reference,targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(reference,'COUPANG'),pendingChanges:pending}]});
  const respond=(route,data)=>route.fulfill({json:{code:'OK',data}});
  await page.route('**/*',r=>new URL(r.request().url()).origin===base?r.continue():r.abort());
  await page.route('**/api/marketplaces/**',async r=>{
   const q=r.request(),url=new URL(q.url()),p=url.pathname;
   if(q.method()!=='GET'&&!p.endsWith('/cancel'))writes.push(p);
   if(/\/categories\/\d+\/rules$/.test(p))return respond(r,{categoryCode:p.split('/').at(-2),attributes:[],notices:[],certifications:[],documents:[]});
   if(p==='/api/marketplaces/elevenst/categories')return respond(r,[{id:'20',name:'가방',leaf:true}]);
   if(p==='/api/marketplaces/lotteon/metadata/categories/page')return respond(r,{items:[{id:'BC01030100',name:'가방',leaf:true}],page:0,hasNext:false});
   if(p==='/api/marketplaces/coupang/categories')return respond(r,[{id:'123',name:'가방 > 크로스백'},{id:'456',name:'가방 > 숄더백'},...Array.from({length:6},(_,i)=>({id:String(700+i),name:'패션잡화 > 여성가방 > 추가 가방 '+i}))]);
   if(p==='/api/marketplaces/naver/metadata/categories')return respond(r,[{id:'50001',name:'크로스백',wholeCategoryName:'패션 > 가방 > 크로스백',last:true}]);
   if(p==='/api/marketplaces/elevenst/brands')return respond(r,{items:[],page:1,hasNext:false,available:false,warning:'모의 CSV 미설정'});
   if(p==='/api/marketplaces/coupang/brands')return respond(r,{items:[{brandId:'KR-5',brandName:'닥스'}],page:1,hasNext:false});
   if(p.includes('/metadata/brands'))return respond(r,[{id:'39',name:'닥스'}]);
   if(p.endsWith('/cancel'))return respond(r,null);
   if(p==='/api/marketplaces/drafts'&&q.method()==='POST'){reference=q.postDataJSON();reference.id='42';reference.revision=0;return respond(r,reference);}
   if(p==='/api/marketplaces/drafts/42')return respond(r,reference);
   if(p.endsWith('/edit-sessions'))return respond(r,session());
   if(p.endsWith('/category-session/draft')){const b=q.postDataJSON();reference=b.reference;reference.revision++;pending=b.targets.find(t=>t.market==='COUPANG')?.changes||[];return respond(r,{reference,session:session()});}
   if(p==='/api/marketplaces/submissions')return respond(r,{items:[],page:0,totalPages:0,totalElements:0});
   throw Error('Unexpected mock request '+p);
  });
  await page.goto(base+'/marketplaces/products/new');await page.locator('#common-category-query').fill('가방');await page.locator('#common-category-query').press('Enter');
  const results=page.locator('.common-category-provider').filter({has:page.getByRole('heading',{name:'쿠팡',exact:true})}).locator('.common-category-results');
  await page.locator('[data-category-market=COUPANG][data-category-index="7"]').waitFor();
  assert(await results.evaluate(el=>el.scrollHeight>el.clientHeight&&el.clientHeight===504));
  const visible=await results.evaluate(el=>[...el.children].filter(n=>{const r=n.getBoundingClientRect(),b=el.getBoundingClientRect();return r.top>=b.top&&r.bottom<=b.bottom+1;}).length);assert.equal(visible,5);
  await results.evaluate(el=>{el.scrollTop=el.scrollHeight;});
  await page.locator('[data-category-market=COUPANG][data-category-index="7"]').click();
  assert.equal(await page.locator('.common-category-provider').filter({has:page.getByRole('heading',{name:'스마트스토어',exact:true})}).locator('.common-category-results').evaluate(el=>el.scrollTop),0);
  await page.locator('[data-category-market=COUPANG][data-category-index="0"]').click();await page.locator('[data-category-market=NAVER][data-category-index="0"]').click();assert.equal(writes.length,0);
  await page.locator('[data-common-category-apply]').click();await page.waitForFunction(()=>document.querySelector('[data-common-category-summary]').textContent.includes('50001'));assert((await page.locator('[data-common-category-summary]').textContent()).includes('50001'));
  await page.locator('[data-view-market=COUPANG]').click();const code=page.locator('#common-field-markets-COUPANG-categoryCode');assert.equal(await code.inputValue(),'123');await code.fill('999');
  await page.locator('[data-view-market=""]').click();await page.locator('#common-category-query').fill('숄더백');await page.locator('#common-category-query').press('Enter');await page.locator('[data-category-market=COUPANG][data-category-index="0"]').click();await page.locator('[data-common-category-apply]').click();
  await page.locator('[data-view-market=COUPANG]').click();assert.equal(await code.inputValue(),'999');await page.locator('[data-view-market=""]').click();
  await page.locator('#common-field-common-productCode').fill('TEST');await page.locator('#common-field-common-name').fill('카테고리 테스트');await page.locator('#common-field-common-brand').fill('닥스');await page.locator('#common-field-common-brand').press('Enter');
  await page.locator('[data-brand-market=COUPANG][data-brand-index="0"]').click();await page.locator('[data-common-brand-apply]').click();await page.locator('#common-save').click();await page.getByText('임시 저장 완료',{exact:true}).waitFor();
  assert.equal(reference.markets.COUPANG.categoryCode,'456');assert.equal(reference.markets.NAVER.categoryCode,'50001');assert(pending.some(c=>c.path==='markets.COUPANG.categoryCode'&&c.value==='999'));
  await page.reload();await page.locator('[data-view-market=COUPANG]').click();assert.equal(await code.inputValue(),'999');assert.deepEqual(errors,[]);
  await page.locator('[data-view-market=""]').click();await page.locator('#common-category-query').fill('가방');await page.locator('#common-category-query').press('Enter');const box=await page.locator('.common-category-dialog').boundingBox();assert(box.x>=0&&box.x+box.width<=width+1);
  const markets=await page.locator('.common-category-providers > section').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,bottom:r.bottom};}));
  assert.equal(markets.length,6);assert(markets.every(r=>Math.abs(r.x-markets[0].x)<1&&Math.abs(r.width-markets[0].width)<1));
  assert(markets.every((r,i)=>i===0||r.y>=markets[i-1].bottom));
  assert(await page.locator('.common-category-dialog').evaluate(el=>el.scrollWidth<=el.clientWidth));
  await page.screenshot({path:'/private/tmp/category-six-rows-'+width+'.png'});
  await page.locator('[data-common-category-close]').click();
  console.log('COMMON_CATEGORY_UI width='+width+' passed');await context.close();
 }}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
