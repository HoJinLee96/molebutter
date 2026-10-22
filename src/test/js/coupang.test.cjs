"use strict";
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const flush=()=>new Promise(r=>setImmediate(r));
const columns=fs.readFileSync('molebutter-app/src/main/resources/templates/coupang.html','utf8').match(/<colgroup>[\s\S]*?<\/colgroup>/)[0];
function setup(saved=null,dismissed=''){const elements={},reads=[],writes=[],bindings={},events={},timers=new Map(),bulkOpens=[],tableWrap={scrollLeft:saved?0:216};let navigation=saved,restoredScroll=0,timerSequence=0,bulkHooks;
 const get=id=>elements[id]??={value:id==='coupang-size'?'20':id==='coupang-sort'?'CREATED_DESC':id.endsWith('Comparison')?'EQ':'',listeners:{},hidden:false,disabled:false,innerHTML:'',textContent:'',open:false,dataset:{},
 addEventListener(t,f){this.listeners[t]=f;},closest(selector){return selector==='.table-wrap'?tableWrap:selector==='table'?{querySelector:()=>({outerHTML:columns})}:null;},setAttribute(k,v){this[k]=v;},removeAttribute(k){delete this[k];},focus(){},showModal(){this.open=true;},close(){this.open=false;this.listeners.close?.();},querySelector(){return get('submit');},querySelectorAll(selector){if(selector!=='[data-product-select]')return[];if(this.checkHtml!==this.innerHTML){this.checkHtml=this.innerHTML;this.checks=[...this.innerHTML.matchAll(/<input\b[^>]*data-product-select="([^"]+)"[^>]*>/g)].map(([tag,id])=>({dataset:{productSelect:id},checked:/\bchecked\b/.test(tag),disabled:/\bdisabled\b/.test(tag)}));}return this.checks;}};
 const ctx={AppUI:{coupangOptionLink:require('../../../molebutter-app/src/main/resources/static/js/app-ui.js').coupangOptionLink,$:get,stamp:v=>v,escape:s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;')},FormActions:{bindEnterSubmit(){},bindExplicitSubmit(form,button,handler){bindings[button===get('coupang-recent-start')?'recent':button===get('coupang-collect-all')?'all':button===get('coupang-collect-selected')?'selected':button===get('coupang-collect-cancel')?'cancel':'other']=handler;}},
 document:{addEventListener(type,handler){events[type]=handler;}},window:{CoupangBulkEdit:{create(hooks){bulkHooks=hooks;return {open(items,kind){bulkOpens.push({items,kind});}};}},scrollY:427,scrollTo(x,y){restoredScroll=y;},addEventListener(){}},URL,URLSearchParams,Set,console,setTimeout(fn,delay){const id=++timerSequence;timers.set(id,{fn,delay});return id;},clearTimeout(id){timers.delete(id);},sessionStorage:{getItem(key){return key.includes('dismissedCollection')?dismissed:navigation;},removeItem(key){if(key.includes('dismissedCollection'))dismissed='';else navigation=null;},setItem(key,value){if(key.includes('dismissedCollection'))dismissed=value;else navigation=value;}},apiGet:url=>new Promise((resolve,reject)=>reads.push({url,resolve,reject})),apiPost:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body,resolve,reject}))};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/table-selection.js','utf8'),ctx);vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/mall-tag.js','utf8'),ctx);vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/product-supplier-summary.js','utf8'),ctx);vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang.js','utf8'),ctx);
 return{get,reads,writes,bindings,timers,bulkOpens,applyBulk:item=>bulkHooks.onApplied(item),select(id,checked=true){get('coupang-rows').listeners.change({target:{dataset:{productSelect:id},checked}});},get dismissed(){return dismissed;},tick(){const [id,timer]=timers.entries().next().value;timers.delete(id);timer.fn();return timer.delay;},window:ctx.window,saveNavigation(){events.click({target:{closest:()=>true}});return navigation;},get scroll(){return restoredScroll;},get tableScroll(){return tableWrap.scrollLeft;},detail(id){get('coupang-rows').listeners.click({target:{closest:selector=>selector==='[data-coupang-product]'?({dataset:{coupangProduct:id}}):null}});},submit(){get('coupang-query').listeners.submit({preventDefault(){}});}};
}
const listing={items:[{product:{sellerProductId:'123',productId:'9729694729',sellerProductName:'상품 <script>'},lastSuccessAt:null,option:{vendorItemId:'222',settings:[{name:'externalVendorSku',value:'HJWA908BK'}],attributes:[{name:'색상',value:'검정',exposed:'EXPOSED'}],current:{salePrice:143000,amountInStock:51,onSale:false}},outboundShippingDays:2}],page:0,totalPages:1,totalElements:1,productCount:1,counts:{all:9,selling:4,soldOut:2,stopped:1,deleted:3}};
async function ready(s,job=null){assert(s.reads[0].url.endsWith('/brands'));s.reads.shift().resolve(['브랜드']);await flush();const r=s.reads.shift();assert(r.url.includes('page='));r.resolve(listing);await flush();s.reads.shift().resolve(job);await flush();}
test('DB option rows include stopped sales and escape names',async()=>{const s=setup();await ready(s);assert(s.get('coupang-rows').innerHTML.includes('HJWA908BK'));assert(s.get('coupang-rows').innerHTML.includes('판매 중지'));assert(s.get('coupang-rows').innerHTML.includes('&lt;script&gt;'));assert.equal(s.writes.length,0);});
test('edit link is the final column and deleted rows cannot open an editor',async()=>{
 const s=setup();await ready(s);
 assert.match(s.get('coupang-rows').innerHTML,/<td><a[^>]+data-coupang-editor-link[^>]+href="\/coupang\/products\/123\/edit"[^>]*>수정<\/a><\/td><\/tr>$/);
 s.submit();s.reads.shift().resolve({...listing,items:[{...listing.items[0],deleted:true}]});await flush();
 assert(!s.get('coupang-rows').innerHTML.includes('data-coupang-editor-link'));
 assert.equal(s.writes.length,0);
});
test('all requested filters use local products query with inclusive comparisons',async()=>{const s=setup();await ready(s);for(const [key,value]of Object.entries({id:'222',brand:'브랜드',name:'상품',sku:'908',stock:'70',stockComparison:'LE',saleStatus:'STOPPED',shippingDays:'2',shippingComparison:'GE'}))s.get('filter-'+key).value=value;s.submit();const query=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(query.get('id'),'222');assert.equal(query.get('sku'),'908');assert.equal(query.get('stockComparison'),'LE');assert.equal(query.get('shippingComparison'),'GE');assert.equal(query.get('saleStatus'),'STOPPED');assert.equal(s.writes.length,0);s.reads.shift().resolve(listing);await flush();});
test('collection is explicit and deletion tab only changes DB filtering',async()=>{const s=setup();await ready(s);s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'DELETED'}})}});assert(s.reads[0].url.includes('deleted=true'));s.reads.shift().resolve({...listing,items:[],productCount:0,totalElements:0});await flush();s.bindings.all();assert.equal(s.writes[0].url,'/api/coupang/products/collections');assert.deepEqual(Array.from(s.writes[0].body.sellerProductIds),[]);s.writes[0].resolve({id:'1',status:'QUEUED',mode:'FULL',discovered:0,completed:0,failed:0});await flush();});
test('five previous selections cannot leak into selected refresh after recommendation rows disappear',async()=>{
 const s=setup();await ready(s);s.submit();const items=Array.from({length:5},(_,n)=>({...listing.items[0],product:{...listing.items[0].product,sellerProductId:String(n+1)}}));s.reads.shift().resolve({...listing,items,totalElements:5});await flush();for(let n=1;n<=5;n++)s.select(String(n));assert.match(s.get('coupang-summary').textContent,/선택 5개/);
 const applying=s.applyBulk({product:'1'});s.reads.shift().resolve(listing);await applying;assert.match(s.get('coupang-summary').textContent,/선택 0개/);s.select('123');s.bindings.selected();assert.deepEqual(Array.from(s.writes[0].body.sellerProductIds),['123']);assert.match(s.get('coupang-summary').textContent,/선택 1개/);
});
test('page changes and a visibly unchecked row cannot submit an empty selection as full collection',async()=>{
 const s=setup();await ready(s);s.select('123');s.get('coupang-rows').querySelectorAll('[data-product-select]')[0].checked=false;await s.bindings.selected();assert.match(s.get('coupang-error').textContent,/현재 목록/);assert.equal(s.writes.length,0);
 s.select('123');s.get('coupang-next').listeners.click();s.reads.shift().reject(Error('조회 실패'));await flush();assert.match(s.get('coupang-summary').textContent,/선택 0개/);await s.bindings.selected();assert.match(s.get('coupang-error').textContent,/현재 목록/);assert.equal(s.writes.length,0);
});
const activeCollection={id:'7',mode:'FULL',status:'RUNNING',phase:'LIST',listComplete:false,discovered:100,completed:0,failed:0,updatedAt:'2026-10-19T12:00:00Z'};
test('collection status restores from DB with indeterminate discovery then processed success and failure counts',async()=>{
 const s=setup();await ready(s,activeCollection);assert.equal(s.get('coupang-collection-state').hidden,false);assert.equal(s.get('coupang-collection-status').textContent,'수집 중');assert.equal(s.get('coupang-collection-phase').textContent,'상품 목록 확인 중');assert(!('value' in s.get('coupang-collection-progress')));assert.match(s.get('coupang-collection-note').textContent,/전체 건수가 늘어날/);assert.equal(s.writes.length,0);
 assert.equal(s.tick(),1500);s.reads.shift().resolve({...activeCollection,phase:'DETAIL',listComplete:true,discovered:300,completed:10,failed:2});await flush();
 assert.equal(s.get('coupang-collection-progress').value,4);assert.match(s.get('coupang-collection-summary').textContent,/발견 300개 · 처리 12개 · 성공 10개 · 실패 2개/);assert.match(s.get('coupang-collection-note').textContent,/남은 상품 288개/);assert.equal(s.writes.length,0);
});
test('status polling failure preserves last metrics and manual retry is read only',async()=>{
 const s=setup();await ready(s,activeCollection);const prior=s.get('coupang-collection-summary').textContent;s.tick();s.reads.shift().reject(Error('연결 실패'));await flush();
 assert.equal(s.get('coupang-collection-summary').textContent,prior);assert.equal(s.get('coupang-collection-error').hidden,false);assert.equal(s.get('coupang-collection-retry').hidden,false);assert.equal([...s.timers.values()][0].delay,5000);
 s.get('coupang-collection-retry').listeners.click();assert.equal(s.reads.length,1);s.get('coupang-collection-retry').listeners.click();assert.equal(s.reads.length,1);
 s.reads.shift().resolve({...activeCollection,phase:'MISSING',listComplete:true,completed:40,failed:1});await flush();assert.equal(s.get('coupang-collection-error').hidden,true);assert.equal(s.get('coupang-collection-phase').textContent,'목록에서 누락된 상품 확인 중');assert.equal(s.writes.length,0);
});
test('in flight status response cannot overwrite a newer cancellation result',async()=>{
 const s=setup();await ready(s,activeCollection);s.tick();const old=s.reads.shift();s.bindings.cancel();s.writes.shift().resolve({...activeCollection,status:'CANCELLED',completed:5});await flush();
 old.resolve({...activeCollection,completed:1});await flush();assert.equal(s.get('coupang-collection-status').textContent,'취소');assert.match(s.get('coupang-collection-summary').textContent,/성공 5개/);assert.equal(s.timers.size,0);assert.equal(s.get('coupang-collect-resume').hidden,false);
});
test('initial status failure remains visible and can recover without starting collection',async()=>{
 const s=setup();s.reads.shift().resolve([]);await flush();s.reads.shift().resolve(listing);await flush();s.reads.shift().reject(Error('현황 실패'));await flush();assert.equal(s.get('coupang-collection-state').hidden,false);assert.equal(s.get('coupang-collection-retry').hidden,false);
 s.get('coupang-collection-retry').listeners.click();assert(s.reads[0].url.endsWith('/latest'));s.reads.shift().resolve(activeCollection);await flush();assert.equal(s.get('coupang-collect-all').disabled,true);assert.equal(s.writes.length,0);
});
test('finished collection keeps results visible and hides the progress bar',async()=>{
 for(const [status,label] of Object.entries({SUCCEEDED:'완료',PARTIAL:'일부 실패',FAILED:'실패',PAUSED:'일시 중단',INTERRUPTED:'중단',CANCELLED:'취소'})){
  const s=setup();await ready(s,{...activeCollection,status,phase:'DONE',listComplete:true,completed:98,failed:2,error:status==='PAUSED'?'RATE_LIMIT':null});assert.equal(s.get('coupang-collection-status').textContent,label);assert.equal(s.get('coupang-collection-progress').hidden,true);assert.match(s.get('coupang-collection-summary').textContent,/처리 100개 · 성공 98개 · 실패 2개/);assert.equal(s.timers.size,0);assert.equal(s.writes.length,0);
 }
});
test('dismissing a completed collection survives reload without mutating the collection',async()=>{
 const job={...activeCollection,status:'SUCCEEDED',phase:'DONE',listComplete:true,completed:100};
 const s=setup();await ready(s,job);assert.equal(s.get('coupang-collection-close').hidden,false);s.get('coupang-collection-close').listeners.click();assert.equal(s.get('coupang-collection-state').hidden,true);assert(s.dismissed);
 const reloaded=setup(null,s.dismissed);await ready(reloaded,job);assert.equal(reloaded.get('coupang-collection-state').hidden,true);assert.equal(s.writes.length+reloaded.writes.length,0);
 const changed=setup(null,s.dismissed);await ready(changed,{...job,updatedAt:'2026-10-19T12:05:00Z'});assert.equal(changed.get('coupang-collection-state').hidden,false);
 const newer=setup(null,s.dismissed);await ready(newer,{...job,id:'8'});assert.equal(newer.get('coupang-collection-state').hidden,false);
});
test('new collection and resumed activity override dismissal while running status cannot be closed',async()=>{
 const job={...activeCollection,status:'SUCCEEDED'},dismissed=JSON.stringify([job.id,job.status,job.updatedAt]);
 const s=setup(null,dismissed);await ready(s,job);s.bindings.all();s.writes.shift().resolve(activeCollection);await flush();assert.equal(s.dismissed,'');assert.equal(s.get('coupang-collection-state').hidden,false);assert.equal(s.get('coupang-collection-close').hidden,true);
 s.get('coupang-collection-close').listeners.click();assert.equal(s.get('coupang-collection-state').hidden,false);
 const resumed=setup(null,dismissed);await ready(resumed,activeCollection);assert.equal(resumed.dismissed,'');assert.equal(resumed.get('coupang-collection-state').hidden,false);assert.equal(resumed.timers.size,1);
});
test('failed searches preserve previously confirmed rows',async()=>{const s=setup();await ready(s);const prior=s.get('coupang-rows').innerHTML;s.submit();s.reads.shift().reject(Error('DB 조회 실패'));await flush();assert.equal(s.get('coupang-rows').innerHTML,prior);assert.equal(s.get('coupang-error').hidden,false);});

