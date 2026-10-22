const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs');
const {server}=require('./product-ui-preview.cjs');
const {fixture,rules}=require('./coupang-ui-fixture.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port,browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:1000}}),errors=[],writes=[];let accept=true,fail=false,settingsFail=false,hold,release;
  context.on('page',p=>{p.on('pageerror',e=>errors.push(e.message));p.on('dialog',d=>accept?d.accept():d.dismiss());});
  const account='a'.repeat(64),daks='https://assets.test/common/daks.png',hazzys='https://assets.test/common/hazzys.png',guide='https://assets.test/common/guide.png';
  let settings={accountKey:account,revision:2,delivery:{},registration:{},settings:{},descriptionPrefixImages:[guide],brandLogos:[{brandName:'닥스',aliases:['DAKS'],imageUrl:daks},{brandName:'헤지스',aliases:['HAZZYS'],imageUrl:hazzys}]};
  const doc=fixture('CREATE').doc;doc.basic.displayProductName='닥스 남성 지갑';doc.options[0].contents=[{id:'11111111-1111-4111-8111-111111111111',type:'IMAGE',detailType:'IMAGE',content:guide,assetId:null},{id:'22222222-2222-4222-8222-222222222222',type:'IMAGE',detailType:'IMAGE',content:daks,assetId:null,purpose:'BRAND_LOGO'},{id:'33333333-3333-4333-8333-333333333333',type:'HTML',detailType:'TEXT',content:'<p>상품 설명 보존</p>',assetId:null}];
  const entry=()=>({mode:'CREATE',workingCopyId:null,revision:null,productId:null,document:structuredClone(doc),sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:null,blocked:false,changes:[],accountKey:account,categoryRules:{...rules,checkedAt:new Date().toISOString()}});
  await context.route('**/*',async r=>{const q=r.request(),u=new URL(q.url()),p=u.pathname,reply=data=>r.fulfill({json:{code:'SUCCESS',data}});
   if(u.origin!==base)return r.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="300" height="100"><text x="40" y="50">BRAND</text></svg>'});
   if(!p.startsWith('/api/'))return r.continue();if(['POST','PUT'].includes(q.method())&&!p.startsWith('/api/auth/'))writes.push({path:p,body:q.postDataJSON()});
   if(p==='/api/auth/csrf')return reply({token:'test',headerName:'X-CSRF-TOKEN'});if(p==='/api/auth/me')return reply({id:'1'});
   if(p==='/api/notifications/summary')return reply({unreadCount:0});if(p==='/api/notifications')return reply({items:[],nextCursor:null});
   if(p==='/api/settings/coupang-registration'){if(q.method()==='PUT'){if(settingsFail)return r.fulfill({status:409,json:{message:'모의 설정 충돌'}});settings={...q.postDataJSON(),accountKey:account,revision:settings.revision+1};}return reply(settings);}
   if(p==='/api/coupang/working-copies/entry')return reply(entry());
   if(p==='/api/coupang/working-copies/brand-logo'){
    const title=q.postDataJSON().title,result={status:title.includes('닥스')&&title.includes('헤지스')?'AMBIGUOUS':title.includes('헤지스')?'MATCHED':'NOT_FOUND',logo:title.includes('헤지스')?settings.brandLogos[1]:null,accountKey:account,revision:settings.revision};
    if(hold)await hold;if(fail)return r.fulfill({status:503,json:{message:'모의 조회 실패'}});return reply(result);
   }
   if(p==='/api/coupang/r2-images/resolve'){if(fail)return r.fulfill({status:400,json:{message:'모의 파일 검사 실패'}});return reply({key:'common/logo.png',url:u.searchParams.get('url'),mimeType:'image/png',width:300,height:100,bytes:1000});}
   if(p.endsWith('/rules'))return reply(rules);if(p.endsWith('/cancel'))return reply(null);
   if(p==='/api/settings/brands')return reply([{name:'닥스'},{name:'헤지스'}]);
   if(p==='/api/settings/preferred-suppliers')return reply({revision:1,rules:[],stores:[]});if(p==='/api/settings/supplier-store-identities'||p==='/api/settings/payment-methods'||p==='/api/settings/suppliers')return reply([]);
   if(p==='/api/settings/coupang-pricing')return reply({revision:1,shippingCost:4000,commissionPercent:10.5,marginPercent:7});if(p==='/api/product-refresh/settings')return reply({revision:1,scheduleEnabled:false,scheduleTime:'03:00'});if(p.startsWith('/api/coupang/submissions'))return reply({items:[],totalPages:0});
   throw Error('Unexpected '+q.method()+' '+p);
  });
  const page=await context.newPage();await page.goto(base+'/coupang/products/new');await page.locator('#editor-logo-status').filter({hasText:'저장된 로고 2개'}).waitFor();await page.waitForFunction(()=>!document.querySelector('#editor-logo-find').disabled);
  const logo=()=>page.locator('[data-field="options.0.contents.1.content"]'),display=page.locator('[data-field="basic.displayProductName"]');assert.equal(await logo().inputValue(),daks);
  await display.fill('헤지스 남성 지갑');assert.equal(await logo().inputValue(),daks);await page.locator('#editor-logo-url').fill('https://assets.test/common/manual.png');await page.locator('#editor-logo-url').press('Enter');assert.equal(writes.length,0);
  accept=false;await page.locator('#editor-logo-apply').click();await page.waitForFunction(()=>!document.querySelector('#editor-logo-apply').disabled);assert.equal(await logo().inputValue(),daks);assert.equal(await page.locator('#editor-logo-url').inputValue(),'https://assets.test/common/manual.png');
  accept=true;await page.locator('#editor-logo-find').click();await page.locator('#editor-logo-status').filter({hasText:'적용했습니다'}).waitFor();assert.equal(await logo().inputValue(),hazzys);assert.equal(await page.locator('[data-field="options.0.contents.0.content"]').inputValue(),guide);
  const retained=await page.locator('[data-field="options.0.contents.2.content"]').inputValue();assert.equal(retained,'<p>상품 설명 보존</p>');
  await page.reload();await page.locator('#editor-logo-status').filter({hasText:'저장된 로고'}).waitFor();assert.equal(await logo().inputValue(),hazzys);
  fail=true;await page.locator('#editor-logo-url').fill('https://assets.test/common/fail.png');await page.locator('#editor-logo-apply').click();await page.locator('#editor-logo-status').filter({hasText:'모의 파일 검사 실패'}).waitFor();assert.equal(await logo().inputValue(),hazzys);assert.equal(await page.locator('#editor-logo-url').inputValue(),'https://assets.test/common/fail.png');fail=false;
  await display.fill('닥스 헤지스 지갑');await page.locator('#editor-logo-find').click();await page.locator('#editor-logo-status').filter({hasText:'여러 브랜드'}).waitFor();assert.equal(await logo().inputValue(),hazzys);
  await display.fill('브랜드 없는 지갑');accept=false;await page.locator('#editor-logo-find').click();await page.locator('#editor-logo-status').filter({hasText:'유지했습니다'}).waitFor();assert.equal(await logo().inputValue(),hazzys);accept=true;await display.fill('헤지스 지갑');
  hold=new Promise(r=>release=r);await page.locator('#editor-logo-find').click();await display.fill('지연 응답 중 변경된 제목');release();hold=null;await page.locator('#editor-logo-status').filter({hasText:'바뀌어 적용하지'}).waitFor();assert.equal(await logo().inputValue(),hazzys);
  await page.locator('#editor-logo-select').selectOption('');await page.waitForFunction(()=>!document.querySelector('#editor-logo-select').disabled);assert.equal(await page.locator('[data-field="options.0.contents.1.content"]').inputValue(),retained);await page.locator('#editor-brand-logo').evaluate(el=>el.scrollIntoView({block:'center'}));assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await page.screenshot({path:'/tmp/coupang-brand-logo-editor-'+width+'.png'});
  const config=await context.newPage();await config.goto(base+'/settings?tab=coupang-registration');await config.locator('[data-logo-field="brandName"]').first().waitFor();assert.equal(await config.locator('[data-logo-field="brandName"]').count(),2);await config.locator('[data-logo-action="preview"]').first().click();await config.locator('[data-logo-preview="0"]').waitFor({state:'visible'});
  await config.locator('[data-logo-field="aliases"]').first().fill('DAKS, 닥스');await config.locator('[data-logo-field="aliases"]').first().press('Enter');assert.equal(writes.filter(v=>v.path==='/api/settings/coupang-registration').length,0);
  settingsFail=true;await config.locator('#registration-default-save').click();await config.locator('#registration-default-status').filter({hasText:'입력은 유지'}).waitFor();assert.equal(await config.locator('[data-logo-field="aliases"]').first().inputValue(),'DAKS, 닥스');settingsFail=false;
  await config.locator('#registration-default-save').click();await config.locator('#registration-default-status').filter({hasText:'저장했습니다'}).waitFor();assert.deepEqual(settings.brandLogos[0].aliases,['DAKS','닥스']);assert.equal(settings.brandLogos[0].imageUrl,daks);
  await config.locator('#registration-brand-logo-add').click();assert.equal(await config.locator('[data-logo-field="brandName"]').last().inputValue(),'');await config.locator('[data-logo-action="remove"]').last().click();assert.equal(await config.locator('[data-logo-field="brandName"]').count(),2);await config.locator('#registration-brand-logos').scrollIntoViewIfNeeded();assert(await config.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await config.screenshot({path:'/tmp/coupang-brand-logo-settings-'+width+'.png'});
  assert.deepEqual(errors,[]);console.log('PASS brand logo settings/editor',width);await context.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
