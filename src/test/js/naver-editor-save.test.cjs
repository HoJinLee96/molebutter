const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const S=require('../../../molebutter-app/src/main/resources/static/js/marketplace-submissions.js');
function execution(status='FAILED',states=['FAILED']){return {id:'execution',revision:1,status,revised:false,targets:[{market:'NAVER',steps:states.map(status=>({status,code:'HTTP_400',label:'save'}))}]};}
function harness({registration=false,value=execution(),post,get}={}){
 const nodes=new Map(),locks=[],requests=[],storage=new Map();let input={fields:{name:'invalid'},options:[],images:[]},refreshCount=0;
 const node=id=>{if(!nodes.has(id))nodes.set(id,{innerHTML:'',hidden:true,disabled:false,textContent:'',listeners:{},querySelectorAll:()=>[],addEventListener(type,fn){this.listeners[type]=fn},showModal(){},close(){}});return nodes.get(id)};
 const observation={token:'trusted',expiresAt:'2099-01-01T00:00:00Z',document:{fields:{name:'original'},options:[],images:[]},draftId:'draft'};
 const context={AppUI:{$:node,escape:v=>String(v??''),uuid:()=> 'key'},window:{MarketplaceSubmissionUI:S,NaverEditorModel:{fromEditor:d=>structuredClone(d),input:d=>structuredClone(d),spec:{},names:{},P:'',S:''},addEventListener(){}},sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)},location:{search:'?draftId=draft'},history:{replaceState(){}},URLSearchParams,structuredClone,Date,setTimeout:()=>1,clearTimeout(){},apiGet:async url=>{requests.push({method:'GET',url});if(get){const out=get(url);if(out!==undefined)return out;}if(url.endsWith('/observation'))return observation;if(url.includes('/product-registrations/drafts/'))return {id:'draft',revision:1,input:observation.document};if(url.includes('?draftId='))return {items:[value]};return value;},apiRequest:async(url,options)=>{requests.push({method:'PUT',url,body:JSON.parse(options.body)});return {id:'draft',revision:2,input:JSON.parse(options.body).input};},apiPost:async(url,body)=>{requests.push({method:'POST',url,body});if(post)return post(url,body);if(url.endsWith('/revise'))return {...value,revised:true};return {id:'preview',executable:true,targets:[]};}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/naver-product-editor-save.js','utf8'),context);
 const save=context.window.NaverEditorSave.create({productId:registration?null:'9001',value:()=>input,busy:v=>locks.push(v),saved(){},refresh:async()=>{refreshCount++;return observation;}});
 const action=name=>node('naver-results').listeners.click({target:{closest:()=>({dataset:{naverResult:name}})}});
 return {save,node,locks,requests,action,input,get refreshCount(){return refreshCount}};
}
for(const registration of [false,true])test(`HTTP400 recovery preserves input and allows a fresh ${registration?'registration':'update'} snapshot`,async()=>{
 const h=harness({registration});await h.save.load();assert.equal(h.locks.at(-1),true);assert(h.node('naver-results').innerHTML.includes('data-naver-result="revise"'));
 await h.action('revise');assert.equal(h.locks.at(-1),false);assert.equal(h.input.fields.name,'invalid');assert.equal(h.refreshCount,0);
 h.input.fields.name='corrected';await h.save.prepare();const request=h.requests.find(r=>r.method==='POST'&&r.url.endsWith('/prepare'));assert(request);
 if(registration){assert.equal(request.body.revision,2);assert.equal(h.requests.find(r=>r.method==='PUT').body.input.fields.name,'corrected');}
 else{assert.equal(request.body.token,'trusted');assert.equal(request.body.input.fields.name,'corrected');}
 assert(!h.requests.some(r=>r.url.endsWith('/retry')));
});
test('uncertain or active steps cannot be retired even by a forged revise action',async()=>{
 for(const value of [execution('UNKNOWN',['UNKNOWN']),execution('ACCEPTED',['ACCEPTED']),execution('PARTIAL',['FAILED','ACCEPTED']),execution('RUNNING',['RUNNING']),execution('QUEUED',['QUEUED'])]){
  const h=harness({value});await h.save.load();await h.action('revise');assert.equal(h.locks.at(-1),true);assert(!h.requests.some(r=>r.method==='POST'));
 }
});
test('failed recovery preserves proposals and a double click cannot submit twice',async()=>{
 let reject;const h=harness({post:()=>new Promise((_,no)=>{reject=no;})});await h.save.load();const pending=h.action('revise');await h.action('revise');assert.equal(h.requests.filter(r=>r.method==='POST').length,1);reject(Error('failed recovery'));await pending;assert.equal(h.input.fields.name,'invalid');assert.equal(h.locks.at(-1),true);
});
test('late result refresh cannot re-lock revised input and reopening honors retired intent',async()=>{
 let release;const h=harness({get:url=>url.endsWith('/execution')?new Promise(r=>{release=r;}):undefined});await h.save.load();const pending=h.action('refresh');await h.action('revise');release(execution());await pending;assert.equal(h.locks.at(-1),false);
 const reopened=harness({value:{...execution(),revised:true}});await reopened.save.load();assert.equal(reopened.locks.at(-1),false);
});
test('partial success in an update can be revised without discarding the original observation',async()=>{
 const h=harness({value:execution('PARTIAL',['SUCCEEDED','FAILED','QUEUED'])});await h.save.load();await h.action('revise');assert.equal(h.locks.at(-1),false);await h.save.prepare();assert.equal(h.requests.find(r=>r.url.endsWith('/prepare')).body.token,'trusted');assert.equal(h.refreshCount,0);
});
