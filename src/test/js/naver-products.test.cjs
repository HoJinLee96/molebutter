const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../../../molebutter-app/src/main/resources/static/js/naver-products.js'),'utf8');
const key='molebutter.naver.navigation';
function setup(saved){
 const elements={},requests=[],storage=new Map(saved?[[key,JSON.stringify(saved)]]:[]);
 const get=id=>elements[id]??={value:id==='naver-products-size'?'20':'',listeners:{},disabled:false,hidden:false,textContent:'',innerHTML:'',addEventListener(type,fn){this.listeners[type]=fn;},querySelector(){return get('search');},querySelectorAll(){return [get('search'),get('naver-products-status'),get('naver-products-keyword'),get('naver-products-size')];}};
 const ctx={AppUI:{$:get,escape:String},URLSearchParams,sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},window:{scrollY:10,addEventListener(){},scrollTo(){}},FormActions:{bindEnterSubmit(form,button){assert.equal(form,get('naver-products-query'));assert.equal(button,get('search'));}},apiGet:url=>new Promise((resolve,reject)=>requests.push({url,resolve,reject}))};
 vm.runInNewContext(source,ctx);
 return {get,requests,storage,submit:()=>get('naver-products-query').listeners.submit({preventDefault(){}}),click:id=>get(id).listeners.click()};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
const result=(page=1,hasNext=true)=>({products:[],page,totalPages:3,hasNext});
const params=request=>new URL(request.url,'https://test').searchParams;
test('status search reaches API, locks controls and keeps submitted filter across pages',async()=>{
 const s=setup();assert.equal(s.requests.length,0);s.get('naver-products-status').value='SUSPENSION';s.get('naver-products-seller-code').value='SELLER';s.submit();
 assert.equal(params(s.requests[0]).get('statusType'),'SUSPENSION');assert.equal(params(s.requests[0]).get('sellerManagementCode'),'SELLER');assert(s.get('naver-products-status').disabled);
 s.requests[0].resolve(result());await flush();assert(!s.get('naver-products-status').disabled);
 s.get('naver-products-status').value='SALE';s.click('naver-products-next');assert.equal(params(s.requests[1]).get('statusType'),'SUSPENSION');assert.equal(params(s.requests[1]).get('page'),'2');
 s.requests[1].resolve(result(2));await flush();s.submit();assert.equal(params(s.requests[2]).get('page'),'1');assert.equal(params(s.requests[2]).get('statusType'),'SALE');
});
test('reset clears status and API failures preserve selection',async()=>{
 const s=setup();s.get('naver-products-status').value='OUTOFSTOCK';s.submit();s.requests[0].reject(Error('모의 조회 오류'));await flush();
 assert.equal(s.get('naver-products-status').value,'OUTOFSTOCK');assert.equal(s.get('naver-products-error').textContent,'모의 조회 오류');assert(!s.get('naver-products-status').disabled);
 s.click('naver-products-reset');assert.equal(s.get('naver-products-status').value,'');assert.equal(params(s.requests[1]).get('statusType'),'');assert.equal(params(s.requests[1]).get('page'),'1');
});
test('return from editor restores status; old saved navigation defaults to all',async()=>{
 const s=setup({page:2,query:{keyword:'9001',size:'50',statusType:'WAIT'},scroll:10});
 assert.equal(s.get('naver-products-status').value,'WAIT');assert.equal(params(s.requests[0]).get('statusType'),'WAIT');assert.equal(params(s.requests[0]).get('page'),'2');
 s.requests[0].resolve(result(2));await flush();s.get('marketplace-naver-panel').listeners.click({target:{closest:()=>true}});
 assert.equal(JSON.parse(s.storage.get(key)).query.statusType,'WAIT');
 const legacy=setup({page:1,query:{keyword:'',size:'20'}});assert.equal(params(legacy.requests[0]).get('statusType'),'');
});
