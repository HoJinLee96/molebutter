const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function fixture(){
 const elements=new Map(),events={},calls=[];
 const element=()=>({value:'',textContent:'',innerHTML:'',disabled:false,focus(){}});
 const dialog={...element(),open:false,setAttribute(){},querySelector(selector){if(!elements.has(selector))elements.set(selector,element());return elements.get(selector);},addEventListener(name,fn){events[name]=fn;},showModal(){this.open=true;},close(){this.open=false;}};
 const window={addEventListener(){}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/naver-brand-picker.js','utf8'),{window,FormActions:{bindEnterSubmit(form,button){assert.equal(form,dialog.querySelector('form'));assert.equal(button,dialog.querySelector('button[type=submit]'));}},AbortController,AppUI:{escape:v=>String(v).replaceAll('<','&lt;')},document:{createElement:()=>dialog,body:{append(){}}},apiGet:(url,options)=>new Promise((resolve,reject)=>calls.push({url,options,resolve,reject}))});
 const submit=()=>dialog.querySelector('form').onsubmit({preventDefault(){}}),settle=()=>new Promise(resolve=>setImmediate(resolve));
 const choose=index=>dialog.querySelector('[data-naver-brand-results]').onclick({target:{closest:()=>({dataset:{naverBrandIndex:String(index)}})}});
 return {picker:window.NaverBrandPicker,dialog,calls,submit,settle,choose};
}
test('SmartStore picker searches its own endpoint and preserves string IDs on selection',async()=>{
 const f=fixture();let selected;f.picker.open({name:'닥스 & 브랜드',onSelect:v=>selected=v});f.submit();
 assert.equal(f.calls[0].url,'/api/marketplaces/naver/metadata/brands?name='+encodeURIComponent('닥스 & 브랜드'));
 f.calls[0].resolve([{id:'9007199254740993',name:'닥스'}]);await f.settle();f.choose(0);
 assert.deepEqual(JSON.parse(JSON.stringify(selected)),{id:'9007199254740993',name:'닥스'});assert.equal(f.dialog.open,false);
});
test('closing and reopening cancels the previous search and ignores its late result',async()=>{
 const f=fixture();f.picker.open({name:'이전',onSelect(){assert.fail('stale selection');}});f.submit();f.picker.close();
 assert(f.calls[0].options.signal.aborted);f.picker.open({name:'다음',onSelect(){}});f.submit();f.calls[0].resolve([{id:'1',name:'이전'}]);await f.settle();
 assert.equal(f.dialog.querySelector('[data-naver-brand-results]').innerHTML,'');assert(f.dialog.querySelector('button[type=submit]').disabled);
 f.calls[1].resolve([{id:'2',name:'다음'}]);await f.settle();assert(f.dialog.querySelector('[data-naver-brand-results]').innerHTML.includes('다음'));
});
test('empty results, errors and invalid numeric IDs are not selectable',async()=>{
 const f=fixture();let selected=false;f.picker.open({name:'없음',onSelect(){selected=true;}});f.submit();f.calls[0].resolve([]);await f.settle();
 assert.equal(f.dialog.querySelector('[data-naver-brand-status]').textContent,'검색 결과가 없습니다.');f.choose(0);assert.equal(selected,false);
 f.submit();f.calls[1].reject(new Error('권한 확인 필요'));await f.settle();assert.equal(f.dialog.querySelector('[data-naver-brand-status]').textContent,'권한 확인 필요');
 f.submit();f.calls[2].resolve([{id:9007199254740992,name:'잘못된 ID'}]);await f.settle();f.choose(0);assert.equal(selected,false);assert.equal(f.dialog.querySelector('[data-naver-brand-status]').textContent,'브랜드 검색 결과를 확인해 주세요.');
});
