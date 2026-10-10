const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const M=require('../../../molebutter-app/src/main/resources/static/js/marketplace-editor-model.js');
const S=require('../../../molebutter-app/src/main/resources/static/js/marketplace-submissions.js');
const flush=async()=>{for(let n=0;n<12;n++)await Promise.resolve();};
function draft(){const d=M.fresh();d.basic.sellerProductId='1';const o=d.options[0];o.sellerProductItemId='1001';o.itemName='브라운 FREE';o.separateCurrentChanges=true;o.current={salePrice:100,amountInStock:2};o.attributes=[{name:'Manufacturer Part Number',value:'MPN',exposed:'NONE'}];return d;}
function execution(status='FAILED',states=['FAILED']){return {id:'execution',status,revised:false,targets:[{steps:states.map((status,n)=>({id:String(n),label:'단계 '+n,status,message:status==='FAILED'?'합성 거절':''}))}]};}
function harness({initial=draft(),stored=null,get,post}={}){
 const nodes=new Map(),storage=new Map(),requests=[],locks=[];let edited=structuredClone(initial),saved=0,focus=0;
 if(stored)storage.set('coupang-save-1',stored);
 const node=id=>{if(!nodes.has(id))nodes.set(id,{innerHTML:'',hidden:true,disabled:false,textContent:'',listeners:{},querySelectorAll:()=>[],querySelector:()=>({focus(){focus++;}}),scrollIntoView(){},addEventListener(type,fn){this.listeners[type]=fn;},showModal(){this.open=true;},close(){this.open=false;}});return nodes.get(id);};
 const context={AppUI:{$:node,escape:v=>String(v??''),uuid:()=> 'idempotent'},window:{MarketplaceSubmissionUI:S,CoupangEditorModel:M,addEventListener(){}},sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)},structuredClone,Date,setTimeout:()=>1,clearTimeout(){},apiGet:async url=>{requests.push({method:'GET',url});return get?get(url):execution();},apiPost:async(url,body)=>{requests.push({method:'POST',url,body});if(post)return post(url,body);if(url.endsWith('/revise'))return {...execution(),revised:true};if(url.endsWith('/execute'))return execution();return {id:'preview',executable:true,targets:[{steps:[],changes:[]}]};}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/marketplace-editor-save.js','utf8'),context);
 const save=context.window.CoupangEditorSave.create({productId:'1',value:()=>edited,busy:v=>locks.push(v),saved:async()=>{saved++;return {observation:{token:'new'},draft:initial};}});
 save.observe({token:'original',expiresAt:'2099-01-01T00:00:00Z'},initial);
 const action=name=>node('editor-save-results').listeners.click({target:{closest:()=>({dataset:{saveAction:name}})}});
 return {save,node,action,requests,locks,storage,get edited(){return edited;},get saved(){return saved;},get focus(){return focus;}};
}
async function submit(h){await h.save.prepare();await h.node('editor-save-confirm').listeners.click();}
test('confirmed rejection reopens the same input with trusted original baseline and a fresh preview',async()=>{
 const h=harness();h.edited.options[0].attributes[0].value='';await submit(h);
 assert(h.node('editor-save-results').innerHTML.includes('data-save-action="revise"'));assert.equal(h.locks.at(-1),true);
 await h.action('revise');assert.equal(h.edited.options[0].attributes[0].value,'');assert.equal(h.saved,0);assert.equal(h.locks.at(-1),false);assert.equal(h.focus,1);
 assert(!h.node('editor-save-results').innerHTML.includes('data-save-action="retry"'));assert(h.node('editor-save-results').innerHTML.includes('합성 거절'));
 h.edited.options[0].attributes[0].value='MPN-FIXED';h.save.changed();await h.save.prepare();
 const preparation=h.requests.filter(r=>r.url.endsWith('/save-preparation')).at(-1);assert.equal(preparation.body.token,'original');assert.equal(preparation.body.changes[0].value[0].value,'MPN-FIXED');assert(!h.requests.some(r=>r.url.endsWith('/retry')));
});
test('partial success retains proposals and original observation so server can skip success and detect conflicts',async()=>{
 const partial=execution('PARTIAL',['SUCCEEDED','FAILED','QUEUED']);const h=harness({post:async(url)=>url.endsWith('/execute')?partial:url.endsWith('/revise')?{...partial,revised:true,targets:[{steps:[partial.targets[0].steps[0],partial.targets[0].steps[1],{...partial.targets[0].steps[2],status:'FAILED',code:'NOT_EXECUTED'}]}]}:{id:'preview',executable:true,targets:[]}});
 h.edited.options[0].currentChanges={salePrice:'0',amountInStock:'3'};h.edited.basic.sellerProductName='입력 보존';await submit(h);await h.action('revise');await h.save.prepare();
 assert.equal(h.saved,0);assert.equal(h.edited.options[0].current.salePrice,100);assert.equal(h.edited.options[0].currentChanges.salePrice,'0');
 const body=h.requests.filter(r=>r.url.endsWith('/save-preparation')).at(-1).body;assert.equal(body.token,'original');assert.deepEqual(Array.from(body.changes.filter(c=>c.path.startsWith('options.')),c=>[c.path,c.sellerProductItemId,c.value]),[['options.price','1001','0'],['options.quantity','1001','3']]);
 assert.equal(h.locks.at(-1),false);assert(h.node('editor-save-results').innerHTML.includes('미실행'));
});
test('unknown, accepted, running and queued executions cannot reopen or forge a revise request',async()=>{
 for(const value of [execution('UNKNOWN',['UNKNOWN']),execution('PARTIAL',['FAILED','UNKNOWN']),execution('PARTIAL',['FAILED','ACCEPTED']),execution('RUNNING',['FAILED','RUNNING']),execution('QUEUED',['QUEUED'])]){
  const h=harness({stored:'execution',get:async()=>value});await flush();assert(!h.node('editor-save-results').innerHTML.includes('data-save-action="revise"'));await h.action('revise');assert(!h.requests.some(r=>r.method==='POST'));assert.equal(h.locks.at(-1),true);
 }
});
test('revision failure preserves values and keeps retry available; repeated click sends one revision',async()=>{
 let release;const h=harness({stored:'execution',post:()=>new Promise(r=>{release=r;})});await flush();h.edited.options[0].attributes[0].value='KEPT';const pending=h.action('revise');await h.action('revise');assert.equal(h.requests.filter(r=>r.url.endsWith('/revise')).length,1);release({...execution(),revised:true});await pending;assert.equal(h.edited.options[0].attributes[0].value,'KEPT');assert.equal(h.locks.at(-1),false);
 const failed=harness({stored:'execution',post:async()=>{throw Error('복구 요청 실패');}});await flush();failed.edited.options[0].attributes[0].value='KEEP-FAIL';await failed.action('revise');assert.equal(failed.edited.options[0].attributes[0].value,'KEEP-FAIL');assert.equal(failed.locks.at(-1),true);assert.equal(failed.node('editor-save-error').textContent,'복구 요청 실패');
});
test('a stale result refresh cannot lock the editor after revision, and reentry honors revised record',async()=>{
 let release,calls=0;const h=harness({stored:'execution',get:()=>++calls===1?execution():new Promise(r=>{release=r;})});await flush();const pending=h.action('refresh');await h.action('revise');release(execution());await pending;assert.equal(h.locks.at(-1),false);assert(!h.node('editor-save-results').innerHTML.includes('data-save-action="revise"'));
 const reentry=harness({stored:'execution',get:async()=>({...execution(),revised:true})});await flush();assert.equal(reentry.locks.at(-1),false);assert(!reentry.node('editor-save-results').innerHTML.includes('data-save-action="retry"'));
});
test('expired original observation blocks new transmission after revision while preserving edits',async()=>{
 const h=harness({stored:'execution'});h.save.observe({token:'expired',expiresAt:'2000-01-01T00:00:00Z'},draft());await flush();h.edited.options[0].attributes[0].value='KEPT';await h.action('revise');await h.save.prepare();assert.equal(h.edited.options[0].attributes[0].value,'KEPT');assert(!h.requests.some(r=>r.url.endsWith('/save-preparation')));assert.match(h.node('editor-save-error').textContent,/만료/);
});
test('new tab restores the first execution with an allowed page size and blocks writes until history resolves',async()=>{
 let release;const h=harness({get:url=>new Promise(resolve=>{release=resolve;})});h.save.observe({token:'original',expiresAt:'2099-01-01T00:00:00Z',draftId:'draft'},draft());assert(h.requests.at(-1).url.endsWith('&size=10'));assert.equal(h.locks.at(-1),true);h.edited.options[0].attributes[0].value='CHANGED';await h.save.prepare();assert(!h.requests.some(r=>r.method==='POST'));release({items:[execution('UNKNOWN',['UNKNOWN'])]});await flush();assert(h.node('editor-save-results').innerHTML.includes('data-save-action="reconcile"'));assert.equal(h.storage.get('coupang-save-1'),'execution');assert.equal(h.locks.at(-1),true);
});
test('empty execution history permits new changes while a failed history lookup preserves input and blocks writes',async()=>{
 const empty=harness({get:async()=>({items:[]})});empty.save.observe({token:'original',expiresAt:'2099-01-01T00:00:00Z',draftId:'draft'},draft());await flush();assert.equal(empty.locks.at(-1),false);empty.edited.options[0].attributes[0].value='NEW';await empty.save.prepare();assert(empty.requests.some(r=>r.url.endsWith('/save-preparation')));
 const failed=harness({get:async()=>{throw Error('합성 이력 조회 실패');}});failed.save.observe({token:'original',draftId:'draft'},draft());await flush();assert.equal(failed.locks.at(-1),true);assert.equal(failed.node('editor-save-error').textContent,'합성 이력 조회 실패');assert.equal(failed.edited.options[0].attributes[0].value,'MPN');
});
test('history from an older observation cannot replace a newer execution view',async()=>{
 let release;const h=harness({get:url=>url.includes('draftId=old')?new Promise(r=>release=r):Promise.resolve({items:[{...execution(),revised:true,id:'latest'}]})});h.save.observe({token:'old',draftId:'old'},draft());h.save.observe({token:'new',draftId:'new'},draft());await flush();release({items:[execution('UNKNOWN',['UNKNOWN'])]});await flush();assert.equal(h.storage.get('coupang-save-1'),'latest');assert.equal(h.locks.at(-1),false);
});
