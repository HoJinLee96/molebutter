'use strict';
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({channel:'chrome',headless:true});
  try { for(const width of [1440,390]) {
    const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    const {doc,source}=fixture('UPDATE');let lists=0,reads=0,writes=0;
    const entry={mode:'UPDATE',workingCopyId:'42',revision:1,productId:'123',document:doc,sessionId:'77',
      expiresAt:'2030-01-01T00:00:00Z',observation:source,blocked:false,changes:[],accountKey:'a'.repeat(64)};
    await context.route('https://**/*',route=>route.abort());
    await context.route('**/api/coupang/**',async route=>{
      const req=route.request(),path=new URL(req.url()).pathname;
      const reply=data=>route.fulfill({json:{code:'OK',data}});
      if(req.method()!=='GET') {writes++;throw Error('Unexpected write: '+path);}
      if(path==='/api/coupang/working-copies/42'){reads++;return reply(entry);}
      if(path.endsWith('/rules'))return reply(rules);
      if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
      if(path==='/api/coupang/submissions') {
        lists++;const status=lists>=3?'SUCCEEDED':'RUNNING';
        return reply({items:[{id:'101',workingCopyId:'42',revision:1,status,target:{mode:'CREATE',status,externalProductId:'123',steps:[
          {id:'create',type:'CREATE',label:'상품 등록',status:'SUCCEEDED',code:'SAVED_FOR_APPROVAL',message:'쿠팡 저장 완료'},
          {id:'approval',type:'APPROVAL',label:'판매 승인 요청',status,code:status==='SUCCEEDED'?'CREATED_CONFIRMED':null,message:''}
        ]}}],totalPages:1});
      }
      throw Error('Unexpected read: '+path);
    });
    await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/working-copies/42/edit');
    await page.waitForFunction(()=>document.querySelector('#coupang-submission-list').textContent.includes('승인·결과 자동 확인 중'));
    assert.equal(await page.locator('[data-submission-action="reconcile"]').count(),0);
    assert.equal(await page.locator('[data-submission-action="retry"]').count(),0);
    assert.equal(await page.locator('#coupang-save').isDisabled(),true);
    await page.locator('#coupang-submissions').screenshot({path:'/tmp/molebutter-approval-auto-'+width+'.png'});
    await page.waitForFunction(()=>!document.querySelector('#coupang-submission-list').textContent.includes('승인·결과 자동 확인 중')&&document.querySelector('#coupang-submission-list').textContent.includes('성공'));
    assert(lists>=3);assert(reads>=1);assert.equal(writes,0);assert.deepEqual(errors,[]);
    console.log('Automatic approval status, no manual action and automatic completion: '+width);
    await context.close();
  }} finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
