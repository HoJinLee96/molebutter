globalThis.MarketplaceChannels=require('../test-fixtures/marketplace-channels.json');
// Common marketplace submission UI against local synthetic APIs only.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const M=require('../molebutter-app/src/main/resources/static/js/common-marketplace-editor-model.js');
const {server}=require('./product-ui-preview.cjs');
// Keep all traffic local while reproducing a LAN HTTP origin without native randomUUID.
const httpLan=process.env.MOLEBUTTER_UI_HTTP_LAN==='true',fixtureHost=httpLan?'molebutter-http.test':'127.0.0.1';
const uuidPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,channel:'chrome',args:httpLan?['--host-resolver-rules=MAP molebutter-http.test 127.0.0.1','--no-proxy-server']:[]}),base='http://'+fixtureHost+':'+server.address().port;
 try{for(const width of [1440,390]){
  const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage(),errors=[],calls=[],preparations=[],executions=new Map(),executeKeys=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/*',route=>new URL(route.request().url()).origin===base?route.continue():route.abort());
  let draft=M.fresh(),counter=0,scenario='SUCCESS',loseResponse=false,releaseExecution=null,holdExecution=false;
  draft.id='42';draft.revision=4;draft.common.productCode='SYNTHETIC';draft.common.name='모의 전송 상품';draft.options[0].name='블랙';draft.options[0].price='10000';draft.options[0].quantity='9';
  function editingSession(){const current=structuredClone(draft);current.options[0].price='9000';current.options[0].quantity='7';return {id:'session-1',draftId:draft.id,revision:draft.revision,expiresAt:'2099-01-01T00:00:00Z',targets:[{market:'COUPANG',mode:'UPDATE',status:'READY',observedAt:'2026-10-06T12:00:00Z',document:current},...draft.selectedMarkets.filter(m=>m!=='COUPANG').map(m=>({market:m,mode:'CREATE',status:'UNSUPPORTED',document:structuredClone(draft)}))]};}
  function execution(id,status='RUNNING'){
   return {id,draftId:draft.id,revision:draft.revision,status,createdAt:'2026-10-06T12:00:00',updatedAt:'2026-10-06T12:00:00',targets:[{market:'COUPANG',mode:'UPDATE',status,externalProductId:'9000000000',steps:[{id:'price',type:'PRICE',optionId:draft.options[0].id,label:'현재 판매가 변경',status,attempts:1,code:null,message:'',updatedAt:'2026-10-06T12:00:00'},{id:'stock',type:'STOCK',optionId:draft.options[0].id,label:'판매 수량 변경',status,attempts:1,code:null,message:'',updatedAt:'2026-10-06T12:00:00'}]}]};
  }
  function finish(record,status){record.status=status;record.targets[0].status=status;for(const step of record.targets[0].steps)step.status=status;}
  await page.route('**/api/marketplaces/**',async route=>{
   const request=route.request(),url=new URL(request.url()),path=url.pathname,method=request.method();assert.equal(url.origin,base);calls.push({path,method});
   assert(!path.startsWith('/api/marketplaces/coupang/'),'submission tests must not call live or direct Coupang endpoints');
   if(path==='/api/marketplaces/drafts/42/edit-sessions')return route.fulfill({json:{code:'OK',data:editingSession()}});
   if(path==='/api/marketplaces/edit-sessions/session-1/reference'){draft=request.postDataJSON();draft.revision++;return route.fulfill({json:{code:'OK',data:editingSession()}});}
   if(path==='/api/marketplaces/drafts/42'){
    if(method==='PUT'){draft=request.postDataJSON();draft.revision++;}
    return route.fulfill({json:{code:'OK',data:draft}});
   }
   if(path==='/api/marketplaces/submissions/prepare'){
    const body=request.postDataJSON();assert.equal(typeof body.requested,'boolean');assert.equal(body.draftId,draft.id);assert.equal(body.revision,draft.revision);assert.equal(body.sessionId,'session-1');assert.deepEqual(body.targets.find(t=>t.market==='COUPANG').changes.map(c=>c.path),['options.price','options.quantity']);
    const unsupported=draft.selectedMarkets.includes('NAVER');
    const preview={id:'preview-'+(++counter),draftId:draft.id,revision:draft.revision,expiresAt:new Date(Date.now()+300000).toISOString(),requested:body.requested,executable:!unsupported,targets:[{market:'COUPANG',mode:'UPDATE',steps:[{id:'price',type:'PRICE',optionId:draft.options[0].id,label:'현재 판매가 변경'},{id:'stock',type:'STOCK',optionId:draft.options[0].id,label:'판매 수량 변경'}],changes:[{path:'옵션.'+draft.options[0].id+'.salePrice',before:'9000',after:draft.options[0].price},{path:'옵션.'+draft.options[0].id+'.amountInStock',before:'7',after:draft.options[0].quantity}],issues:[]}]};
    if(unsupported)preview.targets.push({market:'NAVER',mode:'CREATE',steps:[],changes:[],issues:[{market:'NAVER',path:'markets.NAVER',message:'네이버 저장 연결이 필요합니다.'}]});
    preparations.push(preview);return route.fulfill({json:{code:'OK',data:preview}});
   }
   if(/^\/api\/marketplaces\/submissions\/[^/]+\/execute$/.test(path)){
    const key=request.postDataJSON().idempotencyKey;assert.match(key,uuidPattern);executeKeys.push(key);
    let record=[...executions.values()].find(value=>value.key===key);
    if(!record){record=execution('execution-'+(++counter),scenario);record.key=key;if(scenario==='PARTIAL'){record.targets[0].steps[0].status='SUCCEEDED';record.targets[0].steps[1].status='FAILED';}executions.set(record.id,record);}
    if(holdExecution)await new Promise(resolve=>releaseExecution=resolve);
    if(loseResponse){loseResponse=false;return route.fulfill({status:503,json:{code:'MOCK_NETWORK',message:'전송 응답을 받지 못했습니다.'}});}
    return route.fulfill({json:{code:'OK',data:record}});
   }
   if(/^\/api\/marketplaces\/submissions\/[^/]+\/(retry|reconcile|revise)$/.test(path)){
    const record=executions.get(path.split('/').at(-2));assert(record);
    if(path.endsWith('/retry')){assert(['FAILED','PARTIAL'].includes(record.status));record.targets[0].steps[1].attempts++;finish(record,'RUNNING');setTimeout(()=>finish(record,'SUCCEEDED'),250);}
    else if(path.endsWith('/revise')){assert.equal(record.status,'PARTIAL');record.revised=true;for(const step of record.targets[0].steps)if(step.status==='QUEUED'){step.status='FAILED';step.code='NOT_EXECUTED';}}
    else {assert(['UNKNOWN','ACCEPTED'].includes(record.status));finish(record,'SUCCEEDED');}
    return route.fulfill({json:{code:'OK',data:record}});
   }
   if(path==='/api/marketplaces/submissions'){
    const items=[...executions.values()].reverse(),pageNumber=Number(url.searchParams.get('page')||0),size=Number(url.searchParams.get('size')||10);
    return route.fulfill({json:{code:'OK',data:{items:items.slice(pageNumber*size,(pageNumber+1)*size),page:pageNumber,totalPages:Math.ceil(items.length/size),totalElements:items.length}}});
   }
   if(/^\/api\/marketplaces\/submissions\/[^/]+$/.test(path))return route.fulfill({json:{code:'OK',data:executions.get(path.split('/').at(-1))}});
   throw Error('Unexpected synthetic API '+method+' '+path);
  });
  await page.goto(base+'/marketplaces/products/42/edit');
  const cryptoContext=await page.evaluate(()=>({hostname:location.hostname,secure:isSecureContext,randomUUID:typeof globalThis.crypto?.randomUUID,getRandomValues:typeof globalThis.crypto?.getRandomValues}));
  assert.deepEqual(cryptoContext,{hostname:fixtureHost,secure:!httpLan,randomUUID:httpLan?'undefined':'function',getRandomValues:'function'});
  await page.locator('#common-market-save').waitFor();await page.waitForFunction(()=>!document.querySelector('#common-market-save').disabled);
  await page.locator('[data-view-market=COUPANG]').click();await page.locator('#common-field-options-0-price').fill('10000');await page.locator('#common-field-options-0-quantity').fill('9');
  await page.locator('#common-requested').check();await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();
  assert.equal(preparations[0].requested,true);const previewText=await page.locator('#common-submission-preview').textContent();assert(previewText.includes('현재 판매가 변경'));assert(previewText.includes('옵션 블랙 · 현재 판매가'));assert(previewText.includes('옵션 블랙 · 현재 재고'));assert(!previewText.includes(draft.options[0].id),'internal option UUID must not replace its readable name');assert(previewText.includes('10000'));assert.equal(executeKeys.length,0,'preview must not execute');
  await page.locator('#common-submission-dismiss').click();assert.equal(executeKeys.length,0);
  await page.locator('#common-requested').uncheck();scenario='RUNNING';holdExecution=true;await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();
  const confirm=page.locator('#common-submission-confirm');await confirm.click();await page.waitForFunction(()=>document.querySelector('#common-submission-confirm').disabled);for(let i=0;i<100&&!releaseExecution;i++)await delay(10);assert.equal(executeKeys.length,1);assert(await page.locator('#common-submission-dismiss').isDisabled());assert(releaseExecution,'held synthetic execution started');releaseExecution();holdExecution=false;
  await page.locator('#common-submission-dialog').waitFor({state:'hidden'});const running=[...executions.values()].at(-1);setTimeout(()=>finish(running,'SUCCEEDED'),250);await page.locator('[data-execution="'+running.id+'"] .badge.ok').first().waitFor({timeout:10000});assert.equal(executeKeys.length,1);
  scenario='FAILED';await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();await confirm.click();await page.locator('#common-submission-dialog').waitFor({state:'hidden'});const failed=[...executions.values()].at(-1);const failedCard=page.locator('[data-execution="'+failed.id+'"]');await failedCard.getByRole('button',{name:'실패 작업 재시도',exact:true}).click();await failedCard.locator('.badge.ok').first().waitFor({timeout:10000});assert.equal(failed.targets[0].steps[1].attempts,2);
  scenario='PARTIAL';await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();await confirm.click();await page.locator('#common-submission-dialog').waitFor({state:'hidden'});const partial=[...executions.values()].at(-1),partialCard=page.locator('[data-execution="'+partial.id+'"]');assert((await partialCard.textContent()).includes('일부 완료'));assert.equal(partial.targets[0].steps[0].status,'SUCCEEDED');await partialCard.getByRole('button',{name:'실패 작업 재시도',exact:true}).click();await partialCard.locator('.badge.ok').first().waitFor({timeout:10000});assert.equal(partial.targets[0].steps[0].attempts,1,'successful step is retained');assert.equal(partial.targets[0].steps[1].attempts,2);
  const recovery=execution('recovery','PARTIAL');recovery.revision=draft.revision-1;recovery.targets[0].steps[0].status='SUCCEEDED';recovery.targets[0].steps[1].status='FAILED';recovery.targets[0].steps[1].code='HTTP_400';recovery.targets[0].steps.push({id:'queued',label:'미실행 작업',status:'QUEUED',attempts:0});executions.set(recovery.id,recovery);
  await page.locator('#common-submission-refresh').click();const recoveryCard=page.locator('[data-execution="recovery"]');await recoveryCard.getByRole('button',{name:'입력 수정',exact:true}).waitFor();assert.equal(await recoveryCard.locator('[data-submission-action=retry]').count(),0);
  await page.locator('#common-field-options-0-price').fill('12345');const writesBeforeRevision=calls.filter(c=>c.method!=='GET').length;await recoveryCard.getByRole('button',{name:'입력 수정',exact:true}).click();await recoveryCard.getByText(/기존 처리 결과를 보존했습니다/).waitFor();await page.waitForFunction(()=>!document.querySelector('#common-market-save').disabled);
  assert.equal(await page.locator('#common-field-options-0-price').inputValue(),'12345');assert.equal(calls.filter(c=>c.method!=='GET').length,writesBeforeRevision+1);assert.equal(recovery.targets[0].steps[0].status,'SUCCEEDED');assert.equal(recovery.targets[0].steps[1].code,'HTTP_400');assert.equal(recovery.targets[0].steps[2].code,'NOT_EXECUTED');assert.equal(await recoveryCard.locator('[data-submission-action]').count(),0);
  await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();await page.locator('#common-submission-dismiss').click();await page.locator('#common-field-options-0-price').fill('10000');
  scenario='UNKNOWN';await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();await confirm.click();await page.locator('#common-submission-dialog').waitFor({state:'hidden'});const unknown=[...executions.values()].at(-1),unknownCard=page.locator('[data-execution="'+unknown.id+'"]');assert.equal(await unknownCard.getByRole('button',{name:'실패 작업 재시도',exact:true}).count(),0);await unknownCard.getByRole('button',{name:'결과 확인',exact:true}).click();await unknownCard.locator('.badge.ok').first().waitFor();
  scenario='ACCEPTED';loseResponse=true;await page.locator('#common-market-save').click();await page.locator('#common-submission-dialog[open]').waitFor();await confirm.click();await page.getByText('전송 응답을 받지 못했습니다.',{exact:true}).first().waitFor();assert(await page.locator('#common-submission-dialog').isVisible());const lostKey=executeKeys.at(-1),count=executions.size;await confirm.click();await page.locator('#common-submission-dialog').waitFor({state:'hidden'});assert.equal(executeKeys.at(-1),lostKey);assert.equal(executions.size,count);
  assert(await page.locator('[data-selected-market=NAVER]').isDisabled());const beforePreparedTab=executeKeys.length;await page.locator('[data-view-market=NAVER]').click();await page.locator('#common-live-state strong').filter({hasText:'연동 준비 중'}).waitFor();assert.equal(executeKeys.length,beforePreparedTab);await page.locator('[data-view-market=COUPANG]').click();await page.screenshot({path:'/tmp/molebutter-submission-'+(httpLan?'http-lan-':'')+width+'.png'});
  for(let i=0;i<7;i++){const record=execution('history-'+i,'SUCCEEDED');executions.set(record.id,record);}await page.locator('#common-submission-refresh').click();await page.waitForFunction(()=>document.querySelector('#common-submission-page').textContent==='1 / 2');assert.equal(await page.locator('#common-submission-list [data-execution]').count(),10);await page.locator('#common-submission-next').click();await page.waitForFunction(()=>document.querySelector('#common-submission-page').textContent==='2 / 2');assert.equal(await page.locator('#common-submission-list [data-execution]').count(),executions.size-10);await page.locator('#common-submission-previous').click();await page.waitForFunction(()=>document.querySelector('#common-submission-page').textContent==='1 / 2');
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'viewport must not overflow');assert.deepEqual(errors,[]);assert(calls.every(value=>value.path.startsWith('/api/marketplaces/drafts')||value.path.startsWith('/api/marketplaces/edit-sessions')||value.path.startsWith('/api/marketplaces/submissions')));console.log('SUBMISSION_UI width='+width+' passed requests='+calls.length+' executions='+executions.size+' origin='+(httpLan?'untrusted-http':'trusted-loopback'));await context.close();
 }}finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;server.close();});