test('tabs reuse data, preserve option ownership and isolate HTML', async () => {
  const s = setup();await ready(s);
  s.detail('123');
  s.reads[0].resolve({state:{deleted:false},conflicts:[],detail:{
    product: {
      sellerProductId: '123',
      sellerProductName: '상품'
    },
    items: [{
      sellerProductItemId: '1',
      itemName: '첫 옵션',
      images: [{
        url: 'https://cdn.test/one.jpg',
        type: 'REPRESENTATION',
        order: 1
      }],
      contents: [{
        type: 'HTML',
        detailType: 'TEXT',
        content: '<script>parent.bad()</script><form action="https://bad.test">x</form>'
      }],
      notices: [],
      settings: []
    }, {
      sellerProductItemId: '2',
      itemName: '다른 옵션',
      images: [{
        url: 'https://cdn.test/two.jpg',
        type: 'DETAIL',
        order: 2
      }],
      contents: []
    }]
  }});
  await flush();
  s.reads.shift();if(s.reads[0])s.reads.shift().resolve({items:[],totalElements:0,totalPages:0});await flush();
  const tab = key => s.get('coupang-detail-content').listeners.click({
    target: {
      closest: () => ({
        dataset: {
          detailTab: key
        }
      })
    }
  });
  tab('images');
  assert(s.get('coupang-detail-content').innerHTML.includes('one.jpg'));
  assert(!s.get('coupang-detail-content').innerHTML.includes('two.jpg'));
  s.get('coupang-option').value = '1';
  s.get('coupang-option').listeners.change();
  assert(s.get('coupang-detail-content').innerHTML.includes('two.jpg'));
  s.get('coupang-option').value = '0';
  s.get('coupang-option').listeners.change();
  tab('images');
  const html = s.get('coupang-detail-content').innerHTML;
  assert(html.includes('sandbox=""'));
  assert(html.includes("script-src 'none'"));
  assert(!html.includes('<script>'));
  assert(html.includes('srcdoc='));
  assert.equal(s.reads.length, 0);
});
test('description CDN HTTP images upgrade to HTTPS in both HTML and image contents', async () => {
  const s = setup();await ready(s);
  s.detail('123');
  s.reads[0].resolve({state:{deleted:false},conflicts:[],detail:{
    product: {
      sellerProductId: '123',
      sellerProductName: '상품'
    },
    items: [{
      itemName: '옵션',
      contents: [{
        type: 'HTML',
        detailType: 'TEXT',
        content: '<img src="http://img1a.coupangcdn.com/image/html.jpg"><script>bad()</script>'
      }, {
        type: 'IMAGE',
        detailType: 'IMAGE',
        content: 'http://img1a.coupangcdn.com/image/direct.jpg'
      }, {
        type: 'IMAGE',
        detailType: 'IMAGE',
        content: 'http://untrusted.test/image.jpg'
      }]
    }]
  }});
  await flush();
  s.reads.shift();if(s.reads[0])s.reads.shift().resolve({items:[],totalElements:0,totalPages:0});await flush();
  s.get('coupang-detail-content').listeners.click({
    target: {
      closest: () => ({
        dataset: {
          detailTab: 'images'
        }
      })
    }
  });
  const html = s.get('coupang-detail-content').innerHTML;
  assert(html.includes('https://img1a.coupangcdn.com/image/html.jpg'));
  assert(html.includes('https://img1a.coupangcdn.com/image/direct.jpg'));
  assert(!html.includes('http://img1a.coupangcdn.com'));
  assert(!html.includes('<script>'));
  assert(html.includes('sandbox=""'));
  assert(html.includes('이미지 없음'));
  assert(!html.includes('src="http://untrusted.test'));
  assert.equal(s.reads.length, 0);
});
test('relative description CDN paths resolve without using the application origin', async () => {
  const s = setup();await ready(s);
  s.detail('123');
  s.reads[0].resolve({state:{deleted:false},conflicts:[],detail:{
    product: {
      sellerProductId: '123'
    },
    items: [{
      contents: [{
        type: 'IMAGE',
        detailType: 'IMAGE',
        content: 'vendor_inventory/synthetic.jpg'
      }, {
        type: 'IMAGE',
        detailType: 'IMAGE',
        content: '/image/vendor_inventory/root.jpg'
      }]
    }]
  }});
  await flush();
  s.reads.shift();if(s.reads[0])s.reads.shift().resolve({items:[],totalElements:0,totalPages:0});await flush();
  s.get('coupang-detail-content').listeners.click({
    target: {
      closest: () => ({
        dataset: {
          detailTab: 'images'
        }
      })
    }
  });
  const html = s.get('coupang-detail-content').innerHTML;
  assert(html.includes('https://img1a.coupangcdn.com/image/vendor_inventory/synthetic.jpg'));
  assert(html.includes('https://img1a.coupangcdn.com/image/vendor_inventory/root.jpg'));
});
test('purchase attributes stay with options and search attributes follow selected option in settings', async () => {
  const s = setup();await ready(s);
  s.detail('123');
  s.reads[0].resolve({state:{deleted:false},conflicts:[],detail:{
    product: {
      sellerProductId: '123'
    },
    items: [{
      itemName: '블랙',
      attributes: [{
        name: '색상',
        value: '블랙',
        exposed: 'EXPOSED'
      }, {
        name: '소재',
        value: '가죽',
        exposed: 'NONE'
      }]
    }, {
      itemName: '화이트',
      attributes: [{
        name: '색상',
        value: '화이트',
        exposed: 'EXPOSED'
      }, {
        name: '소재',
        value: '면',
        exposed: 'NONE'
      }]
    }]
  }});
  await flush();
  s.reads.shift();if(s.reads[0])s.reads.shift().resolve({items:[],totalElements:0,totalPages:0});await flush();
  let html = s.get('coupang-detail-content').innerHTML;
  assert(html.includes('구매 속성'));
  assert(html.includes('색상: 블랙'));
  assert(!html.includes('가죽'));
  assert(!html.includes('구매·검색 속성'));
  s.get('coupang-detail-content').listeners.click({
    target: {
      closest: () => ({
        dataset: {
          detailTab: 'settings'
        }
      })
    }
  });
  html = s.get('coupang-detail-content').innerHTML;
  assert(html.includes('검색 속성'));
  assert(html.includes('가죽'));
  assert(!html.includes('색상'));
  s.get('coupang-option').value = '1';
  s.get('coupang-option').listeners.change();
  html = s.get('coupang-detail-content').innerHTML;
  assert(html.includes('<dd>면</dd>'));
  assert(!html.includes('가죽'));
  assert.equal(s.reads.length, 0);
});

