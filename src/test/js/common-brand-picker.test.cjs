const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function fixture(){
 const elements=new Map(),calls=[];const element=()=>({value:'',textContent:'',innerHTML:'',disabled:false,hidden:false,focus(){}});
 const dialog={...element(),open:false,setAttribute(){},querySelector(s){if(!elements.has(s))elements.set(s,element());return elements.get(s);},addEventListener(){},showModal(){this.open=true;},close(){this.open=false;}};
 const request=(url,options={})=>new Promise((resolve,reject)=>calls.push({url,options,resolve,reject}));
 const window={addEventListener(){},CoupangRead:{start(url){const controller=new AbortController();return {promise:request(url,{signal:controller.signal}),cancel:()=>controller.abort()};}}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/common-brand-picker.js','utf8'),{window,FormActions:{bindEnterSubmit(form,button){assert.equal(form,dialog.querySelector('form'));assert.equal(button,dialog.querySelector('button[type=submit]'));}},AbortController,structuredClone,AppUI:{escape:v=>String(v).replaceAll('<','&lt;')},document:{createElement:()=>dialog,body:{append(){}}},apiGet:request});
 const settle=()=>new Promise(r=>setImmediate(r)),click=dataset=>dialog.querySelector('[data-common-brand-providers]').onclick({target:{closest:()=>({dataset})}});
 const apply=()=>dialog.querySelector('[data-common-brand-apply]').onclick(),submit=()=>dialog.querySelector('form').onsubmit({preventDefault(){}});
 const resolve=(call,rows,paged=false)=>call.resolve(paged?{items:rows.map(b=>({brandId:b.id,brandName:b.name})),page:1,hasNext:false}:rows);
 return {picker:{...window.CommonBrandPicker,open:options=>window.CommonBrandPicker.open({markets:['COUPANG','NAVER','LOTTEON'],...options})},dialog,calls,settle,click,apply,submit,resolve};
}
test('one query loads three independent catalogs and applies distinct names and IDs together',async()=>{
 const f=fixture();let result;f.picker.open({name:'닥스 & 브랜드',onApply:v=>result=v});assert.equal(f.calls.length,3);
 assert(f.calls.every(c=>c.url.includes(encodeURIComponent('닥스 & 브랜드'))));assert(f.calls[0].url.includes('/coupang/brands?'));assert(f.calls[1].url.includes('/naver/metadata/brands?'));assert(f.calls[2].url.includes('/lotteon/metadata/brands?'));
 f.resolve(f.calls[0],[{id:'KR-5',name:'DAKS'}],true);f.resolve(f.calls[1],[{id:'9007199254740993',name:'닥스'}]);f.resolve(f.calls[2],[{id:'0000012',name:'닥스(DAKS)'}]);await f.settle();
 for(const market of ['COUPANG','NAVER','LOTTEON'])f.click({brandMarket:market,brandIndex:'0'});assert.equal(result,undefined);f.apply();
 assert.deepEqual(JSON.parse(JSON.stringify(result)),{name:'닥스 & 브랜드',selections:{COUPANG:{id:'KR-5',name:'DAKS'},NAVER:{id:'9007199254740993',name:'닥스'},LOTTEON:{id:'0000012',name:'닥스(DAKS)'}}});assert.equal(f.dialog.open,false);
});
test('one failure keeps other results and choices; retry and Coupang paging affect only their provider',async()=>{
 const f=fixture();let result;f.picker.open({name:'닥스',onApply:v=>result=v});
 f.calls[0].resolve({items:[{brandId:'KR-5',brandName:'DAKS'}],page:1,hasNext:true});f.calls[1].reject(Error('네이버 권한 오류'));f.resolve(f.calls[2],[]);await f.settle();
 assert(f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('네이버 권한 오류'));assert(f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('최대 100개를 조회합니다.'));f.click({brandMarket:'COUPANG',brandIndex:'0'});f.click({brandMarket:'NAVER',brandRetry:''});assert.equal(f.calls.length,4);
 f.resolve(f.calls[3],[{id:'123',name:'닥스'}]);await f.settle();f.click({brandMarket:'NAVER',brandIndex:'0'});f.click({brandMarket:'COUPANG',brandPage:'2'});assert.equal(f.calls.length,5);assert(f.calls[4].url.endsWith('&page=2'));
 f.calls[4].resolve({items:[],page:2,hasNext:false});await f.settle();f.apply();assert.equal(result.selections.COUPANG.id,'KR-5');assert.equal(result.selections.NAVER.id,'123');
});
test('cancel and reopen ignore late responses and do not apply staged changes',async()=>{
 const f=fixture();let count=0;f.picker.open({name:'이전',onApply(){count++;}});const old=f.calls.slice();f.picker.close();assert(old.every(c=>c.options.signal.aborted));
 f.picker.open({name:'다음',onApply(){count++;}});for(const call of old)call.resolve([{id:'1',name:'늦은 이전 결과'}]);await f.settle();
 assert(!f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('늦은 이전 결과'));assert(f.dialog.querySelector('[data-common-brand-apply]').disabled);
 f.resolve(f.calls[3],[{id:'KR-2',name:'다음'}],true);f.resolve(f.calls[4],[]);f.resolve(f.calls[5],[]);await f.settle();f.click({brandMarket:'COUPANG',brandIndex:'0'});f.picker.close();assert.equal(count,0);
});
test('existing connected Coupang is locked and explicit clear changes only the chosen market',async()=>{
 const f=fixture();let result;f.picker.open({name:'닥스',locked:{COUPANG:true},selections:{COUPANG:{id:'KR-OLD',name:'DAKS'},NAVER:{id:'123',name:'닥스'}},onApply:v=>result=v});assert.equal(f.calls.length,2);
 f.resolve(f.calls[0],[]);f.resolve(f.calls[1],[]);await f.settle();f.click({brandMarket:'COUPANG',brandClear:''});f.click({brandMarket:'NAVER',brandClear:''});f.apply();
 assert.deepEqual(result.selections,{NAVER:null});
});
test('editing an unsearched query blocks apply and a malformed provider response is not selectable',async()=>{
 const f=fixture();let result;f.picker.open({name:'닥스',onApply:v=>result=v});f.resolve(f.calls[0],[],true);f.resolve(f.calls[1],[{id:123,name:'숫자 ID'}]);f.resolve(f.calls[2],[]);await f.settle();
 f.click({brandMarket:'NAVER',brandIndex:'0'});assert(f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('브랜드 검색 결과를 확인해 주세요.'));
 f.dialog.querySelector('[data-common-brand-query]').value='헤지스';f.dialog.querySelector('[data-common-brand-query]').oninput();f.apply();assert.equal(result,undefined);f.submit();assert.equal(f.calls.length,6);
 f.resolve(f.calls[3],[{id:'KR-H',name:'HAZZYS'}],true);f.resolve(f.calls[4],[]);f.resolve(f.calls[5],[]);await f.settle();f.click({brandMarket:'COUPANG',brandIndex:'0'});f.apply();assert.equal(result.name,'헤지스');assert.equal(result.selections.COUPANG.name,'HAZZYS');
});
test('11st only reads configured brand pages and missing CSV still permits explicit text choice',async()=>{
 const f=fixture();let result,closed=0;f.picker.open({markets:['ELEVENST'],name:'닥스',onApply:v=>result=v,onClose:()=>closed++});assert.equal(f.calls.length,1);assert(f.calls[0].url.includes('/elevenst/brands?'));
 f.calls[0].resolve({items:[],page:1,hasNext:false,available:false,warning:'공식 CSV 파일 필요'});await f.settle();assert(f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('공식 CSV 파일 필요'));assert(!f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('쿠팡'));
 f.click({brandMarket:'ELEVENST',brandText:''});assert.equal(result,undefined);f.apply();assert.equal(result.selections.ELEVENST.name,'닥스');assert.equal(result.selections.ELEVENST.id,'');assert.equal(closed,1);
});
test('11st managed-brand pagination failure preserves current rows and staged selection',async()=>{
 const f=fixture();let result;f.picker.open({markets:['ELEVENST'],name:'닥스',onApply:v=>result=v});f.calls[0].resolve({items:[{id:'189822',name:'DAKS'}],page:1,hasNext:true,available:true,warning:'CSV 조회'});await f.settle();f.click({brandMarket:'ELEVENST',brandIndex:'0'});f.click({brandMarket:'ELEVENST',brandPage:'2'});f.calls[1].reject(Error('모의 조회 실패'));await f.settle();assert(f.dialog.querySelector('[data-common-brand-providers]').innerHTML.includes('DAKS'));f.apply();assert.equal(result.selections.ELEVENST.id,'189822');
});
