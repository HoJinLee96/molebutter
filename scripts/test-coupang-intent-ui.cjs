const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs'),{fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390])for(const initialMode of ['UPDATE','CREATE']){
  const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  const {doc,source}=fixture(initialMode);doc.options[0].registration.salePrice='120000';source.options[0].current.salePrice=120000;
  let document=structuredClone(doc),revision=1,changes=[],previews=0,current=null,complete=false,gets=0,appliedRevision=null;
  const entry=()=>({mode:complete?'UPDATE':initialMode,workingCopyId:'42',revision,productId:complete||initialMode==='UPDATE'?'123':null,document:structuredClone(document),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:complete||initialMode==='UPDATE'?source:null,blocked:false,changes,accountKey:'a'.repeat(64),appliedRevision});
  const finish=()=>{complete=true;appliedRevision=revision;changes=[];document.options[0].registration.salePrice='150000';source.options[0].current.salePrice=150000;current.status='SUCCEEDED';current.target.status='SUCCEEDED';current.target.steps[0].status='SUCCEEDED';};
  await context.route('https://**/*',r=>r.abort());
  await context.route('**/api/coupang/**',async r=>{const req=r.request(),path=new URL(req.url()).pathname,reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path==='/api/coupang/working-copies/42'&&req.method()==='GET'){gets++;return reply(entry());}
   if(path==='/api/coupang/working-copies/42'&&req.method()==='PUT'){const b=req.postDataJSON();document=b.document;changes=b.changes;revision++;return reply(entry());}
   if(path.endsWith('/rules'))return reply(rules);
   if(path.includes('/shipping-places/'))return reply({items:[],hasNext:false});
   if(path==='/api/coupang/submissions')return reply({items:current?[current]:[],totalPages:current?1:0});
   if(path.endsWith('/prepare')){previews++;const b=req.postDataJSON();assert.equal(b.workingCopyId,'42');assert(!('requested' in b));if(previews===2){assert(b.changes.some(c=>c.path==='basic.sellerProductName'));assert(!b.changes.some(c=>c.path==='options.registration.salePrice'));}return reply({id:String(previews),workingCopyId:'42',revision,executable:true,expiresAt:'2030-01-01T00:00:00Z',target:{mode:entry().mode,issues:[],changes:[],steps:[{id:'write',type:initialMode==='CREATE'?'CREATE':'PRICE',optionId:doc.options[0].id,label:'합성 전송'}]}});}
   if(path.endsWith('/execute')){current={id:'101',workingCopyId:'42',revision,status:'QUEUED',target:{mode:initialMode,status:'QUEUED',steps:[{id:'write',type:initialMode==='CREATE'?'CREATE':'PRICE',status:'QUEUED',label:'합성 전송',attempts:1}]}};if(initialMode==='CREATE')finish();return reply(current);}
   if(path.endsWith('/cancel'))return reply(null);throw Error('Unexpected '+req.method()+' '+path);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/working-copies/42/edit');
  const name=page.locator('[data-field="basic.sellerProductName"]');await name.waitFor();await page.waitForTimeout(1200);
  if(initialMode==='UPDATE')await page.locator('[data-field="options.0.currentChanges.salePrice"]').fill('130000');
  await page.locator('#coupang-save').click();await page.locator('#coupang-submission-dialog[open]').waitFor();await page.locator('#coupang-submission-confirm').click();await page.waitForFunction(()=>!document.querySelector('#coupang-submission-dialog').open);
  if(initialMode==='UPDATE'){
   await name.fill('전송 중 새 입력');finish();await page.locator('#coupang-submission-refresh').click();
   await page.waitForFunction(()=>document.querySelector('[data-field="options.0.currentChanges.salePrice"]').value==='150000');
   assert.equal(await name.inputValue(),'전송 중 새 입력');await page.reload();await name.waitFor();assert.equal(await name.inputValue(),'전송 중 새 입력');
  }else{
   await page.waitForFunction(()=>document.querySelector('[data-field="basic.displayCategoryCode"]').disabled);
   assert((await page.locator('body').textContent()).includes('상품 수정'));await name.fill('등록 후 이름 수정');
  }
  assert(gets>=2);await page.locator('#coupang-save').click();await page.locator('#coupang-submission-dialog[open]').waitFor();assert.equal(previews,2);assert.deepEqual(errors,[]);
  console.log('Coupang completed intent settlement, unsaved recovery, next name-only request and UPDATE transition passed: '+width+' '+initialMode);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