test('delivery details translate the documented SEQUENCIAL enum', async () => {
  const s = setup();await ready(s);s.detail('123');
  s.reads[0].resolve({state:{deleted:false},conflicts:[],detail:{product:{sellerProductId:'123'},items:[],delivery:[{name:'deliveryMethod',value:'SEQUENCIAL'}]}});
  await flush();
  s.get('coupang-detail-content').listeners.click({target:{closest:()=>({dataset:{detailTab:'delivery'}})}});
  assert(s.get('coupang-detail-content').innerHTML.includes('일반배송'));
  assert(!s.get('coupang-detail-content').innerHTML.includes('SEQUENCIAL'));
});

test('cards preserve search and reset only conditions without changing global counts',async()=>{
 const s=setup();await ready(s);s.get('filter-name').value='블랙';s.submit();s.reads.shift().resolve({...listing,totalElements:0,items:[]});await flush();
 assert.equal(s.get('coupang-count-all').textContent,'9');
 s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'SOLD_OUT'}})}});
 let q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'SOLD_OUT');assert.equal(q.get('name'),'블랙');assert.equal(q.get('page'),'0');
 s.reads.shift().resolve(listing);await flush();s.get('coupang-reset').listeners.click();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'SOLD_OUT');assert.equal(q.get('name'),null);assert.equal(s.writes.length,0);
});
test('own option image uses HTTPS representative then valid fallback without row metadata',async()=>{
 const s=setup();await ready(s);s.submit();s.reads.shift().resolve({...listing,items:[{...listing.items[0],option:{...listing.items[0].option,images:[{url:'https://cdn.test/detail.jpg',order:0,type:'DETAIL'},{url:'http://bad.test/a.jpg',order:1,type:'REPRESENTATION'},{url:'https://cdn.test/representative.jpg',order:2,type:'REPRESENTATION'}]}},{...listing.items[0],option:null}]});await flush();
 const html=s.get('coupang-rows').innerHTML;assert.match(html,/data-coupang-image="https:\/\/cdn.test\/representative.jpg"/);assert(!html.includes('detail.jpg'));assert(!html.includes('bad.test'));assert.match(html,/사진 없음/);assert(!html.includes('등록상품 123'));assert(!html.includes(' · 확인 '));
 s.get('coupang-rows').listeners.click({target:{closest:selector=>selector==='[data-coupang-image]'?{dataset:{coupangImage:'https://cdn.test/representative.jpg',imageLabel:'상품'}}:null}});assert.equal(s.get('coupang-image-dialog').open,true);assert.equal(s.get('coupang-image-full').src,'https://cdn.test/representative.jpg');
 s.get('coupang-image-full').listeners.error();assert.equal(s.get('coupang-image-status').hidden,false);s.get('coupang-image-close').listeners.click();assert.equal(s.get('coupang-image-dialog').open,false);
});

