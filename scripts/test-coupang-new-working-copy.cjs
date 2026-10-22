const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),M=require('../molebutter-app/src/main/resources/static/js/coupang-editor-model.js'),W=require('../molebutter-app/src/main/resources/static/js/coupang-working-copy-model.js');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){for(const failure of ['COUPANG_WORKING_COPY_EXPIRED','COUPANG_WORKING_COPY_NOT_FOUND']){
  const context=await browser.newContext({timezoneId:'America/Los_Angeles',viewport:{width,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());let saved=null,creates=0,firstInput;
  const entry=()=>({mode:'CREATE',workingCopyId:saved?'42':null,revision:saved?0:null,productId:null,document:saved||W.flatten(M.fresh()),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:null,blocked:false,changes:[],accountKey:'a'.repeat(64)});
  await context.route('https://**/*',r=>r.abort());await context.route('**/api/coupang/**',async r=>{const req=r.request(),path=new URL(req.url()).pathname,reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/entry')return reply({...entry(),workingCopyId:null,revision:null,document:W.flatten(M.fresh())});
   if(path==='/api/coupang/working-copies'&&req.method()==='POST'){creates++;const b=req.postDataJSON();assert.equal(b.expectedRevision,null);if(creates===1){firstInput=b.document;return r.fulfill({status:409,json:{code:failure,message:'세션 만료'}});}assert.deepEqual(b.document,firstInput);saved=b.document;return reply(entry());}
   if(path==='/api/coupang/working-copies/42')return reply(entry());
   if(path==='/api/coupang/submissions')return reply({items:[],totalPages:0});
   if(path.endsWith('/cancel'))return reply(null);throw Error('Unexpected '+path);
  });
  const base='http://127.0.0.1:'+server.address().port;await page.goto(base+'/coupang/products/new');const name=page.locator('[data-field="basic.sellerProductName"]');await name.waitFor();assert.equal(await page.locator('[data-field="settings.saleStartedAt"]').inputValue(),'');await page.locator('[data-coupang-long-period]').focus();await page.keyboard.press('Space');assert.equal(creates,0);await name.fill('첫 저장 전 입력');await name.press('Enter');assert.equal(creates,0);await page.reload();await name.waitFor();assert.equal(await name.inputValue(),'첫 저장 전 입력');assert.equal(creates,0);
  await page.locator('#editor-save-working-copy').focus();await page.keyboard.press('Space');await page.waitForFunction(()=>location.pathname.includes('/working-copies/42/'));assert.equal(creates,2);assert.equal(saved.basic.sellerProductName,'첫 저장 전 입력');assert.equal(saved.settings.saleEndedAt,M.longSalePeriod().saleEndedAt);assert(Math.abs(Date.now()-Date.parse(saved.settings.saleStartedAt+'+09:00'))<60000,'new sale start uses Korean time regardless of browser timezone');await page.reload();await name.waitFor();assert.equal(await name.inputValue(),'첫 저장 전 입력');
  await page.goto(base+'/coupang/products/new');await name.waitFor();assert.equal(await name.inputValue(),'');assert.equal(creates,2);assert.deepEqual(errors,[]);console.log('Coupang first-save creation, unsaved re-entry, UUID recovery and cleared new-form cache passed: '+width+' '+failure);await context.close();
 }}}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
