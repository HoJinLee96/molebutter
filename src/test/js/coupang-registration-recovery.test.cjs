const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const M=require('../../../molebutter-app/src/main/resources/static/js/marketplace-editor-model.js');
const S=require('../../../molebutter-app/src/main/resources/static/js/marketplace-submissions.js');
const flush=async()=>{for(let n=0;n<12;n++)await Promise.resolve();};
const execution=(status='FAILED',step='FAILED',extra={})=>({id:'execution',revision:1,status,revised:false,targets:[{market:'COUPANG',steps:[{status:step,code:'HTTP_400_REJECTED',label:'등록',message:'합성 입력 거절'}]}],...extra});
function harness({value=execution(),get,post}={}){
 const nodes=new Map(),requests=[],locks=[],storage=new Map(),draft=M.fresh();draft.basic.sellerProductName='보존할 상품명';let savedCount=0,revision=1;
 const node=id=>{if(!nodes.has(id))nodes.set(id,{innerHTML:'',hidden:true,textContent:'',listeners:{},querySelectorAll:()=>[],addEventListener(t,fn){this.listeners[t]=fn;},showModal(){this.open=true;},close(){this.open=false;}});return nodes.get(id);};
 const draftValue=()=>({id:'draft',revision,blocked:!value.revised,input:structuredClone(draft)});
 const context={AppUI:{$:node,escape:v=>String(v??''),uuid:()=> 'new-key'},window:{MarketplaceSubmissionUI:S,CoupangEditorModel:M,addEventListener(){}},URLSearchParams,location:{search:'?draftId=draft'},history:{replaceState(){}},structuredClone,Date,setTimeout:()=>1,clearTimeout(){},sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},apiGet:async url=>{requests.push({method:'GET',url});if(get)return get(url,draftValue);return url.includes('/submissions?')?{items:[value]}:url.includes('/submissions/')?value:draftValue();},apiRequest:async(url,options)=>{requests.push({method:options.method,url,body:JSON.parse(options.body)});revision++;return {...draftValue(),blocked:false};},apiPost:async(url,body)=>{requests.push({method:'POST',url,body});if(post)return post(url,body);if(url.endsWith('/revise'))return {...value,revised:true};return {id:'preview',draftId:'draft',revision,executable:true,targets:[{mode:'CREATE'}]};}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-product-registration.js','utf8'),context);
 const save=context.window.CoupangProductRegistration.create({value:()=>draft,busy:x=>locks.push(x),saved:()=>savedCount++});
 const action=name=>node('editor-save-results').listeners.click({target:{closest:()=>({dataset:{registrationAction:name}})}});
 return {save,draft,node,requests,locks,action,get savedCount(){return savedCount;}};
}
test('confirmed rejected registration can revise, retain input, persist correction and prepare a new snapshot',async()=>{
 const h=harness();await h.save.load();assert(h.save.blocked());assert.match(h.node('editor-save-results').innerHTML,/data-registration-action="revise"/);
 await h.action('revise');assert(!h.save.blocked());assert.equal(h.draft.basic.sellerProductName,'보존할 상품명');assert.equal(h.savedCount,0);
 h.draft.basic.sellerProductName='수정한 상품명';await h.save.prepare();const write=h.requests.find(r=>r.method==='PUT');assert.equal(write.body.input.basic.sellerProductName,'수정한 상품명');const preview=h.requests.find(r=>r.url.endsWith('/prepare'));assert.equal(preview.body.revision,2);assert(!h.requests.some(r=>r.url.endsWith('/retry')||r.url.endsWith('/execute')));
});
test('reopening a revised registration unlocks input without creating or repeating an external write',async()=>{
 const h=harness({value:execution('FAILED','FAILED',{revised:true})});await h.save.load();assert(!h.save.blocked());assert(!h.node('editor-save-results').innerHTML.includes('data-registration-action="retry"'));assert(!h.requests.some(r=>r.method==='POST'));
});
test('unknown, accepted, succeeded or externally linked registrations cannot revise or prepare CREATE again',async()=>{
 for(const value of [execution('UNKNOWN','UNKNOWN'),execution('ACCEPTED','ACCEPTED'),execution('RUNNING','RUNNING'),execution('QUEUED','QUEUED'),execution('SUCCEEDED','SUCCEEDED'),execution('FAILED','FAILED',{targets:[{market:'COUPANG',externalProductId:'123',steps:[{status:'FAILED'}]}]})]){
  const h=harness({value});await h.save.load();assert(h.save.blocked());assert(!h.node('editor-save-results').innerHTML.includes('data-registration-action="revise"'));await h.action('revise');await h.save.prepare();assert(!h.requests.some(r=>r.method==='POST'||r.method==='PUT'));
 }
});
test('revision prevents duplicate submission and ignores a result read started before the revision',async()=>{
 let resolveRead,resolvePost;const h=harness({get:(url,draft)=>url.includes('/submissions?')?{items:[execution()]}:url.includes('/submissions/')?new Promise(r=>resolveRead=r):draft(),post:()=>new Promise(r=>resolvePost=r)});await h.save.load();const read=h.action('refresh'),revise=h.action('revise');await h.action('revise');assert.equal(h.requests.filter(r=>r.url.endsWith('/revise')).length,1);resolvePost({...execution(),revised:true});await revise;resolveRead(execution());await read;assert(!h.save.blocked());assert(!h.node('editor-save-results').innerHTML.includes('data-registration-action="retry"'));
});
test('revision request failure preserves input and the existing lock',async()=>{
 const h=harness({post:async()=>{throw Error('합성 복구 실패');}});await h.save.load();await h.action('revise');assert(h.save.blocked());assert.equal(h.draft.basic.sellerProductName,'보존할 상품명');assert.equal(h.node('editor-save-error').textContent,'합성 복구 실패');
});