test('editor return restores group, search, page, page size and scroll',async()=>{
 const s=setup();await ready(s);s.get('filter-name').value='복귀 검색';s.submit();s.reads.shift().resolve({...listing,totalPages:3});await flush();
 s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'STOPPED'}})}});s.reads.shift().resolve({...listing,totalPages:3});await flush();
 s.get('coupang-sort').value='CREATED_ASC';s.get('coupang-size').value='50';s.get('coupang-next').listeners.click();s.reads.shift().resolve({...listing,page:1,totalPages:3});await flush();
 s.get('filter-stock').value='10';s.get('filter-stock').listeners.input();s.window.scrollY=0;s.window.ModalScroll={position:()=>({x:0,y:427})};const restored=setup(s.saveNavigation());restored.reads.shift().resolve([]);await flush();const query=new URL(restored.reads[0].url,'http://local').searchParams;assert.equal(query.get('group'),'STOPPED');assert.equal(query.get('name'),'복귀 검색');assert.equal(query.get('page'),'1');assert.equal(query.get('size'),'50');assert.equal(query.get('sort'),'CREATED_ASC');assert.equal(restored.get('coupang-sort').value,'CREATED_ASC');restored.reads.shift().resolve({...listing,page:1,totalPages:3});await flush();assert.equal(restored.scroll,427);assert.equal(restored.tableScroll,216);assert.equal(restored.get('filter-name').value,'복귀 검색');assert.equal(restored.writes.length,0);restored.get('filter-saleStatus').value='ON_SALE';restored.get('filter-saleStatus').listeners.change();assert.equal(restored.get('filter-stock').value,'1');assert.equal(restored.get('filter-stockComparison').value,'GE');
});

