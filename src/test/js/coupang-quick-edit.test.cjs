'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function setup(storage=new Map()){
 const nodes={},posts=[],gets=[];let applied=0,prepareHook=null,executeHook=null,openHook=null,uuid=0;
 const $=id=>nodes[id]??={value:'',disabled:false,hidden:false,innerHTML:'',textContent:'',listeners:{},open:false,
  addEventListener(type,fn){this.listeners[type]=fn;},setAttribute(){},showModal(){this.open=true;},close(){this.open=false;this.listeners.close?.();},reset(){['quick-edit-cost','quick-edit-value','quick-edit-stock-option'].forEach(id=>$(id).value='');},querySelectorAll(selector){return id==='quick-edit-form'?['quick-edit-cost','quick-edit-value','quick-edit-stock-option','quick-edit-prepare'].map($):[];}};
 const context={entry:{accountKey:'account',sessionId:'session',workingCopyId:'copy',revision:1,appliedRevision:0},optionId:'option',currentValue:143000,pendingValue:null,currentInventory:{salePrice:143000,amountInStock:49},registry:{state:{version:10},revision:1,blocked:false,conflicts:[]},source:{productCode:'CODE',purchaseCost:115170,stocks:[{id:'one',quantity:144,previous:true}],stockOptionCount:1,fingerprint:'source'},settings:{revision:2,shippingCost:4000,commissionPercent:10.5,marginPercent:7},calculation:{suggestedPrice:147000}};
 const ctx={window:{},AppUI:{normalizeCoupangPrice:require('../../../molebutter-app/src/main/resources/static/js/app-ui.js').normalizeCoupangPrice,coupangOptionLink:require('../../../molebutter-app/src/main/resources/static/js/app-ui.js').coupangOptionLink,$,escape:String,stamp:String,uuid:()=>`key-${++uuid}`},FormActions:{bindExplicitSubmit(form,button,fn){button.listeners.click=()=>button.disabled?undefined:fn();}},setError(id,message){$(id).textContent=message||'';$(id).hidden=!message;},sessionStorage:{getItem:k=>storage.get(k)??null,setItem(k,v){storage.set(k,v);},removeItem:k=>storage.delete(k)},setTimeout:()=>1,clearTimeout(){},console,URLSearchParams,
  async apiGet(url){gets.push(url);if(url.includes('/quick-edit?')){if(openHook)await openHook();return structuredClone(context);}if(url.includes('/submissions/'))return {id:'execution',status:'UNKNOWN',target:{steps:[]}};throw Error('Unexpected GET '+url);},
  async apiPost(url,body){posts.push({url,body});if(url.endsWith('/prepare')){if(prepareHook)return prepareHook(body);context.entry.revision++;context.pendingValue=body.value;return result(body);}
   if(url.endsWith('/execute')){if(executeHook)return executeHook(body);return {id:'execution',status:'UNKNOWN',target:{steps:[]}};}if(url.endsWith('/reconcile'))return {id:'execution',status:'SUCCEEDED',target:{steps:[]}};throw Error('Unexpected POST '+url);}};
 function result(body){return {entry:structuredClone(context.entry),registry:structuredClone(context.registry),preview:{id:'preview',executable:true,target:{steps:[{type:body.kind,optionId:'option'}],issues:[],changes:[{path:body.kind==='PRICE'?'salePrice':'amountInStock',before:String(context.currentValue),after:String(body.value)}]}}};}
 vm.createContext(ctx);vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-pricing.js','utf8'),ctx);ctx.CoupangPricing=ctx.window.CoupangPricing;
 vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-quick-edit.js','utf8'),ctx);const modal=ctx.window.CoupangQuickEdit.create({onApplied:async()=>applied++});
 return {$,posts,gets,storage,context,result,modal,get applied(){return applied;},set prepare(fn){prepareHook=fn;},set execute(fn){executeHook=fn;},set opening(fn){openHook=fn;},async open(kind='PRICE'){context.currentValue=kind==='PRICE'?context.currentInventory.salePrice:context.currentInventory.amountInStock;await modal.open('123','222','42',kind);},async save(){await $('quick-edit-prepare').listeners.click();},input(id,value){$(id).value=value;$(id).listeners.input?.();}};
}
test('single save prepares and executes only selected value with displayed delta; UNKNOWN blocks another save',async()=>{
 const s=setup();await s.open();assert.equal(s.$('quick-edit-delta').textContent,'143,000원 → 147,000원');assert(s.$('quick-edit-identity').innerHTML.includes('CODE'));assert.equal((s.$('quick-edit-source').innerHTML.match(/<p>/g)||[]).length,1);await s.save();assert.equal(s.posts.length,2);assert(s.posts[0].url.endsWith('/prepare'));assert.equal(s.posts[0].body.kind,'PRICE');assert.equal(s.posts[0].body.value,147000);assert(s.posts[1].url.endsWith('/execute'));assert(s.$('quick-edit-prepare').disabled);await s.save();assert.equal(s.posts.length,2);assert(!s.$('quick-edit-preview').innerHTML.includes('quick-edit-confirm'));
});
test('cost changes recalculate price, manual prices remain until next cost change, blank or invalid cost preserves price',async()=>{
 const s=setup();await s.open();s.input('quick-edit-cost','154330');assert.equal(String(s.$('quick-edit-value').value),'196000');s.input('quick-edit-value','193000');assert(s.$('quick-edit-margin').innerHTML.includes('5.83%'));s.input('quick-edit-cost','');assert.equal(String(s.$('quick-edit-value').value),'193000');s.input('quick-edit-cost','-1');assert.equal(String(s.$('quick-edit-value').value),'193000');s.input('quick-edit-cost','115170');assert.equal(String(s.$('quick-edit-value').value),'147000');assert.equal(s.posts.length,0);
});
test('same value performs neither local save nor transmission; stock zero is a valid changed value',async()=>{
 const s=setup();await s.open('STOCK');s.input('quick-edit-value','49');await s.save();assert.equal(s.posts.length,0);s.input('quick-edit-value','0');await s.save();assert.equal(s.posts[0].body.value,0);assert.equal(s.posts[0].body.purchaseCost,null);assert.equal(s.posts.length,2);
});
test('changed before value or observation requires another click and preserves input',async()=>{
 const s=setup();await s.open();s.prepare=body=>{s.context.currentValue=145000;s.context.currentInventory.salePrice=145000;s.context.registry.state.version++;return s.result(body);};await s.save();assert.equal(s.posts.length,1);assert.equal(String(s.$('quick-edit-value').value),'147000');assert(s.$('quick-edit-delta').textContent.includes('145,000원'));assert(s.$('quick-edit-status').textContent.includes('다시 확인'));s.prepare=null;await s.save();assert.equal(s.posts.filter(p=>p.url.endsWith('/execute')).length,1);
});
test('conflicts and server issues stop execution and preserve the selected intent',async()=>{
 const s=setup();await s.open();s.prepare=body=>{const r=s.result(body);r.preview.executable=false;r.preview.target.issues=[{message:'충돌 선택 필요'}];r.registry.conflicts=[{key:'price',state:'CONFLICT',change:{optionId:'option',path:'options.registration.salePrice'},baseline:143000,current:145000,desired:147000}];return r;};await s.save();assert.equal(s.posts.length,1);assert(s.$('quick-edit-preview').innerHTML.includes('내 수정값 반영'));assert.equal(String(s.$('quick-edit-value').value),'147000');
});
test('prepare failure preserves price and does not retry automatically',async()=>{
 const s=setup();await s.open();s.input('quick-edit-value','193000');s.prepare=()=>{throw Error('설정 변경');};await s.save();assert.equal(s.posts.length,1);assert.equal(String(s.$('quick-edit-value').value),'193000');assert(s.$('quick-edit-error').textContent.includes('설정 변경'));
});
test('lost execute response survives modal and page reentry; recovery uses identical preview and key',async()=>{
 const storage=new Map(),s=setup(storage);await s.open();s.execute=()=>{throw Error('응답 유실');};await s.save();assert.equal(s.posts.length,2);const original=s.posts[1];assert(s.$('quick-edit-prepare').disabled);s.$('quick-edit-close').listeners.click();const reentered=setup(storage);await reentered.open();assert.equal(reentered.posts.length,0);assert(reentered.$('quick-edit-prepare').disabled);await reentered.$('quick-edit-result').listeners.click();assert.equal(reentered.posts.length,1);assert.equal(reentered.posts[0].url,original.url);assert.equal(reentered.posts[0].body.idempotencyKey,original.body.idempotencyKey);assert(reentered.$('quick-edit-prepare').disabled);
});
test('known execution is read on reentry even while registry is blocked; success only reconciles existing execution',async()=>{
 const storage=new Map(),s=setup(storage);await s.open();await s.save();s.$('quick-edit-close').listeners.click();const reentered=setup(storage);reentered.context.registry.blocked=true;await reentered.open();assert.equal(reentered.posts.length,0);assert(reentered.gets.some(url=>url.endsWith('/submissions/execution')));assert(reentered.$('quick-edit-prepare').disabled);await reentered.$('quick-edit-result').listeners.click();assert.equal(reentered.posts.length,1);assert(reentered.posts[0].url.endsWith('/execution/reconcile'));assert.equal(reentered.applied,1);assert(![...storage.keys()].some(k=>k.endsWith('.execution')));
});
test('explicit execution rejection releases receipt but preserves input for another review',async()=>{
 const s=setup();await s.open();s.execute=()=>{throw Object.assign(Error('revision 변경'),{status:409});};await s.save();assert.equal(s.posts.length,2);assert(!s.$('quick-edit-prepare').disabled);assert.equal(String(s.$('quick-edit-value').value),'147000');assert(![...s.storage.keys()].some(k=>k.endsWith('.execution')));
});
test('closed modal late preparation cannot execute, and unavailable recovery storage blocks execution',async()=>{
 const s=setup();await s.open();let resolve;s.prepare=body=>new Promise(r=>resolve=()=>r(s.result(body)));const saving=s.save();s.$('coupang-quick-edit').close();resolve();await saving;assert.equal(s.posts.length,1);
 const blocked=setup();await blocked.open();blocked.storage.set=()=>{throw Error('storage denied');};await blocked.save();assert.equal(blocked.posts.length,1);assert(blocked.$('quick-edit-error').textContent.includes('복구 정보'));
});
test('double click during preparation cannot create a second save or execution',async()=>{
 const s=setup();await s.open();let resolve;s.prepare=body=>new Promise(r=>resolve=()=>r(s.result(body)));const saving=s.save();await s.save();assert.equal(s.posts.length,1);resolve();await saving;assert.equal(s.posts.length,2);assert.equal(s.posts.filter(p=>p.url.endsWith('/execute')).length,1);
});
test('unexpected option or additional prepared steps cannot be transmitted by one save',async()=>{
 const s=setup();await s.open();s.prepare=body=>{const r=s.result(body);r.preview.target.steps.push({optionId:'other',type:'STOCK'});return r;};await s.save();assert.equal(s.posts.length,1);assert(s.$('quick-edit-status').textContent.includes('다시 확인'));assert.equal(String(s.$('quick-edit-value').value),'147000');
});

