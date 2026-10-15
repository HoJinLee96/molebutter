const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function fixture(){
 const elements=new Map(),calls=[];const element=()=>({value:'',textContent:'',innerHTML:'',disabled:false,hidden:false,focus(){}});
 const dialog={...element(),open:false,setAttribute(){},querySelector(s){if(!elements.has(s))elements.set(s,element());return elements.get(s);},addEventListener(){},showModal(){this.open=true;},close(){this.open=false;}};
 const request=(url,options={})=>new Promise((resolve,reject)=>calls.push({url,options,resolve,reject}));
 const window={addEventListener(){},CoupangRead:{start(url){const controller=new AbortController();return {promise:request(url,{signal:controller.signal}),cancel:()=>controller.abort()};}}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/common-category-picker.js','utf8'),{window,FormActions:{bindEnterSubmit(form,button){assert.equal(form,dialog.querySelector('form'));assert.equal(button,dialog.querySelector('button[type=submit]'));}},AbortController,structuredClone,AppUI:{escape:v=>String(v).replaceAll('<','&lt;')},document:{createElement:()=>dialog,body:{append(){}}},apiGet:request});
 const settle=()=>new Promise(r=>setImmediate(r)),click=dataset=>dialog.querySelector('[data-common-category-providers]').onclick({target:{closest:()=>({dataset})}});
 const apply=()=>dialog.querySelector('[data-common-category-apply]').onclick(),submit=()=>dialog.querySelector('form').onsubmit({preventDefault(){}});
 const resolve=(call,rows,paged=false)=>call.resolve(paged?{items:rows.map(b=>({brandId:b.id,brandName:b.name})),page:1,hasNext:false}:rows);
 return {picker:window.CommonCategoryPicker,dialog,calls,settle,click,apply,submit,resolve};
}

const cp=[{id:'123',name:'가방 > 크로스백'},{id:'456',name:'가방 > 지갑'}],naver=[{id:'50001',name:'크로스백',wholeCategoryName:'패션 > 가방 > 크로스백',last:true},{id:'50002',name:'가방',wholeCategoryName:'패션 > 가방',last:false}];
const el=[{id:'20',name:'가방 > 크로스백',leaf:true}],lot=[{id:'CAT1',name:'가방',leaf:true}];
function answer(call){call.resolve(call.url.includes('coupang')?cp:call.url.includes('elevenst')?el:call.url.includes('lotteon')?{items:lot,page:0,hasNext:false}:naver);}
test('shared query loads all four enabled markets and applies only staged selections',async()=>{
 const f=fixture();let result;f.picker.open({name:'가방',onApply:v=>result=v});assert.equal(f.calls.length,4);assert(f.calls.some(c=>c.url.includes('lotteon/metadata/categories')));f.calls.forEach(answer);await f.settle();assert(!f.dialog.querySelector('[data-common-category-providers]').innerHTML.includes('50002'));for(const market of ['COUPANG','NAVER','ELEVENST','LOTTEON'])f.click({categoryMarket:market,categoryIndex:'0'});assert.equal(result,undefined);f.apply();assert.equal(result.selections.COUPANG.id,'123');assert.equal(result.selections.NAVER.id,'50001');assert.equal(result.selections.ELEVENST.id,'20');assert.equal(result.selections.LOTTEON.id,'CAT1');
});
test('late responses cannot replace a reopened dialog and paged Lotte queries refetch',async()=>{
 const f=fixture();f.picker.open({name:'가방',onApply(){}});const old=f.calls.slice();f.picker.close();f.picker.open({name:'123',onApply(){}});old.forEach(c=>c.resolve(cp));f.calls.slice(4).forEach(answer);await f.settle();let html=f.dialog.querySelector('[data-common-category-providers]').innerHTML;assert(html.includes('크로스백'));assert(!html.includes('456'));f.dialog.querySelector('[data-common-category-query]').value='지갑';f.submit();assert.equal(f.calls.length,9);answer(f.calls[8]);await f.settle();assert(f.dialog.querySelector('[data-common-category-providers]').innerHTML.includes('지갑'));
});
test('locked provider is preserved while Enter only searches other providers',async()=>{
 const f=fixture();let result;f.picker.open({name:'가방',locked:{COUPANG:true},selections:{COUPANG:{id:'OLD',name:'기존'}},onApply:v=>result=v});assert.equal(f.calls.length,3);assert(f.calls.every(c=>!c.url.includes('coupang')));f.calls.forEach(answer);await f.settle();f.submit();answer(f.calls[3]);await f.settle();assert.equal(result,undefined);f.click({categoryMarket:'COUPANG',categoryClear:''});f.click({categoryMarket:'NAVER',categoryIndex:'0'});f.apply();assert.equal(result.selections.COUPANG,undefined);assert.equal(result.selections.NAVER.id,'50001');
});
test('one provider failure preserves independent selections and retry refetches malformed data',async()=>{
 const f=fixture();let result;f.picker.open({name:'가방',onApply:v=>result=v});f.calls[0].resolve({wrong:[]});f.calls.slice(1).forEach(answer);await f.settle();f.click({categoryMarket:'NAVER',categoryIndex:'0'});f.click({categoryMarket:'LOTTEON',categoryIndex:'0'});f.click({categoryMarket:'COUPANG',categoryRetry:''});assert.equal(f.calls.length,5);answer(f.calls[4]);await f.settle();f.click({categoryMarket:'COUPANG',categoryIndex:'0'});f.apply();assert.equal(result.selections.COUPANG.id,'123');assert.equal(result.selections.NAVER.id,'50001');assert.equal(result.selections.LOTTEON.id,'CAT1');
});