test('card presets synchronize inputs; status edits clear only generated stock and custom combinations use ALL',async()=>{
 const s=setup();await ready(s);const card=choice=>s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:choice}})}});
 s.get('filter-brand').value='브랜드';card('SELLING');assert.equal(s.get('filter-stock').value,'1');assert.equal(s.get('filter-stockComparison').value,'GE');assert.equal(s.get('filter-saleStatus').value,'ON_SALE');s.reads.shift().resolve(listing);await flush();
 s.get('filter-saleStatus').value='STOPPED';s.get('filter-saleStatus').listeners.change();assert.equal(s.get('filter-stock').value,'');assert.equal(s.reads.length,0);s.submit();let q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'STOPPED');assert.equal(q.get('stock'),null);assert.equal(q.get('brand'),'브랜드');s.reads.shift().resolve(listing);await flush();
 card('SELLING');s.reads.shift().resolve(listing);await flush();s.get('filter-stock').value='10';s.get('filter-stock').listeners.input();s.submit();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'ALL');assert.equal(q.get('stock'),'10');s.reads.shift().resolve(listing);await flush();
 s.get('filter-saleStatus').value='STOPPED';s.get('filter-saleStatus').listeners.change();assert.equal(s.get('filter-stock').value,'10');s.submit();assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('group'),'ALL');assert.equal(s.writes.length,0);
});
const supplierKey=JSON.stringify(['123','222']);
function supplierClick(s,attribute='supplierToggle',key=supplierKey){const selector='[data-'+attribute.replace(/[A-Z]/g,c=>'-'+c.toLowerCase())+']';s.get('coupang-rows').listeners.click({target:{closest:q=>q===selector?{dataset:{[attribute]:key},disabled:false}:null}});}
const procurement={id:'42',brand:'브랜드',productCode:'HJWA908BK',latestStatus:'NOT_CHECKED',selectedSupplier:null};
test('supplier expansion reads exact code only, pages without duplicates, preserves on selection and retries failures',async()=>{
 const s=setup();await ready(s);supplierClick(s);assert.match(s.reads[0].url,/\/api\/products\/by-code\?/);assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('code'),'HJWA908BK');assert.match(s.get('coupang-rows').innerHTML,/colspan="13"/);
 s.reads.shift().resolve({items:[procurement],totalPages:2});await flush();assert.match(s.get('coupang-rows').innerHTML,/매입처 미선정/);assert.match(s.get('coupang-rows').innerHTML,/data-detail="42"/);
 s.get('coupang-rows').listeners.change({target:{dataset:{productSelect:'123'},checked:true}});assert.match(s.get('coupang-rows').innerHTML,/coupang-supplier-row/);assert.equal(s.reads.length,0);
 supplierClick(s,'supplierMore');assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('page'),'1');s.reads.shift().reject(Error('추가 조회 실패'));await flush();assert.match(s.get('coupang-rows').innerHTML,/추가 조회 실패/);assert.match(s.get('coupang-rows').innerHTML,/data-detail="42"/);
 supplierClick(s,'supplierRetry');s.reads.shift().resolve({items:[procurement,{...procurement,id:'43'}],totalPages:2});await flush();assert.equal((s.get('coupang-rows').innerHTML.match(/class="coupang-supplier-product"/g)||[]).length,2);assert.equal(s.writes.length,0);
 supplierClick(s);assert(!s.get('coupang-rows').innerHTML.includes('coupang-supplier-row'));
});
test('late supplier responses cannot reopen a closed or changed list; reopening queries again',async()=>{
 const s=setup();await ready(s);supplierClick(s);const old=s.reads.shift();supplierClick(s);supplierClick(s);const fresh=s.reads.shift();old.resolve({items:[procurement],totalPages:1});await flush();assert(!s.get('coupang-rows').innerHTML.includes('상품 42'));fresh.resolve({items:[],totalPages:0});await flush();assert.match(s.get('coupang-rows').innerHTML,/일치하는 상품 없음/);
 supplierClick(s);supplierClick(s);const pending=s.reads.shift();s.submit();s.reads.shift().resolve(listing);await flush();pending.resolve({items:[procurement],totalPages:1});await flush();assert(!s.get('coupang-rows').innerHTML.includes('coupang-supplier-row'));
});

