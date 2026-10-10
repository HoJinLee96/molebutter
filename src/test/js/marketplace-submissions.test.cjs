const {test}=require('node:test'),assert=require('node:assert/strict');
const S=require('../../../molebutter-app/src/main/resources/static/js/marketplace-submissions.js');
const execution=(status,...states)=>({status,targets:[{steps:states.map(status=>({status}))}]});
test('standalone option changes use observed option name and readable price/stock labels',()=>{
    const draft={options:[{id:'internal-option-id',name:'브라운 FREE'}]};
    assert.equal(S.changeLabel('옵션.internal-option-id.options.price',draft),'옵션 브라운 FREE · 판매가');
    assert.equal(S.changeLabel('옵션.internal-option-id.options.quantity',draft),'옵션 브라운 FREE · 재고');
});
test('ambiguous registration cannot offer retry; reconciliation remains available',()=>{
    const e=execution('UNKNOWN','UNKNOWN');assert.equal(S.retryable(e),false);assert.equal(S.reconcilable(e),true);
    assert.equal(S.retryable(execution('PARTIAL','SUCCEEDED','FAILED','UNKNOWN')),false);
});
test('failed independent operations can retry after completion without repeating successful work',()=>{
    assert.equal(S.retryable(execution('PARTIAL','SUCCEEDED','FAILED')),true);
    assert.equal(S.retryable(execution('RUNNING','FAILED','QUEUED')),false);
    assert.equal(S.retryable(execution('SUCCEEDED','SUCCEEDED')),false);
    assert.equal(S.retryable({status:'FAILED',targets:[{steps:[{status:'FAILED',code:'BASELINE_CHANGED'}]}]}),false);
});
test('approval acceptance stays distinct from success and only offers result verification',()=>{
    const e=execution('ACCEPTED','ACCEPTED');assert.equal(S.active(e),false);assert.equal(S.reconcilable(e),true);assert.equal(S.retryable(e),false);
    assert.notEqual(S.statuses.ACCEPTED,S.statuses.SUCCEEDED);
});
test('change review identifies the option and field across registration and current-value requests',()=>{
    const draft={options:[{id:'stable-option-id',name:'블랙'}]};
    const spec=require('../../../molebutter-app/src/main/resources/static/js/marketplace-editor-model.js').spec;
    assert.equal(S.changeLabel('items.[1].salePrice',draft,spec),'옵션 블랙 · 등록 판매가');
    assert.equal(S.changeLabel('옵션.stable-option-id.salePrice',draft,spec),'옵션 블랙 · 현재 판매가');
    assert.equal(S.changeLabel('items.[1].images.[2].vendorPath',draft,spec),'옵션 블랙 · 이미지 2 · 이미지 주소');
    assert.equal(S.changeLabel('배송.returnCharge',draft,spec),'배송 · 반품 비용');
});

test('retry requires a replayable failure and the current saved draft revision',()=>{
 for(const code of ['ALREADY_REGISTERED','UNSUPPORTED_SNAPSHOT_VERSION','BASELINE_CHANGED'])assert.equal(S.retryable({status:'FAILED',revision:1,targets:[{steps:[{status:'FAILED',code}]}]},1),false);
 const failed={...execution('FAILED','FAILED'),revision:1};assert.equal(S.retryable(failed,2),false);assert.equal(S.retryable(failed,1),true);assert.equal(S.retryable({...failed,revised:true},1),false);
 assert.equal(S.retryable(execution('PARTIAL','FAILED','ACCEPTED')),false);
});