test('unavailable supplier proposes zero or single owned stock; multiple options remain blank and edits survive refresh',async()=>{
 for(const [owned,count,coupons,expected]of [[0,0,2,'0'],[5,1,1,'5'],[5,2,1,''],[5,1,2,'']]){
  const s=setup();Object.assign(s.context.source,{availability:'UNAVAILABLE',ownedQuantity:owned,ownedOptions:Array.from({length:count},(_,i)=>({color:'색상'+i,size:'FREE',quantity:i===0?owned:0})),couponOptionCount:coupons});
  await s.open('STOCK');assert.equal(String(s.$('quick-edit-value').value),expected);assert.equal(s.$('quick-edit-stock').hidden,true);assert.equal(s.$('quick-edit-owned').hidden,false);assert(s.$('quick-edit-owned').innerHTML.includes('보유 재고 '+owned+'개'));
  s.input('quick-edit-value','');await s.$('quick-edit-reload').listeners.click();assert.equal(String(s.$('quick-edit-value').value),'');assert.equal(s.posts.length,0);
 }
 const s=setup();Object.assign(s.context.source,{availability:'UNAVAILABLE',ownedQuantity:5,ownedOptions:[{color:'블랙',size:'FREE',quantity:5}],couponOptionCount:1});s.context.pendingValue=7;await s.open('STOCK');assert.equal(String(s.$('quick-edit-value').value),'7');
});

test('comma price inputs use canonical values and equal formatting never transmits',async()=>{const s=setup();await s.open();s.input('quick-edit-value',' 143,000 ');await s.save();assert.equal(s.posts.length,0);s.input('quick-edit-value','139,000');await s.save();assert.equal(s.posts[0].body.value,139000);});