test('registration sort defaults newest and changes from page one without losing filters',async()=>{
 const s=setup();s.reads.shift().resolve([]);await flush();const initial=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(initial.get('sort'),'CREATED_DESC');assert.equal(initial.get('group'),'SELLING');assert.equal(initial.get('saleStatus'),'ON_SALE');assert.equal(initial.get('stock'),'1');assert.equal(initial.get('stockComparison'),'GE');s.reads.shift().resolve({...listing,totalPages:3});await flush();s.reads.shift().resolve(null);await flush();
 s.get('filter-name').value='검색 유지';s.submit();s.reads.shift().resolve({...listing,totalPages:3});await flush();
 s.get('coupang-next').listeners.click();s.reads.shift().resolve({...listing,page:1,totalPages:3});await flush();
 s.get('coupang-sort').value='CREATED_ASC';s.get('coupang-sort').listeners.change();let q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('sort'),'CREATED_ASC');assert.equal(q.get('page'),'0');assert.equal(q.get('name'),'검색 유지');s.reads.shift().resolve(listing);await flush();
 s.get('coupang-reset').listeners.click();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('sort'),'CREATED_ASC');assert.equal(q.get('name'),null);assert.equal(s.writes.length,0);
});

test('recommended preset and searches retain group and global count; reset preserves recommendation and sort',async()=>{
 const s=setup();await ready(s);s.get('filter-name').value='상품';s.get('coupang-sort').value='CREATED_ASC';
 s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'RECOMMENDED'}})}});
 assert.equal(s.get('filter-saleStatus').value,'ON_SALE');assert.equal(s.get('filter-stock').value,'');
 let q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'RECOMMENDED');assert.equal(q.get('sort'),'CREATED_ASC');assert.equal(q.get('name'),'상품');
 const recommended={...listing,counts:{...listing.counts,recommended:7}};s.reads.shift().resolve(recommended);await flush();
 s.get('filter-stock').value='10';s.get('filter-stock').listeners.input();s.get('filter-saleStatus').value='STOPPED';s.get('filter-saleStatus').listeners.change();s.submit();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'RECOMMENDED');assert.equal(q.get('stock'),'10');assert.equal(q.get('saleStatus'),'STOPPED');s.reads.shift().resolve({...recommended,items:[],totalElements:0});await flush();assert.equal(s.get('coupang-count-recommended').textContent,'7');
 s.get('coupang-reset').listeners.click();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'RECOMMENDED');assert.equal(q.get('saleStatus'),'ON_SALE');assert.equal(q.get('stock'),null);assert.equal(q.get('name'),null);assert.equal(q.get('sort'),'CREATED_ASC');s.reads.shift().resolve(recommended);await flush();
 s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'SELLING'}})}});assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('group'),'SELLING');
});
test('recommendation reasons and per-option actions are rendered once; multi source stock is never summed',async()=>{
 const s=setup();await ready(s);s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'RECOMMENDED'}})}});
 const recommendation={internalProductId:'41',reasons:['PRICE_HIGH','RESTOCK'],suggestedPrice:147000,previousPrice:true,suggestedLoss:false,stockOptionCount:2,stocks:[{id:'a',quantity:70}]};
 s.reads.shift().resolve({...listing,items:[{...listing.items[0],recommendation}]});await flush();const html=s.get('coupang-rows').innerHTML;
 assert(html.includes('가격 인하'));assert(html.includes('재고 보충'));assert(html.includes('>147,000원</strong>'));assert(html.includes('이전 정상 확인 사입가 기준'));assert(!html.includes('제안 '));assert(!html.includes('이전 가격'));assert(html.includes('>옵션 선택</strong>'));assert(!html.includes('70개'));assert.equal((html.match(/data-quick-edit=/g)||[]).length,2);assert(html.includes('data-internal-product="41"'));
 s.submit();s.reads.shift().reject(Error('추천 근거 조회 실패'));await flush();assert.equal(s.get('coupang-rows').innerHTML,html);assert.equal(s.get('coupang-error').textContent,'추천 근거 조회 실패');
});
test('restored recommendation clamps a removed last page and retains filters and scroll',async()=>{
 const s=setup(JSON.stringify({submitted:{sku:'HJWA',saleStatus:'ON_SALE'},group:'RECOMMENDED',deleted:false,index:3,size:'20',sort:'CREATED_ASC',scroll:427,autoStock:{stock:'',comparison:'EQ'}}));
 s.reads.shift().resolve([]);await flush();assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('page'),'3');
 s.reads.shift().resolve({...listing,items:[],page:3,totalPages:2});await flush();const q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('group'),'RECOMMENDED');assert.equal(q.get('page'),'1');assert.equal(q.get('sku'),'HJWA');
 s.reads.shift().resolve({...listing,page:1,totalPages:2});await flush();assert.equal(s.get('coupang-page').textContent,'2 / 2페이지');assert.equal(s.scroll,427);
});


