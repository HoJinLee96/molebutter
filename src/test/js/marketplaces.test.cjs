const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function setup(){
 const elements={},requests=[];
 const get=id=>elements[id]??= {value:id==='marketplace-size'?'10':'',listeners:{},disabled:false,hidden:false,textContent:'',innerHTML:'',open:false,showModal(){this.open=true;},close(){this.open=false;this.listeners.close?.();},addEventListener(type,fn){this.listeners[type]=fn;},setAttribute(){},focus(){},querySelectorAll(){return [get('marketplace-size'),get('submit')];}};
 const ctx={AppUI:{$:get,escape:s=>String(s).replaceAll('<','&lt;').replaceAll('>','&gt;')},URL,URLSearchParams,document:{querySelector:()=>get('header')},window:{addEventListener(){}},apiGet:url=>new Promise((resolve,reject)=>requests.push({url,resolve,reject}))};
 vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../../../molebutter-app/src/main/resources/static/js/marketplaces.js'),'utf8'),ctx);
 return {get,requests,detail:id=>get('marketplace-rows').listeners.click({target:{closest:()=>({dataset:{marketplaceProduct:id}})}}),submit:()=>get('marketplace-query').listeners.submit({preventDefault(){}}),click:id=>get(id).listeners.click(),size:value=>{get('marketplace-size').value=value;get('marketplace-size').listeners.change();}};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
const page=(name,token='')=>({items:[{sellerProductId:'99999999999999999999',sellerProductName:name}],nextToken:token,hasNext:!!token});
test('manual query, opaque next/back tokens and size reset',async()=>{
 const s=setup();assert(s.get('header').innerHTML.indexOf('노출상품 ID')<s.get('header').innerHTML.indexOf('브랜드'));assert(s.get('header').innerHTML.indexOf('등록상품명')<s.get('header').innerHTML.indexOf('등록상태'));assert.equal(s.requests.length,0);s.submit();s.submit();assert.equal(s.requests.length,1);
 assert(!s.requests[0].url.includes('nextToken'));s.requests[0].resolve(page('<unsafe>','0007+/='));await flush();assert(s.get('marketplace-rows').innerHTML.includes('&lt;unsafe&gt;'));
 s.click('marketplace-next');assert.equal(new URL(s.requests[1].url,'http://test').searchParams.get('nextToken'),'0007+/=');s.requests[1].resolve(page('second'));await flush();assert(s.get('marketplace-next').disabled);
 s.click('marketplace-prev');assert(!s.requests[2].url.includes('nextToken'));s.requests[2].resolve(page('first','0007+/='));await flush();
 s.size('100');assert(s.get('marketplace-next').disabled);s.submit();assert.equal(s.requests[3].url,'/api/marketplaces/coupang/products?maxPerPage=100');
 s.requests[3].resolve({items:[],nextToken:'',hasNext:false});await flush();assert(s.get('marketplace-rows').innerHTML.includes('등록상품이 없습니다.'));
});
test('late response cannot overwrite newer query and failure remains retryable',async()=>{
 const s=setup();s.submit();s.size('50');s.submit();assert.equal(s.requests.length,2);
 s.requests[1].resolve(page('new'));await flush();s.requests[0].resolve(page('old'));await flush();assert(s.get('marketplace-rows').innerHTML.includes('new'));assert(!s.get('marketplace-rows').innerHTML.includes('old'));
 s.submit();s.requests[2].reject(Error('쿠팡 연결 설정이 필요합니다'));await flush();assert.equal(s.get('marketplace-error').hidden,false);assert.equal(s.get('submit').disabled,false);
 s.submit();s.requests[3].resolve({items:[],nextToken:null,hasNext:false});await flush();assert(s.get('marketplace-error').textContent.includes('응답'));
});

test('detail shows separate names, suppresses duplicates and ignores responses after close',async()=>{
 const s=setup();s.detail('123');s.detail('123');assert.equal(s.requests.length,1);assert.equal(s.requests[0].url,'/api/marketplaces/coupang/products/123');
 s.requests[0].resolve({product:{sellerProductId:'123',sellerProductName:'관리명'},displayProductName:'<노출명>',items:[{sellerProductItemId:'9',vendorItemId:'3000000000',itemName:'블랙',current:{salePrice:0,amountInStock:0,onSale:false},currentError:null}]});await flush();
 s.get('marketplace-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:'basic'}})}});
 assert(s.get('marketplace-detail-content').innerHTML.includes('0원'));assert(s.get('marketplace-detail-content').innerHTML.includes('0개'));assert(s.get('marketplace-detail-content').innerHTML.includes('판매 중지'));assert.equal(s.get('marketplace-detail-title').textContent,'관리명');s.get('marketplace-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:'basic'}})}});assert(s.get('marketplace-detail-content').innerHTML.includes('&lt;노출명&gt;'));
 s.get('marketplace-detail').close();s.detail('124');s.get('marketplace-detail').close();s.detail('125');
 s.requests[1].resolve({product:{sellerProductId:'124',sellerProductName:'old'},items:[]});await flush();assert(!s.get('marketplace-detail-content').innerHTML.includes('old'));
 s.requests[2].reject(Error('호출 제한'));await flush();assert(!s.get('marketplace-detail-retry').hidden);
 s.click('marketplace-detail-retry');s.requests[3].resolve({product:{sellerProductId:'125',sellerProductName:'new'},items:[]});await flush();assert(s.get('marketplace-detail-content').innerHTML.includes('new'));
});

test('submitted filters stay fixed during page navigation; reset clears them',async()=>{
 const s=setup();s.get('filter-sellerProductName').value='블랙 + &';s.get('filter-status').value='APPROVED';s.submit();
 assert.equal(new URL(s.requests[0].url,'https://test').searchParams.get('sellerProductName'),'블랙 + &');
 s.requests[0].resolve(page('first','next'));await flush();s.get('filter-sellerProductName').value='미제출';s.click('marketplace-next');
 assert.equal(new URL(s.requests[1].url,'https://test').searchParams.get('sellerProductName'),'블랙 + &');s.requests[1].resolve(page('second'));await flush();s.click('marketplace-reset');assert(!s.requests[2].url.includes('sellerProductName'));assert(!s.requests[2].url.includes('nextToken'));
});
test('tabs reuse data, preserve option ownership and isolate HTML',async()=>{
 const s=setup();s.detail('123');s.requests[0].resolve({product:{sellerProductId:'123',sellerProductName:'상품'},items:[{sellerProductItemId:'1',itemName:'첫 옵션',images:[{url:'https://cdn.test/one.jpg',type:'REPRESENTATION',order:1}],contents:[{type:'HTML',detailType:'TEXT',content:'<script>parent.bad()</script><form action="https://bad.test">x</form>'}],notices:[],settings:[]},{sellerProductItemId:'2',itemName:'다른 옵션',images:[{url:'https://cdn.test/two.jpg',type:'DETAIL',order:2}],contents:[]}]});await flush();
 const tab=key=>s.get('marketplace-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:key}})}});
 tab('images');assert(s.get('marketplace-detail-content').innerHTML.includes('one.jpg'));assert(!s.get('marketplace-detail-content').innerHTML.includes('two.jpg'));
 s.get('marketplace-option').value='1';s.get('marketplace-option').listeners.change();assert(s.get('marketplace-detail-content').innerHTML.includes('two.jpg'));
 s.get('marketplace-option').value='0';s.get('marketplace-option').listeners.change();tab('images');
 const html=s.get('marketplace-detail-content').innerHTML;assert(html.includes('sandbox=""'));assert(html.includes("script-src 'none'"));assert(!html.includes('<script>'));assert(html.includes('srcdoc='));assert.equal(s.requests.length,1);
});

test('detail retains registration date from the clicked list without another request',async()=>{
 const s=setup();s.submit();s.requests[0].resolve({items:[{sellerProductId:'123',sellerProductName:'상품',createdAt:'2026-10-05T09:00:00'}],nextToken:'',hasNext:false});await flush();
 s.detail('123');s.requests[1].resolve({product:{sellerProductId:'123',sellerProductName:'상품'},items:[]});await flush();assert(s.get('marketplace-detail-content').innerHTML.includes('2026-10-05 09:00:00'));assert.equal(s.requests.length,2);
});

test('description CDN HTTP images upgrade to HTTPS in both HTML and image contents',async()=>{
 const s=setup();s.detail('123');s.requests[0].resolve({product:{sellerProductId:'123',sellerProductName:'상품'},items:[{itemName:'옵션',contents:[{type:'HTML',detailType:'TEXT',content:'<img src="http://img1a.coupangcdn.com/image/html.jpg"><script>bad()</script>'},{type:'IMAGE',detailType:'IMAGE',content:'http://img1a.coupangcdn.com/image/direct.jpg'},{type:'IMAGE',detailType:'IMAGE',content:'http://untrusted.test/image.jpg'}]}]});await flush();
 s.get('marketplace-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:'images'}})}});
 const html=s.get('marketplace-detail-content').innerHTML;
 assert(html.includes('https://img1a.coupangcdn.com/image/html.jpg'));assert(html.includes('https://img1a.coupangcdn.com/image/direct.jpg'));
 assert(!html.includes('http://img1a.coupangcdn.com'));assert(!html.includes('<script>'));assert(html.includes('sandbox=""'));assert(html.includes('이미지 없음'));assert(!html.includes('src="http://untrusted.test'));assert.equal(s.requests.length,1);
});

test('relative description CDN paths resolve without using the application origin',async()=>{
 const s=setup();s.detail('123');s.requests[0].resolve({product:{sellerProductId:'123'},items:[{contents:[{type:'IMAGE',detailType:'IMAGE',content:'vendor_inventory/synthetic.jpg'},{type:'IMAGE',detailType:'IMAGE',content:'/image/vendor_inventory/root.jpg'}]}]});await flush();
 s.get('marketplace-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:'images'}})}});
 const html=s.get('marketplace-detail-content').innerHTML;
 assert(html.includes('https://img1a.coupangcdn.com/image/vendor_inventory/synthetic.jpg'));assert(html.includes('https://img1a.coupangcdn.com/image/vendor_inventory/root.jpg'));
});

test('purchase attributes stay with options and search attributes follow selected option in settings',async()=>{
 const s=setup();s.detail('123');s.requests[0].resolve({product:{sellerProductId:'123'},items:[{itemName:'블랙',attributes:[{name:'색상',value:'블랙',exposed:'EXPOSED'},{name:'소재',value:'가죽',exposed:'NONE'}]},{itemName:'화이트',attributes:[{name:'색상',value:'화이트',exposed:'EXPOSED'},{name:'소재',value:'면',exposed:'NONE'}]}]});await flush();
 let html=s.get('marketplace-detail-content').innerHTML;assert(html.includes('구매 속성'));assert(html.includes('색상: 블랙'));assert(!html.includes('가죽'));assert(!html.includes('구매·검색 속성'));
 s.get('marketplace-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:'settings'}})}});
 html=s.get('marketplace-detail-content').innerHTML;assert(html.includes('검색 속성'));assert(html.includes('가죽'));assert(!html.includes('색상'));
 s.get('marketplace-option').value='1';s.get('marketplace-option').listeners.change();html=s.get('marketplace-detail-content').innerHTML;assert(html.includes('<dd>면</dd>'));assert(!html.includes('가죽'));assert.equal(s.requests.length,1);
});
