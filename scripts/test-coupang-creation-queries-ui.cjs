const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {fixture,rules}=require('./coupang-ui-fixture.cjs');
const root=path.resolve(__dirname,'../molebutter-app/src/main/resources'),fixtures=path.resolve(__dirname,'../molebutter-app/target/ui-fixtures');
const server=http.createServer((req,res)=>{
  const p=new URL(req.url,'http://localhost').pathname;
  if(p.startsWith('/coupang/')){res.setHeader('Content-Type','text/html; charset=utf-8');return res.end(fs.readFileSync(path.join(fixtures,'coupang-editor-ADMIN.html')));}
  if(/^\/(js|css)\/[a-zA-Z0-9/._-]+$/.test(p)&&!p.includes('..')){res.setHeader('Content-Type',p.endsWith('.js')?'text/javascript':'text/css');return res.end(fs.readFileSync(path.join(root,'static',p)));}
  res.statusCode=404;res.end();
});
(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const base='http://127.0.0.1:'+server.address().port,browser=await chromium.launch({channel:'chrome',headless:true});
  try{for(const width of [1440,390]){
    const context=await browser.newContext({viewport:{width,height:1000}}),page=await context.newPage(),errors=[],requests=[];
    page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
    const data=fixture('CREATE');let document=structuredClone(data.doc),expired=false,completed=false,prepareCalls=0,executeCalls=0;
    const snapshot=()=>({...rules,checkedAt:new Date(Date.now()-(expired?600001:0)).toISOString()});
    const entry=()=>({mode:completed?'UPDATE':'CREATE',workingCopyId:null,revision:null,productId:completed?'555':null,document,
      sessionId:'77',expiresAt:'2030-01-01T00:00:00Z',observation:completed?{...fixture('UPDATE').source,options:fixture('UPDATE').source.options.map(o=>({...o,current:null,currentError:'현재 판매값 미수집'}))}:null,
      blocked:false,changes:[],accountKey:'a'.repeat(64),categoryRules:completed?null:snapshot()});
    await context.route('**/*',async route=>{
      const req=route.request(),u=new URL(req.url()),p=u.pathname,reply=data=>route.fulfill({json:{code:'SUCCESS',data}});
      if(u.origin!==base)return route.abort();if(!p.startsWith('/api/'))return route.continue();
      requests.push({method:req.method(),p,query:u.search});
      if(p==='/api/auth/csrf')return reply({token:'fake',headerName:'X-CSRF-TOKEN'});
      if(p==='/api/auth/me')return reply({id:'1'});
      if(p==='/api/notifications/summary')return reply({unreadCount:0});
      if(p==='/api/settings/coupang-registration')return reply({accountKey:'a'.repeat(64),revision:1,brandLogos:[]});
      if(p.startsWith('/api/coupang/working-copies/prefill/'))return reply({entry:entry(),ownerId:'1',notes:[]});
      if(p.endsWith('/rules')){expired=false;return reply(snapshot());}
      if(p.includes('/shipping-places/'))return reply({items:[{code:'123',addresses:[{address:'합성 출고지'}]}],hasNext:false});
      if(p.endsWith('/cancel'))return reply(null);
      if(p==='/api/coupang/working-copies'&&req.method()==='POST'){document=req.postDataJSON().document;return reply({...entry(),workingCopyId:'42',revision:0});}
      if(p==='/api/coupang/working-copies/42'){
        assert.equal(req.method(),'GET');assert.equal(u.searchParams.get('observed'),'true');
        return reply({...entry(),workingCopyId:'42',revision:0});
      }
      if(p==='/api/coupang/submissions/prepare'){prepareCalls++;return reply({id:'88',workingCopyId:'42',revision:0,executable:true,target:{mode:'CREATE',categoryRulesCheckedAt:snapshot().checkedAt,steps:[{id:'create',type:'CREATE',label:'상품 등록'},{id:'approve',type:'APPROVAL',label:'판매 승인 요청'}],changes:[],issues:[],warnings:[]}});}
      if(p==='/api/coupang/submissions/88/execute'){executeCalls++;completed=true;return reply({id:'99',workingCopyId:'42',revision:0,status:'SUCCEEDED',target:{mode:'CREATE',status:'SUCCEEDED',externalProductId:'555',steps:[{id:'create',status:'SUCCEEDED'},{id:'approve',status:'SUCCEEDED'}]}});}
      if(p==='/api/coupang/submissions')return reply({items:[],totalPages:0});
      throw Error('Unexpected mock request '+req.method()+' '+p);
    });
    await page.goto(base+'/coupang/products/new?prefill=11111111-1111-4111-8111-111111111111');
    const name=page.locator('[data-field="basic.sellerProductName"]');await name.waitFor();
    await page.locator('#editor-category-status').filter({hasText:'조회 완료'}).waitFor();
    assert.equal(requests.filter(r=>r.p.endsWith('/rules')).length,0,'prefilled server rules require no second read');
    await name.fill('사용자가 보존할 제목');expired=true;await page.reload();await name.waitFor();
    await page.locator('#editor-category-status').filter({hasText:'만료'}).waitFor();assert.equal(await name.inputValue(),'사용자가 보존할 제목');
    assert.equal(requests.filter(r=>r.p.endsWith('/rules')).length,0,'expired rules never trigger an implicit provider read');
    await page.locator('#coupang-save').click();assert.equal(prepareCalls,0);assert.equal(executeCalls,0);
    await page.locator('#editor-category-load').click();await page.locator('#editor-category-status').filter({hasText:'조회 완료'}).waitFor();
    assert.equal(requests.filter(r=>r.p.endsWith('/rules')).length,1);assert.equal(await name.inputValue(),'사용자가 보존할 제목');
    await page.locator('#coupang-save').click();await page.locator('#coupang-submission-dialog').waitFor();
    assert.equal(prepareCalls,1);assert.equal(executeCalls,0,'preview must not execute registration');
    assert.match(await page.locator('.coupang-preview-summary').textContent(),/규격 확인/);
    await page.locator('#coupang-submission-confirm').click();
    await page.waitForFunction(()=>document.querySelector('#editor-mode')?.textContent.includes('상품 수정'));
    assert.equal(executeCalls,1);assert.equal(requests.filter(r=>r.p.endsWith('/rules')).length,1);
    assert.equal(requests.filter(r=>r.p==='/api/coupang/working-copies/42'&&r.query==='?observed=true').length,1);
    assert(!requests.some(r=>/inventories|external-vendor-sku/.test(r.p)));
    assert.equal(await name.inputValue(),'사용자가 보존할 제목');
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert.deepEqual(errors,[]);
    fs.mkdirSync('target/ui-check',{recursive:true});await page.screenshot({path:'target/ui-check/coupang-creation-queries-'+width+'.png',fullPage:true});
    console.log('PASS creation rules reuse/expiry/explicit refresh/preview/observed completion '+width);await context.close();
  }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
