// Actual editor with synthetic API. Never contacts the running app, database or Coupang.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));let document=fixture('CREATE').doc,revision=4,saves=0,prepares=0,executions=0;
  document.options[0].noticeCategory='가방';document.options[0].notices=[{category:'가방',name:'소재',content:'상품 상세페이지 참조'}];
  const metadata={...rules,notices:[{name:'가방',fields:[{name:'소재',required:'MANDATORY'}]},{name:'기타 재화',fields:[{name:'품명',required:'MANDATORY'}]}]};
  const entry=()=>({mode:'CREATE',workingCopyId:'42',revision,productId:null,document:structuredClone(document),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:null,blocked:false,changes:[],accountKey:'a'.repeat(64),categoryRules:{...metadata,checkedAt:new Date().toISOString()}});
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{
   const req=r.request(),path=new URL(req.url()).pathname,reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/42'){
    if(req.method()==='PUT'){saves++;document=req.postDataJSON().document;revision++;
     assert.equal(document.options[0].noticeCategory,'기타 재화');assert(document.options[0].notices.every(n=>n.category==='기타 재화'&&n.content==='상품 상세페이지 참조'));}
    return reply(entry());
   }
   if(path.endsWith('/rules'))return reply(metadata);
   if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
   if(path==='/api/coupang/submissions')return reply({items:[{id:'101',workingCopyId:'42',revision:4,status:'FAILED',revised:true,target:{mode:'CREATE',status:'FAILED',steps:[{id:'create',type:'CREATE',status:'FAILED',label:'상품 등록',attempts:1,code:'HTTP_200_REJECTED',message:'쿠팡이 요청을 거절했습니다(HTTP 200).'}]}}],totalPages:1});
   if(path.endsWith('/prepare')){prepares++;return reply({id:'102',workingCopyId:'42',revision,executable:true,expiresAt:'2030-01-01T00:00:00Z',target:{mode:'CREATE',issues:[],changes:[],steps:[{id:'create',type:'CREATE',label:'상품 등록'},{id:'approval',type:'APPROVAL',label:'판매 승인 요청'}]}});}
   if(path.endsWith('/execute')){executions++;throw Error('Do not execute');}
   if(path.endsWith('/cancel'))return reply(null);
   throw Error('Unexpected '+req.method()+' '+path);
  });
  const base='http://127.0.0.1:'+server.address().port;
  await page.goto(base+'/coupang/working-copies/42/edit');
  const select=page.locator('[data-field="options.0.noticeCategory"]');await select.waitFor();await page.waitForFunction(()=>{const el=document.querySelector('[data-field="options.0.noticeCategory"]');return el && !el.disabled;});assert.equal(await select.inputValue(),'가방');
  await select.selectOption('기타 재화');await page.locator('#editor-notice-reference').check();
  await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent.includes('임시 저장 완료'));
  assert.equal(saves,1);await page.reload();await select.waitFor();await page.waitForFunction(()=>{const el=document.querySelector('[data-field="options.0.noticeCategory"]');return el && !el.disabled;});assert.equal(await select.inputValue(),'기타 재화');assert(await page.locator('#editor-notice-reference').isChecked());
  await page.locator('#editor-notices').scrollIntoViewIfNeeded();await page.screenshot({path:'/tmp/coupang-notice-reentry-'+width+'.png'});
  await page.locator('#coupang-save').click();await page.locator('#coupang-submission-dialog[open]').waitFor({timeout:10000}).catch(async err=>{console.error('Validation:',await page.locator('#editor-validation').textContent(),errors);throw err;});
  assert.equal(await page.locator('#coupang-submission-confirm').isEnabled(),true);assert.match(await page.locator('#coupang-submission-preview').textContent(),/신규 등록/);
  assert.equal(prepares,1);assert.equal(executions,0);assert.deepEqual(errors,[]);
  console.log('Rejected CREATE reentry and notice type/reference save-reload, preview only: '+width);
  await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