const fs=require('node:fs'),vm=require('node:vm');
const uiSource=fs.readFileSync('molebutter-app/src/main/resources/static/js/marketplace-submissions.js','utf8');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function setupUI(items){
 const nodes=new Map(),reads=[],writes=[],busy=[];let saved=0;
 const get=id=>{if(!nodes.has(id))nodes.set(id,{hidden:false,disabled:false,open:false,dataset:{},listeners:{},value:'작성 중인 값',innerHTML:'',textContent:'',addEventListener(name,fn){this.listeners[name]=fn;},querySelectorAll(){return [];},showModal(){this.open=true;},close(){this.open=false;},focus(){},scrollIntoView(){}});return nodes.get(id);};
 const draft={id:'draft-1',revision:2};
 const context={AppUI:{$:get,escape:v=>String(v??''),uuid:()=> 'key'},window:{addEventListener(){}},setTimeout:()=>1,clearTimeout(){},apiGet:url=>{reads.push(url);return Promise.resolve({items,totalPages:1});},apiPost:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body,resolve,reject}))};
 vm.runInNewContext(uiSource,context);
 const ui=context.window.MarketplaceSubmissionUI.create({canAct:()=>true,save:()=>{saved++;return Promise.resolve(draft);},busy:value=>busy.push(value)});
 ui.draft(draft);
 return {ui,get,reads,writes,busy,draft,saved:()=>saved,
  action(type,id='execution-1'){const button={disabled:false,dataset:{submissionAction:type,executionId:id}};get('common-submissions').listeners.click({target:{closest:()=>button}});},
  click(id){get(id).listeners.click();},setItems(value){items=value;}};
}
const partial=()=>({id:'execution-1',draftId:'draft-1',revision:1,status:'PARTIAL',targets:[{market:'COUPANG',mode:'UPDATE',steps:[{status:'SUCCEEDED',label:'배송'},{status:'FAILED',code:'HTTP_400',label:'가격'},{status:'QUEUED',label:'재고'}]}]});
test('shared history offers input revision after draft revision changes and preserves attempted results',async()=>{
 const old=partial(),s=setupUI([old]);await flush();
 assert.match(s.get('common-submission-list').innerHTML,/data-submission-action="revise"/);
 assert.doesNotMatch(s.get('common-submission-list').innerHTML,/data-submission-action="retry"/);
 const input=s.get('common-field-common-name');s.action('revise');s.action('revise');
 assert.equal(s.writes.length,1);assert.equal(s.writes[0].url,'/api/marketplaces/submissions/execution-1/revise');assert.equal(s.saved(),0);
 const revised={...old,revised:true,targets:[{...old.targets[0],steps:[...old.targets[0].steps.slice(0,2),{status:'FAILED',code:'NOT_EXECUTED',label:'재고'}]}]};
 s.setItems([revised]);s.writes[0].resolve(revised);await flush();
 assert.equal(input.value,'작성 중인 값');assert.equal(s.saved(),0);assert.equal(s.reads.length,2);
 assert.doesNotMatch(s.get('common-submission-list').innerHTML,/data-submission-action="(?:revise|retry)"/);
 assert.match(s.get('common-submission-list').innerHTML,/성공/);assert.match(s.get('common-submission-list').innerHTML,/실패/);
 assert.deepEqual(s.busy,[true,false]);
 s.action('revise');assert.equal(s.writes.length,1);
});
test('revision failure retains input and permits an explicit recovery retry',async()=>{
 const s=setupUI([partial()]);await flush();s.action('revise');s.writes[0].reject(Error('충돌'));await flush();
 assert.equal(s.get('common-field-common-name').value,'작성 중인 값');assert.equal(s.get('common-submission-error').textContent,'충돌');
 s.action('revise');assert.equal(s.writes.length,2);
});
test('uncertain accepted running and already revised executions cannot release their locks from shared UI',async()=>{
 for(const blocked of [execution('UNKNOWN','FAILED','UNKNOWN'),execution('PARTIAL','FAILED','ACCEPTED'),execution('RUNNING','FAILED','RUNNING'),{...partial(),revised:true}]){
  const s=setupUI([{...blocked,id:'execution-1'}]);await flush();
  assert.doesNotMatch(s.get('common-submission-list').innerHTML,/data-submission-action="revise"/);
  s.action('revise');assert.equal(s.writes.length,0);
 }
});
test('revision invalidates prepared confirmation and permits a fresh preparation without reloading inputs',async()=>{
 const s=setupUI([partial()]);await flush();s.click('common-market-save');await flush();
 s.writes[0].resolve({id:'preview-old',draftId:s.draft.id,revision:2,executable:true,targets:[]});await flush();assert.equal(s.get('common-submission-dialog').open,true);
 const revised={...partial(),revised:true};s.setItems([revised]);s.action('revise');s.writes[1].resolve(revised);await flush();
 assert.equal(s.get('common-submission-dialog').open,false);s.click('common-submission-confirm');assert.equal(s.writes.length,2);
 s.click('common-market-save');await flush();assert.equal(s.writes[2].url,'/api/marketplaces/submissions/prepare');assert.equal(s.saved(),2);
});
test('a late revision response cannot overwrite the next draft history or confirmation',async()=>{
 const s=setupUI([partial()]);await flush();s.action('revise');
 s.setItems([]);s.ui.draft({id:'draft-2',revision:4});await flush();
 s.writes[0].resolve({...partial(),revised:true});await flush();
 assert.doesNotMatch(s.get('common-submission-list').innerHTML,/execution-1/);assert.equal(s.reads.length,2);assert.deepEqual(s.busy,[true,false]);
});
