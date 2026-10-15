// Isolated browser; all address books, draft storage and marketplace responses are synthetic.
globalThis.MarketplaceChannels=require('../test-fixtures/marketplace-channels.json');
const {chromium}=require('playwright'),assert=require('node:assert/strict'),M=require('../molebutter-app/src/main/resources/static/js/common-marketplace-editor-model.js'),{server}=require('./product-ui-preview.cjs');
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const browser=await chromium.launch({headless:true,channel:'chrome'}),base='http://127.0.0.1:'+server.address().port;
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage(),errors=[],writes=[];let reference=null,pending=[],failReturn=false,naverReads=0,coupangReads=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  const session=()=>({id:'address-session',draftId:'42',revision:reference.revision,expiresAt:'2099-01-01T00:00:00Z',reference,targets:['COUPANG','NAVER'].map(m=>({market:m,mode:'CREATE',status:'READY',document:m==='NAVER'?require('../molebutter-app/src/main/resources/static/js/common-naver-editor-model.js').project(reference):M.newMarketDocument(reference,m),pendingChanges:pending.find(t=>t.market===m)?.changes||[]}))});
  const respond=(route,data)=>route.fulfill({json:{code:'OK',data}});
  await page.route('**/*',r=>new URL(r.request().url()).origin===base?r.continue():r.abort());
  await page.route('**/api/marketplaces/**',async r=>{
   const q=r.request(),url=new URL(q.url()),p=url.pathname;
   if(q.method()!=='GET'&&!p.endsWith('/cancel'))writes.push(p);
   if(p.includes('/shipping-places/')){coupangReads.push(performance.now());if(failReturn&&p.endsWith('/return'))return r.fulfill({status:502,json:{message:'조회 실패'}});const ret=p.endsWith('/return'),second=url.searchParams.get('page')==='2';return respond(r,{items:[{code:second?'103':ret?'102':'101',name:ret?'쿠팡 반품':'쿠팡 출고',usable:true,addresses:[{zipCode:'12345',address:'서울',detail:'1층',phone:'01012345678'}]},{code:'999',name:'미사용',usable:false,addresses:[]}],page:second?2:1,hasNext:!ret&&!second,totalCount:2});}
   if(p==='/api/marketplaces/naver/metadata/addresses'){naverReads++;return respond(r,{page:1,totalPage:1,addressBooks:[{addressBookNo:201,name:'네이버 출고',addressType:'RELEASE',zipCode:'12345',baseAddress:'부산',detailAddress:'2층',phoneNumber1:'01012345678'},{addressBookNo:202,name:'네이버 반품',addressType:'REFUND_OR_EXCHANGE',zipCode:'12345',baseAddress:'대전',detailAddress:'3층',phoneNumber1:'01012345678'}]});}
   if(p==='/api/marketplaces/elevenst/addressbooks')return respond(r,[{code:'7',name:'11번가',address:'서울',detail:'1층',zip:'12345',phone:'010'}]);
   if(p.startsWith('/api/marketplaces/lotteon/metadata/')&&p.endsWith('/page'))return respond(r,{items:[{id:p.includes('/return/')?'PLE00009':'PLO00008',name:'롯데ON 주소',data:{dvpNm:'롯데ON 주소',stnmZipNo:'12345',stnmZipAddr:'서울',stnmDtlAddr:'1층',telNo:'010'}}],page:0,hasNext:false});
   if(p==='/api/marketplaces/elevenst/brands')return respond(r,{items:[],page:1,hasNext:false,available:false,warning:'모의 CSV 미설정'});
   if(p==='/api/marketplaces/coupang/brands')return respond(r,{items:[{brandId:'KR-5',brandName:'닥스'}],page:1,hasNext:false});
   if(p.includes('/metadata/brands'))return respond(r,[{id:'39',name:'닥스'}]);
   if(p.endsWith('/cancel'))return respond(r,null);
   if(p==='/api/marketplaces/drafts'&&q.method()==='POST'){reference=q.postDataJSON();reference.id='42';reference.revision=0;return respond(r,reference);}
   if(p==='/api/marketplaces/drafts/42')return respond(r,reference);
   if(p.endsWith('/edit-sessions'))return respond(r,session());
   if(p.endsWith('/address-session/draft')){const b=q.postDataJSON();reference=b.reference;reference.revision++;pending=b.targets;return respond(r,{reference,session:session()});}
   if(p==='/api/marketplaces/submissions')return respond(r,{items:[],page:0,totalPages:0,totalElements:0});
   throw Error('Unexpected mock request '+p);
  });
  await page.goto(base+'/marketplaces/products/new');await page.locator('[data-common-address-search]').click();
  const pick=(m,k)=>page.locator(`[data-address-state="${m}.${k}"][data-address-index="0"]`);
  await pick('COUPANG','outbound').click();await pick('COUPANG','returns').click();await pick('NAVER','outbound').click();await pick('NAVER','returns').click();await pick('LOTTEON','outbound').click();await pick('LOTTEON','returns').click();await pick('ELEVENST','outbound').click();
  assert.equal(naverReads,1);assert.equal(coupangReads.length,2);assert(coupangReads[1]-coupangReads[0]>=990);assert.equal(await page.getByRole('button',{name:/미사용/}).count(),0);assert.equal(writes.length,0);
  assert(await page.locator('.common-address-dialog').evaluate(el=>el.scrollWidth<=el.clientWidth));await page.locator('[data-address-apply]').click();
  assert((await page.locator('[data-common-address-summary]').textContent()).includes('201'));
  await page.locator('[data-view-market=COUPANG]').click();const outbound={inputValue:async()=> (await page.locator('[data-coupang-address=outbound]').locator('..').textContent()).match(/코드 (\d+)/)[1]};assert.equal(await outbound.inputValue(),'101');assert.equal((await page.locator('[data-coupang-address=returns]').locator('..').textContent()).match(/코드 (\d+)/)[1],'102');
  await page.locator('[data-coupang-address=outbound]').click();await page.locator('[data-address-state="COUPANG.outbound"][data-address-page="2"]').click();await page.getByRole('button',{name:/쿠팡 출고 · 103/}).click();await page.locator('[data-address-apply]').click();assert.equal(await outbound.inputValue(),'103');
  await page.locator('[data-view-market=""]').click();await page.locator('[data-common-address-search]').click();await pick('COUPANG','outbound').click();await page.locator('[data-address-apply]').click();await page.locator('[data-view-market=COUPANG]').click();assert.equal(await outbound.inputValue(),'103');
  await page.locator('[data-view-market=NAVER]').click();const naverShip=page.locator('#common-naver-host [data-field="originProduct.deliveryInfo.claimDeliveryInfo.shippingAddressId"]');assert.equal(await naverShip.inputValue(),'201');await naverShip.fill('888');
  await page.locator('[data-view-market=""]').click();await page.locator('[data-common-address-search]').click();await pick('NAVER','outbound').click();await page.locator('[data-address-apply]').click();await page.locator('[data-view-market=NAVER]').click();assert.equal(await naverShip.inputValue(),'888');
  await page.locator('[data-view-market=""]').click();await page.locator('#common-field-common-productCode').fill('TEST');await page.locator('#common-field-common-name').fill('주소 테스트');await page.locator('#common-field-common-brand').fill('닥스');await page.locator('#common-field-common-brand').press('Enter');await page.locator('[data-brand-market=COUPANG][data-brand-index="0"]').click();await page.locator('[data-common-brand-apply]').click();await page.locator('#common-save').click();await page.getByText('임시 저장 완료',{exact:true}).waitFor();
  assert.equal(reference.markets.COUPANG.addressBook.outbound.code,'101');assert.equal(reference.markets.LOTTEON.addressBook.outbound.code,'PLO00008');assert.equal(reference.markets.ELEVENST.addressBook.outbound.code,'7');assert.equal(reference.markets.NAVER.addressBook.returns.code,'202');assert(pending.find(t=>t.market==='COUPANG').changes.some(c=>c.path==='delivery.outboundCode'&&c.value==='103'));
  await page.reload();await page.locator('[data-view-market=NAVER]').click();assert.equal(await naverShip.inputValue(),'888');await page.locator('[data-view-market=COUPANG]').click();assert.equal(await outbound.inputValue(),'103');
  await page.locator('[data-view-market=""]').click();failReturn=true;await page.locator('[data-common-address-search]').click();await page.getByText('조회 실패',{exact:true}).waitFor({state:'visible'}).catch(async()=>{assert((await page.locator('.common-address-dialog').textContent()).includes('조회 실패'));});await pick('NAVER','outbound').click();assert.equal(await page.locator('[data-address-apply]').isEnabled(),true);await page.locator('[data-address-cancel]').click();
  assert.equal(reference.markets.COUPANG.addressBook.outbound.code,'101');assert.equal(reference.markets.LOTTEON.addressBook.outbound.code,'PLO00008');assert.equal(reference.markets.ELEVENST.addressBook.outbound.code,'7');assert.deepEqual(errors,[]);console.log('COMMON_ADDRESS_UI width='+width+' passed');await context.close();
 }}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