test('reason selection filters before paging, persists through searches/reset/cards and restores after failure',async()=>{
 const s=setup();await ready(s);const card=group=>s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:group}})}}),choose=reason=>s.get('coupang-recommendation-filters').listeners.click({target:{closest:()=>({dataset:{recommendationReason:reason}})}});
 const data={...listing,counts:{...listing.counts,recommended:8,recommendationReasons:{priceHigh:4,negativeMargin:2,restock:5}}};
 assert(s.get('coupang-recommendation-filters').hidden);card('RECOMMENDED');s.reads.shift().resolve(data);await flush();assert(!s.get('coupang-recommendation-filters').hidden);assert.equal(s.get('coupang-reason-count-PRICE_HIGH').textContent,'4');
 s.get('filter-name').value='상품';s.get('coupang-sort').value='CREATED_ASC';choose('PRICE_HIGH');let q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('reason'),'PRICE_HIGH');assert.equal(q.get('page'),'0');assert.equal(q.get('name'),'상품');assert.equal(q.get('sort'),'CREATED_ASC');s.reads.shift().resolve(data);await flush();
 s.submit();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('name'),'상품');assert.equal(q.get('reason'),'PRICE_HIGH');s.reads.shift().resolve({...data,items:[],totalElements:0});await flush();assert.equal(s.get('coupang-reason-count-PRICE_HIGH').textContent,'4');
 s.get('coupang-reset').listeners.click();assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('reason'),'PRICE_HIGH');s.reads.shift().resolve(data);await flush();
 choose('RESTOCK');s.reads.shift().reject(Error('유형 조회 실패'));await flush();s.submit();assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('reason'),'PRICE_HIGH');s.reads.shift().resolve(data);await flush();
 card('SELLING');assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('reason'),null);s.reads.shift().resolve(data);await flush();assert(s.get('coupang-recommendation-filters').hidden);
 card('RECOMMENDED');assert.equal(new URL(s.reads[0].url,'http://local').searchParams.get('reason'),'PRICE_HIGH');s.reads.shift().resolve(data);await flush();
 const saved=JSON.parse(s.saveNavigation());assert.equal(saved.reason,'PRICE_HIGH');const restored=setup(JSON.stringify(saved));restored.reads.shift().resolve([]);await flush();assert.equal(new URL(restored.reads[0].url,'http://local').searchParams.get('reason'),'PRICE_HIGH');
});
test('recommendation cells show only relevant one-line comparisons, including same-price losses and unknown prices',async()=>{
 const s=setup();await ready(s);s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:'RECOMMENDED'}})}});
 const base=listing.items[0],rec={internalProductId:'41',suggestedPrice:147000,previousPrice:false,priceCheckedAt:'2026-10-16T10:00:00',stockOptionCount:1,stocks:[{id:'a',quantity:70}]};
 s.reads.shift().resolve({...listing,items:[{...base,recommendation:{...rec,reasons:['PRICE_HIGH']}}]});await flush();let html=s.get('coupang-rows').innerHTML;assert.equal((html.match(/data-quick-edit=/g)||[]).length,1);assert(html.includes('data-quick-edit="PRICE"'));assert(!html.includes('70개'));assert(!html.includes('제안 '));
 s.submit();s.reads.shift().resolve({...listing,items:[{...base,option:{...base.option,current:{salePrice:null,amountInStock:0,onSale:true}},recommendation:{...rec,reasons:['RESTOCK']}}]});await flush();html=s.get('coupang-rows').innerHTML;assert.equal((html.match(/data-quick-edit=/g)||[]).length,1);assert(html.includes('data-quick-edit="STOCK"'));assert(html.includes('>0개</span>'));assert(html.includes('>70개</strong>'));assert(!html.includes('147,000원'));
 s.submit();s.reads.shift().resolve({...listing,items:[{...base,recommendation:{...rec,reasons:['NEGATIVE_MARGIN'],suggestedPrice:base.option.current.salePrice,suggestedLoss:true}}]});await flush();html=s.get('coupang-rows').innerHTML;assert(!html.includes('coupang-recommendation-arrow'));assert(html.includes('계산 설정 검토 필요'));assert(html.includes('data-quick-edit="PRICE"'));assert.equal(s.writes.length,0);
});

