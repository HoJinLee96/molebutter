// Real application HTML/JS/CSS, synthetic history only. No live app or marketplace calls.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try { for(const width of [1440,390]) {
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const {doc,source}=fixture('UPDATE');let writes=0;
  const message='쿠팡이 요청을 거절했습니다(HTTP 200). 알려진 오류 사유로 분류되지 않았습니다.\n쿠팡 상세 사유: 필수 구매 옵션을 입력해주세요.\n옵션 1 · 색상: 값이 없습니다.\n'+ '긴 설명 '.repeat(25)+'<img src=x onerror="window.injected=true">';
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{
   const req=r.request(),path=new URL(req.url()).pathname,reply=data=>r.fulfill({json:{code:'OK',data}});
   if(req.method()!=='GET'){writes++;throw Error('Unexpected write '+path);}
   if(path==='/api/coupang/working-copies/42') return reply({mode:'UPDATE',workingCopyId:'42',revision:4,productId:'123',document:doc,sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:source,blocked:false,changes:[],accountKey:'a'.repeat(64)});
   if(path.endsWith('/rules'))return reply(rules);
   if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
   if(path==='/api/coupang/submissions')return reply({items:[{id:'101',workingCopyId:'42',revision:4,status:'FAILED',createdAt:'2026-10-19T13:10:38Z',target:{mode:'CREATE',status:'FAILED',steps:[{id:'create',type:'CREATE',status:'FAILED',label:'상품 등록',attempts:1,code:'HTTP_200_REJECTED',message},{id:'approval',type:'APPROVAL',status:'QUEUED',label:'판매 승인 요청',attempts:0}]}}],totalPages:1});
   throw Error('Unexpected GET '+path);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/working-copies/42/edit');
  const details=page.locator('.coupang-step-message').first();await details.waitFor();
  assert.equal(await details.textContent(),message);
  assert.equal(await details.locator('img').count(),0);
  assert.equal(await page.evaluate(()=>window.injected),undefined);
  assert.equal(await details.evaluate(el=>getComputedStyle(el).whiteSpace),'pre-line');
  assert(await details.evaluate(el=>el.scrollWidth<=el.clientWidth+1));
  await details.scrollIntoViewIfNeeded();
  await page.screenshot({path:'/tmp/coupang-rejection-details-'+width+'.png'});
  await page.reload();await page.locator('.coupang-step-message').first().waitFor();
  assert.equal(await page.locator('.coupang-step-message').first().textContent(),message);
  assert.equal(writes,0);assert.deepEqual(errors,[]);
  console.log('Redacted rejection history, reload, multiline wrapping, HTML escaping, zero writes: '+width);
  await context.close();
 }} finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