test('all cards share a separate recommendation-reason column without enabling recommendation-only actions',async()=>{
 const s=setup();await ready(s);
 const row={...listing.items[0],recommendation:{internalProductId:'41',reasons:['PRICE_HIGH','RESTOCK'],suggestedPrice:147000,stockOptionCount:1,stocks:[{id:'a',quantity:70}]}};
 for(const group of ['ALL','SELLING','SOLD_OUT','STOPPED','DELETED','RECOMMENDED']){
  s.get('coupang-groups').listeners.click({target:{closest:()=>({dataset:{productGroup:group}})}});
  s.reads.shift().resolve({...listing,items:[{...row,deleted:group==='DELETED'}]});await flush();
  const cells=[...s.get('coupang-rows').innerHTML.matchAll(/<td(?: [^>]*)?>([\s\S]*?)<\/td>/g)].map(m=>m[1]);
  assert.equal(cells.length,13);assert(!cells[5].includes('coupang-recommendation-reasons'));assert(cells[6].includes('색상: 검정'));assert(cells[7].includes('가격 인하'));assert(cells[7].includes('재고 보충'));
  if(group!=='RECOMMENDED')assert(!s.get('coupang-rows').innerHTML.includes('data-quick-edit'));
 }
 s.submit();s.reads.shift().resolve(listing);await flush();assert.match(s.get('coupang-rows').innerHTML,/<td class="coupang-reasons-cell">—<\/td>/);
});

test('recent collection opens with seven Korean days and never starts on input Enter',async()=>{
 const s=setup();await ready(s);s.get('coupang-collect-recent').listeners.click();assert(s.get('coupang-recent-dialog').open);assert.equal(s.get('coupang-recent-count').value,'100');assert.equal((Date.parse(s.get('coupang-recent-to').value)-Date.parse(s.get('coupang-recent-from').value))/86400000,6);assert.equal(s.writes.length,0);
 s.bindings.recent();assert.equal(s.writes.length,1);assert.equal(s.writes[0].body.mode,'RECENT');assert.equal(s.writes[0].body.maxProducts,100);assert.deepEqual(Array.from(s.writes[0].body.sellerProductIds),[]);s.bindings.recent();assert.equal(s.writes.length,1);
 s.writes.shift().reject(Error('요청 거절'));await flush();assert(s.get('coupang-recent-dialog').open);assert.match(s.get('coupang-recent-error').textContent,/요청 거절/);assert.equal(s.get('coupang-recent-count').value,'100');
});
test('recent invalid conditions preserve input and never post',async()=>{
 const s=setup();await ready(s);s.get('coupang-collect-recent').listeners.click();s.get('coupang-recent-count').value='501';await s.bindings.recent();assert.equal(s.writes.length,0);assert.match(s.get('coupang-recent-error').textContent,/1~500/);assert.equal(s.get('coupang-recent-count').value,'501');
 s.get('coupang-recent-count').value='1';s.get('coupang-recent-from').value='2026-01-01';s.get('coupang-recent-to').value='2026-04-01';await s.bindings.recent();assert.equal(s.writes.length,0);
});
test('recent listing has independent counters and detail progress despite global listComplete false',async()=>{
 const recent={createdDateFrom:'2026-10-14',createdDateTo:'2026-10-20',maxProducts:100,currentDate:'2026-10-19',apiPages:3,candidates:150,selected:0,selectionComplete:false};
 const s=setup();await ready(s,{...activeCollection,mode:'RECENT',recent});assert.match(s.get('coupang-collection-summary').textContent,/API 3페이지 · 발견 후보 150개 · 확정 대상 0개/);assert(!('value' in s.get('coupang-collection-progress')));assert(s.get('coupang-collect-recent').disabled);
 s.tick();s.reads.shift().resolve({...activeCollection,mode:'RECENT',phase:'DETAIL',listComplete:false,discovered:100,completed:20,failed:5,recent:{...recent,selectionComplete:true,selected:100}});await flush();assert.equal(s.get('coupang-collection-progress').value,25);assert.match(s.get('coupang-collection-note').textContent,/다시 선정하지/);assert.equal(s.writes.length,0);
});
