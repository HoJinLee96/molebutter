const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const template=readFileSync('molebutter-app/src/main/resources/templates/products.html','utf8');
const source=readFileSync('molebutter-app/src/main/resources/static/js/products.js','utf8');
const product={id:'42',brand:'헤지스',brandId:'1',brandKey:'HAZZYS',productCode:'ABCD6F123BK',comparisonCode:'ABCD123',searchQuery:'ABCD123',searchMode:'AUTO',suggestedQuery:'ABCD123',codeType:'LF_ACCESSORY',managed:true,revision:0,lookupRevision:0,latestStatus:'NOT_CHECKED',latestResult:null};
const pageOf=(items=[product],page=0)=>({items,page,totalElements:41,totalPages:3});
const settle=async()=>{for(let i=0;i<4;i++)await new Promise(r=>setImmediate(r));};
function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};}
function node(){return {value:'',textContent:'',innerHTML:'',hidden:true,disabled:false,checked:false,open:false,options:[],dataset:{},attrs:{},listeners:{},classList:{toggle(){}},addEventListener(n,fn){this.listeners[n]=fn;},setAttribute(n,v){this.attrs[n]=v;},showModal(){this.open=true;},close(){this.open=false;this.listeners.close?.();},reset(){}};}
function fixture({load=async()=>pageOf(),post=async()=>null,brandFailure=false,legacy=false,page='products'}={}) {
 const nodes=new Map([...template.matchAll(/\bid="([^"]+)"/g)].map(([,id])=>[id,node()]));const $=id=>nodes.get(id)||null;
 const tabs=['ALL','AUTO','MANUAL'].map(mode=>({...node(),dataset:{mode}}));const calls=[],posts=[],pagers={},events={},timers=new Map();let timerId=0;
 $('product-q').value='복원 검색어';$('product-status').value='SOLD_OUT';
 const ctx=vm.createContext({URL,URLSearchParams,console,FormData,location:{search:''},history:{replaceState(){}},window:{addEventListener(n,fn){events[n]=fn;}},setInterval(){},setTimeout(fn){timers.set(++timerId,fn);return timerId;},clearTimeout(id){timers.delete(id);},
 document:{hidden:false,body:{dataset:{page,productView:legacy?'old':'lookup-v11'}},addEventListener(){},querySelectorAll(q){return q==='[data-mode]'?tabs:[];}},
 AppUI:{$,escape:v=>String(v??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'),stamp:v=>v,pager(id,data,change,options){pagers[id]={data,change,options};}},ProductSourceSearch:{webUrl:()=>''},
 setError(id,message){if($(id)){$(id).textContent=message||'';$(id).hidden=!message;}},
 apiGet:async url=>{calls.push(url);if(url.startsWith('/api/products/change-counts'))return {selected:0,all:0};if(url==='/api/settings/brands'){if(brandFailure)throw Error('설정 조회 실패');return [{id:'1',name:'헤지스'}];}return load(url);},
 apiPost:async(url,body)=>{posts.push({url,body});return post(url,body);}});vm.runInContext(readFileSync('molebutter-app/src/main/resources/static/js/product-refresh-watch.js','utf8'),ctx);vm.runInContext(readFileSync('molebutter-app/src/main/resources/static/js/mall-tag.js','utf8'),ctx);vm.runInContext(source,ctx);
 return {$,calls,posts,pagers,events,timers,async tick(){const next=timers.entries().next().value;if(next){timers.delete(next[0]);next[1]();}await settle();},submit(id='product-filter'){return $(id).listeners.submit({preventDefault(){},submitter:node()});},click(id){return $(id).listeners.click({currentTarget:$(id)});},select(){ $('product-rows').listeners.change({target:{dataset:{select:'42'},checked:true}}); },mode(mode){const b=tabs.find(t=>t.dataset.mode===mode);$('management-tabs').listeners.click({target:{closest:()=>b}});},row(dataset){$('product-rows').listeners.click({target:{closest:()=>({...node(),dataset})}});}};
}
test('entry and back navigation reset filters and load immediately without settings dependency',async()=>{const f=fixture({brandFailure:true});await settle();assert.equal(f.calls[0],'/api/products?q=&mode=ALL&status=&page=0&size=100&change=ALL');assert.match(f.$('product-rows').innerHTML,/ABCD6F123BK/);f.mode('MANUAL');await settle();f.$('product-q').value='X';f.events.pageshow({persisted:true});await settle();assert.equal(f.calls.filter(u=>u.startsWith('/api/products?')).at(-1),f.calls[0]);});
test('numbered pager and size changes preserve query and clear selections',async()=>{const f=fixture();await settle();f.select();f.$('product-q').value='ABCD';f.$('product-page-size').value='50';f.$('product-page-size').listeners.change();await settle();assert.match(f.calls.filter(u=>u.startsWith('/api/products?')).at(-1),/q=ABCD&mode=ALL&status=&page=0&size=50/);assert.match(f.$('product-selection').textContent,/선택 0개/);assert.equal(f.pagers['product-pager'].options.numbered,true);f.pagers['product-pager'].change(2);await settle();assert.match(f.calls.filter(u=>u.startsWith('/api/products?')).at(-1),/page=2&size=50/);});
test('out-of-order failures cannot replace a newer response',async()=>{const old=deferred(),next=deferred();let n=0;const f=fixture({load:()=>++n===1?old.promise:next.promise});f.mode('AUTO');next.resolve(pageOf());await settle();old.reject(Error('오래된 실패'));await settle();assert.equal(f.$('page-error').hidden,true);assert.match(f.$('product-rows').innerHTML,/ABCD6F123BK/);assert.equal(f.$('product-query').disabled,false);});
test('failed request can be retried; incompatible html does not touch missing controls',async()=>{let n=0;const f=fixture({load:async()=>{if(++n===1)throw Error('일시 오류');return pageOf([]);}});await settle();assert.match(f.$('page-error').textContent,/일시 오류/);f.submit();await settle();assert.equal(f.$('page-error').hidden,true);assert.match(f.$('product-rows').innerHTML,/상품이 없습니다/);const legacy=fixture({legacy:true});await settle();assert.equal(legacy.calls.length,0);assert.match(legacy.$('page-error').textContent,/다시 실행/);});
test('management switch sends displayed revision once and keeps state on failure',async()=>{const wait=deferred();const f=fixture({post:()=>wait.promise});await settle();f.row({managed:'42'});f.row({managed:'42'});assert.equal(f.posts.length,1);assert.equal(JSON.stringify(f.posts[0].body),JSON.stringify({products:[{id:'42',revision:0}],managed:false}));wait.reject(Error('저장 실패'));await settle();assert.match(f.$('page-error').textContent,/저장 실패/);assert.match(f.$('product-rows').innerHTML,/aria-checked="true"/);});
test('deletion requires confirmation and blocks duplicate submits',async()=>{const wait=deferred();const f=fixture({post:()=>wait.promise});await settle();f.row({delete:'42'});assert.equal(f.posts.length,0);assert.equal(f.$('product-delete-dialog').open,true);f.submit('product-delete-form');f.submit('product-delete-form');assert.equal(f.posts.length,1);assert.equal(f.posts[0].url,'/api/products/delete');wait.resolve({});await settle();assert.equal(f.$('product-delete-dialog').open,false);});
test('selected refresh is allowed with no supplier links and uses product ids',async()=>{const f=fixture({post:async()=>({id:'run'})});await settle();assert.doesNotMatch(f.$('product-rows').innerHTML,/data-refresh="42" disabled/);f.select();f.click('refresh-selected');await settle();assert.equal(JSON.stringify(f.posts[0].body),JSON.stringify({scope:'SELECTED',ids:['42']}));});

const detailOf=p=>({product:{...p},suppliers:[],history:pageOf([]),lastGoodResult:null});
const historyPage=(marker,page=0)=>pageOf([{createdAt:marker,payload:{status:'SUCCESS',suppliers:[]}}],page);
test('history ignores late success and failure after switching, reopening or closing a product modal',async()=>{
 for(const transition of ['switch','reopen','close'])for(const failed of [false,true]) {
  const pending=deferred();
  const f=fixture({load:async url=>{
   if(url==='/api/products/42/history?page=1')return pending.promise;
   if(url.endsWith('/duplicates'))return [];
   const match=/^\/api\/products\/(42|43)$/.exec(url);
   if(match)return {...detailOf({...product,id:match[1]}),history:historyPage('current-'+match[1])};
   return pageOf();
  }});
  await settle();f.row({detail:'42'});await settle();
  const request=f.pagers['history-pager'].change(1);
  f.$('product-dialog').close();
  if(transition!=='close'){f.row({detail:transition==='switch'?'43':'42'});await settle();}
  const before=f.$('history-results').innerHTML,pagerBefore=f.pagers['history-pager'];
  if(failed)pending.reject(Error('old-history-error'));else pending.resolve(historyPage('old-history',1));
  await request;await settle();
  assert.equal(f.$('history-results').innerHTML,before,transition);
  assert.equal(f.pagers['history-pager'],pagerBefore,transition);
  assert.equal(f.$('detail-error').hidden,true,transition);
  assert.equal(f.$('product-dialog').open,transition!=='close');
 }
});
test('current history pages still load and a failed page can be retried',async()=>{
 let attempts=0;
 const f=fixture({load:async url=>{
  if(url==='/api/products/42/history?page=1'){if(++attempts===1)throw Error('current-history-error');return historyPage('current-page-1',1);}
  if(url.endsWith('/duplicates'))return [];
  if(url==='/api/products/42')return {...detailOf(product),history:historyPage('current-page-0')};
  return pageOf();
 }});
 await settle();f.row({detail:'42'});await settle();
 await f.pagers['history-pager'].change(1);
 assert.equal(f.$('detail-error').textContent,'current-history-error');
 assert.match(f.$('history-results').innerHTML,/current-page-0/);
 await f.pagers['history-pager'].change(1);
 assert.match(f.$('history-results').innerHTML,/current-page-1/);
 assert.equal(f.pagers['history-pager'].data.page,1);
});
test('only the latest history page request may update the current modal',async()=>{
 for(const failed of [false,true]) {
  const old=deferred(),latest=deferred();
  const f=fixture({load:async url=>{
   if(url==='/api/products/42/history?page=1')return old.promise;
   if(url==='/api/products/42/history?page=2')return latest.promise;
   if(url.endsWith('/duplicates'))return [];
   if(url==='/api/products/42')return detailOf(product);
   return pageOf();
  }});
  await settle();f.row({detail:'42'});await settle();
  const first=f.pagers['history-pager'].change(1),second=f.pagers['history-pager'].change(2);
  latest.resolve(historyPage('latest-page-2',2));await second;
  if(failed)old.reject(Error('old-page-error'));else old.resolve(historyPage('old-page-1',1));
  await first;
  assert.match(f.$('history-results').innerHTML,/latest-page-2/);
  assert.equal(f.pagers['history-pager'].data.page,2);
  assert.equal(f.$('detail-error').hidden,true);
 }
});
test('modal shows loading, polls only status, applies completion and preserves unsaved fields and revision',async()=>{
 let status='CHECKING',gets=0;
 const f=fixture({load:async url=>{
  if(url.endsWith('/refresh-status'))return {productId:'42',status,runStatus:'RUNNING',runId:'7'};
  if(url.endsWith('/duplicates'))return [];
  if(url==='/api/products/42'){gets++;return detailOf({...product,latestStatus:gets===1?'NOT_CHECKED':'SUCCESS',revision:gets===1?0:2});}
  return pageOf();
 },post:async()=>({id:'7'})});
 await settle();f.row({detail:'42'});await settle();f.click('detail-refresh');await settle();
 assert.equal(f.$('detail-spinner').hidden,false);assert.equal(f.$('detail-refresh').disabled,true);
 f.$('edit-query').value='저장 전 검색어';f.$('edit-form').listeners.input();
 await f.tick();await f.tick();assert.equal(gets,1,'long running search does not fetch full result repeatedly');
 status='SUCCESS';await f.tick();assert.equal(gets,2);assert.equal(f.$('detail-spinner').hidden,true);
 assert.equal(f.$('detail-refresh').disabled,false);assert.match(f.$('detail-progress-text').textContent,/완료/);
 assert.equal(f.$('edit-query').value,'저장 전 검색어');assert.equal(f.timers.size,0);
 f.submit('edit-form');await settle();assert.equal(f.posts.at(-1).body.revision,0,'form retains its original revision for conflict detection');
});
test('closing a modal fences late completion and never opens it again',async()=>{
 const response=deferred();let detailGets=0;
 const f=fixture({load:async url=>{
  if(url.endsWith('/refresh-status'))return {productId:'42',status:'SUCCESS',runStatus:'RUNNING',runId:'7'};
  if(url.endsWith('/duplicates'))return [];
  if(url==='/api/products/42')return ++detailGets===1?detailOf({...product,latestStatus:'CHECKING'}):response.promise;
  return pageOf();
 }});
 await settle();f.row({detail:'42'});await settle();await f.tick();f.$('product-dialog').close();
 response.resolve(detailOf({...product,latestStatus:'SUCCESS'}));await settle();
 assert.equal(f.$('product-dialog').open,false);assert.equal(f.$('detail-progress').hidden,true);assert.equal(f.timers.size,0);
});
test('paused and blocked work stop spinner and offer work page instead of false completion',async()=>{
 let status={productId:'42',status:'PENDING',runStatus:'PAUSED',runId:'7'};
 const f=fixture({load:async url=>url.endsWith('/refresh-status')?status:url.endsWith('/duplicates')?[]:url==='/api/products/42'?detailOf({...product,latestStatus:'PENDING'}):pageOf()});
 await settle();f.row({detail:'42'});await settle();await f.tick();
 assert.equal(f.$('detail-spinner').hidden,true);assert.equal(f.$('detail-run-link').hidden,false);assert.match(f.$('detail-progress-text').textContent,/중단/);
 status={...status,runStatus:'BLOCKED',message:'네이버 접속 제한'};await f.tick();assert.match(f.$('detail-progress-text').textContent,/접속 제한/);
 status={...status,status:'CHECKING',runStatus:'RUNNING'};await f.tick();assert.equal(f.$('detail-spinner').hidden,false);
});
test('a new job started by another tab during completion is still watched',async()=>{
 let n=0;
 const f=fixture({load:async url=>{
  if(url.endsWith('/refresh-status'))return {productId:'42',status:'SUCCESS',runStatus:'COMPLETED',runId:'7'};
  if(url.endsWith('/duplicates'))return [];
  if(url==='/api/products/42')return detailOf({...product,latestStatus:++n<3?'CHECKING':'SUCCESS'});
  return pageOf();
 }});
 await settle();f.row({detail:'42'});await settle();await f.tick();assert.equal(f.$('detail-spinner').hidden,false);assert.equal(f.timers.size,1);
 await f.tick();assert.equal(f.$('detail-spinner').hidden,true);assert.match(f.$('detail-progress-text').textContent,/완료/);assert.equal(f.timers.size,0);
});

test('missing preferred suppliers notice survives the conflict refresh',async()=>{
 const f=fixture({post:async()=>{const err=Error('공통 설정에서 선호 매입처를 먼저 등록해 주세요.');err.status=409;throw err;}});
 await settle();f.row({refresh:'42'});await settle();
 assert.equal(f.$('page-error').hidden,false);assert.match(f.$('page-error').textContent,/선호 매입처/);
 assert.equal(f.calls.filter(u=>u.startsWith('/api/products?')).length,2);
});

test('shared product stock is shown once in supplier card and selected summaries',async()=>{
 const result={offer:{mall:'NAVER_SMART_STORE',mallName:'헤지스핸드백',price:103800,deliveryFee:0},match:{state:'SEARCH_RESULT'},state:'CONFIRMED',options:[{id:'10481417934',label:'상품 전체',stock:50,state:'AVAILABLE',stockScope:'PRODUCT',simpleChoices:[{id:'1',groupName:'컬러',name:'블랙'},{id:'2',groupName:'선물 포장',name:'O'},{id:'3',groupName:'선물 포장',name:'X'}]}]};
 const listing={id:'L1',mall:'NAVER_SMART_STORE',store:{retailer:'롯데백화점',name:'잠실점'},preferred:true,current:true,selected:true,priceStatus:'CONFIRMED',referencePrice:103800,inventoryState:'AVAILABLE',result};
 const p={...product,selectedSupplier:listing};const d={...detailOf(p),comparison:{selected:listing,groups:[{mall:listing.mall,store:listing.store,minPrice:103800,maxPrice:103800,listings:[listing]}],recommendations:[],recommendationStatus:{state:'READY'}}};
 const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([p])});await settle();
 assert.match(f.$('product-rows').innerHTML,/상품 전체 재고 50개/);f.row({detail:'42'});await settle();
 assert.match(f.$('detail-summary').innerHTML,/상품 전체 재고 50개/);
 assert.equal((f.$('supplier-results').innerHTML.match(/상품 전체 재고/g)||[]).length,1);
 assert.doesNotMatch(f.$('supplier-results').innerHTML,/옵션 상품 전체/);
 assert.match(f.$('supplier-results').innerHTML,/컬러: 블랙/);
 assert.match(f.$('supplier-results').innerHTML,/선물 포장: O \/ X/);
 assert.doesNotMatch(f.$('product-rows').innerHTML,/컬러:|선물 포장/);
 result.options[0].simpleChoices=[{id:'1',groupName:'<컬러>',name:'<img src=x onerror=alert(1)>'}];f.row({detail:'42'});await settle();
 assert.match(f.$('supplier-results').innerHTML,/&lt;img src=x onerror=alert\(1\)&gt;/);
 assert.doesNotMatch(f.$('supplier-results').innerHTML,/<img src=x/);
 result.options=[{id:'old',label:'FREE',stock:3,state:'AVAILABLE',stockScope:null}];f.row({detail:'42'});await settle();
 assert.match(f.$('supplier-results').innerHTML,/옵션 FREE/);assert.doesNotMatch(f.$('supplier-results').innerHTML,/상품 전체 재고/);
});

test('one simple choice labels common stock while multiple choices retain product total',async()=>{
 for(const choices of [[{id:'1',groupName:'컬러',name:'블랙'}],[{id:'1',groupName:'컬러',name:'블랙'},{id:'2',groupName:'컬러',name:'핑크'},{id:'3',groupName:'컬러',name:'옐로우'}],[{id:'1',groupName:'선물 포장',name:'O'},{id:'2',groupName:'선물 포장',name:'X'}],[],[{id:'1',groupName:'컬러',name:''}]]) {
  const result={offer:{mall:'NAVER_SMART_STORE',mallName:'닥스골프',price:145630,deliveryFee:0},match:{state:'SEARCH_RESULT'},state:'CONFIRMED',options:[{id:'13771629148',label:'상품 전체',stock:9,state:'AVAILABLE',stockScope:'PRODUCT',simpleChoices:choices}]};
  const listing={id:'L1',mall:'NAVER_SMART_STORE',store:{retailer:'롯데백화점',name:'본점'},preferred:true,current:true,selected:true,priceStatus:'CONFIRMED',referencePrice:145630,inventoryState:'AVAILABLE',result};
  const p={...product,selectedSupplier:listing};const d={...detailOf(p),comparison:{selected:listing,groups:[{mall:listing.mall,store:listing.store,listings:[listing]}],recommendations:[],recommendationStatus:{state:'READY'}}};
  const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([p])});await settle();f.row({detail:'42'});await settle();
  const single=choices.length===1&&choices[0].name==='블랙';
  const gift=choices.length===2&&choices[0].groupName==='선물 포장';
  assert.match(f.$('product-rows').innerHTML,single||gift?/재고 9개/:/상품 전체 재고 9개/);
  assert.match(f.$('detail-summary').innerHTML,single||gift?/재고 9개/:/상품 전체 재고 9개/);
  assert.match(f.$('supplier-results').innerHTML,single||gift?/재고 <strong>9개/:/상품 전체 재고 <strong>9개/);
  if(single){assert.doesNotMatch(f.$('product-rows').innerHTML,/블랙/);assert.match(f.$('supplier-results').innerHTML,/옵션 블랙/);assert.doesNotMatch(f.$('supplier-results').innerHTML,/상품 전체 재고/);}
  if(gift){assert.doesNotMatch(f.$('product-rows').innerHTML,/상품 전체 재고/);assert.doesNotMatch(f.$('supplier-results').innerHTML,/상품 전체 재고/);assert.match(f.$('supplier-results').innerHTML,/선물 포장: O \/ X/);}
  assert.equal(result.options[0].stockScope,'PRODUCT');assert.equal(result.options.length,1);
 }
});

test('list inventory badges require confirmed current stock and do not collapse mixed options',async()=>{
 const option=(stock,state)=>({id:String(stock),label:'FREE',stock,state});
 const cases=[
  {options:[option(35,'AVAILABLE')],expect:'재고 35개',absent:/구매 가능|product-badge bad/},
  {options:[option(0,'SOLD_OUT')],expect:'supplier-list-quantity is-unavailable',absent:/>품절<\/span>|구매 불가능/},
  {options:[option(12,'UNAVAILABLE')],expect:'supplier-list-quantity is-unavailable',absent:/>품절<\/span>|구매 불가능/},
  {options:[option(null,'UNAVAILABLE')],expect:'>구매 불가능</span>',absent:/재고 0개/},
  {options:[option(null,'STOCK_UNKNOWN')],expect:'수량 미제공',absent:/product-badge bad/},
  {options:[option(0,'SOLD_OUT')],current:false,expect:'이전 가격 및 재고',absent:/product-badge bad|is-unavailable/},
  {options:[option(0,'SOLD_OUT')],resultState:'FAILED',inventoryState:'FAILED',expect:'재고 조회 실패',absent:/product-badge bad|is-unavailable/},
  {options:[option(0,'SOLD_OUT'),option(4,'AVAILABLE')],expect:'재고 상세',absent:/product-badge bad|재고 4개/},
  {options:[option(0,'SOLD_OUT'),option(0,'SOLD_OUT')],expect:'>품절</span>',absent:/재고 0개/},
  {options:[option(0,'SOLD_OUT'),option(null,'STOCK_UNKNOWN')],resultState:'OPTIONS_PARTIAL',inventoryState:'OPTIONS_PARTIAL',expect:'재고 상세',absent:/product-badge bad|일부 옵션만 확인/},
  {options:[option(0,'SOLD_OUT'),option(3,'UNAVAILABLE')],expect:'>구매 불가능</span>',absent:/>품절<\/span>/}
 ];
 for(const c of cases){
  const listing={id:'L1',mall:'LFMALL',branchRequired:false,preferred:true,current:c.current!==false,referencePrice:40680,inventoryState:c.inventoryState||(c.options.length>1?'MULTIPLE':c.options[0].state),result:{match:{state:'SEARCH_RESULT'},state:c.resultState||'CONFIRMED',options:c.options}};
  const f=fixture({load:async()=>pageOf([{...product,selectedSupplier:listing}])});await settle();
  const html=f.$('product-rows').innerHTML;assert(html.includes(c.expect),JSON.stringify(c));assert.doesNotMatch(html,c.absent);
 }
});

test('list missing badge opens detail instead of expanding a duplicate explanation',async()=>{
 const l={id:'L1',mall:'LFMALL',branchRequired:false,preferred:true,current:false,selectedMissing:true,priceStatus:'MISSING',priceCheckedAt:'2026-09-29T19:00:00',referencePrice:40680,inventoryState:'UNCONFIRMED',result:{match:{state:'SEARCH_RESULT'},state:'CONFIRMED',options:[{id:'FREE',stock:0,state:'SOLD_OUT'}]}};
 const f=fixture({load:async()=>pageOf([{...product,selectedSupplier:l}])});await settle();
 const html=f.$('product-rows').innerHTML;
 assert.match(html,/selected-missing-button" data-detail="42"/);assert.doesNotMatch(html,/<details|검색 범위 밖|>품절</);
 assert.doesNotMatch(html,/이전 확인값|is-unavailable/);
 assert(html.indexOf('supplier-compact-stock')<html.indexOf('selected-missing-button'));
});

test('stale listings show previous stock as reference in summaries and cards',async()=>{
 const result={offer:{mall:'LFMALL',mallName:'LFmall',price:94000,deliveryFee:0},match:{state:'SEARCH_RESULT'},state:'CONFIRMED',options:[{id:'FREE',label:'FREE',stock:6,state:'AVAILABLE'}]};
 const listing={id:'L1',mall:'LFMALL',branchRequired:false,preferred:true,current:false,selected:true,selectable:true,priceStatus:'CHECKING',referencePrice:94000,inventoryState:'UNCONFIRMED',result};
 const p={...product,selectedSupplier:listing};const d={...detailOf(p),comparison:{selected:listing,groups:[{mall:'LFMALL',branchRequired:false,minPrice:null,maxPrice:null,listings:[listing]}],recommendations:[],recommendationStatus:{state:'PRICE_UNCONFIRMED'}}};
 const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([p])});await settle();
 assert.match(f.$('product-rows').innerHTML,/재고 6개/);assert.match(f.$('product-rows').innerHTML,/조회 중 - 이전 가격 및 재고/);assert.doesNotMatch(f.$('product-rows').innerHTML,/최신 재고 미확인|이전 재고|참고용/);
 f.row({detail:'42'});await settle();
 assert.match(f.$('detail-summary').innerHTML,/재고 6개/);assert.match(f.$('detail-summary').innerHTML,/조회 중 - 이전 가격 및 재고/);assert.doesNotMatch(f.$('detail-summary').innerHTML,/이전 재고|참고용/);
 const cards=f.$('supplier-results').innerHTML;
 assert.match(cards,/is-reference/);assert.match(cards,/조회 중 - 이전 가격 및 재고/);
 assert.doesNotMatch(cards,/product-badge good">구매 가능|이전 재고|이전 확인|참고용/);
 Object.assign(listing,{current:true,priceStatus:'CONFIRMED',inventoryState:'AVAILABLE'});f.row({detail:'42'});await settle();
 assert.match(f.$('detail-summary').innerHTML,/재고 6개 · 구매 가능/);assert.doesNotMatch(f.$('supplier-results').innerHTML,/is-reference|이전 가격 및 재고/);
});

test('store groups start collapsed with the stock-confirmed and selected listings pinned on top',async()=>{
 const offer=(id,price)=>({mall:'HI_THEHYUNDAI',mallName:'더현대Hi',mallProductId:id,price,deliveryFee:0});
 const make=(id,price,state,options,extra={})=>({id,mall:'HI_THEHYUNDAI',store:{id:'S1',retailer:'현대백화점',name:'목동점'},branchRequired:true,storeStatus:'CONFIRMED',preferred:true,current:true,selected:false,selectable:true,priceStatus:'CONFIRMED',referencePrice:price,inventoryState:'AVAILABLE',result:{offer:offer(id,price),match:{state:'SEARCH_RESULT'},state,options},...extra});
 const cheap=make('CHEAP',59000,'SKIPPED_SAME_STORE',[]),stocked=make('STOCK',60480,'CONFIRMED',[{id:'o',label:'없음',stock:48,state:'AVAILABLE'}]);
 const other=make('OTHER',61000,'SKIPPED_SAME_STORE',[]),chosen=make('CHOSEN',62000,'SKIPPED_SAME_STORE',[],{selected:true});
 const single=make('SINGLE',70000,'CONFIRMED',[{id:'o',label:'없음',stock:3,state:'AVAILABLE'}]);single.store={id:'S2',retailer:'현대백화점',name:'천호점'};
 const p={...product,selectedSupplier:chosen};
 const d={...detailOf(p),comparison:{selected:chosen,groups:[{id:'HI:S1',mall:'HI_THEHYUNDAI',store:cheap.store,selected:true,branchRequired:true,minPrice:59000,maxPrice:62000,listings:[cheap,stocked,other,chosen]},{id:'HI:S2',mall:'HI_THEHYUNDAI',store:single.store,branchRequired:true,minPrice:70000,maxPrice:70000,listings:[single]}],recommendations:[],recommendationStatus:{state:'READY'}}};
 const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([p])});await settle();f.row({detail:'42'});await settle();
 const html=f.$('supplier-results').innerHTML,restAt=html.indexOf('data-group-rest');
 assert.ok(html.indexOf('listing-STOCK')<html.indexOf('listing-CHOSEN')&&html.indexOf('listing-CHOSEN')<restAt,'재고 확인·선정 판매글이 접힌 영역 앞에 고정');
 assert.ok(restAt<html.indexOf('listing-CHEAP')&&html.indexOf('listing-CHEAP')<html.indexOf('listing-OTHER'),'나머지는 가격순으로 접힌 영역 안');
 assert.match(html,/data-group-rest="pref:HI:S1" hidden/);assert.match(html,/aria-expanded="false"[^>]*>나머지 2개 펼치기</);
 assert.equal((html.match(/data-group-toggle=/g)||[]).length,1,'판매글 1개 묶음에는 토글 없음');
});

test('a non-preferred selection stays inside the recommendation area instead of the separate selected section',async()=>{
 const store=(id,name)=>({id,retailer:'롯데백화점',name});
 const make=(id,price,st,extra={})=>({id,mall:'LOTTE_ON',store:st,branchRequired:true,storeStatus:'CONFIRMED',preferred:false,current:true,selected:false,selectable:true,priceStatus:'CONFIRMED',referencePrice:price,inventoryState:'AVAILABLE',result:{offer:{mall:'LOTTE_ON',mallName:'롯데ON',mallProductId:id,price,deliveryFee:0},match:{state:'SEARCH_RESULT'},state:'CONFIRMED',options:[{id:'o',label:'FREE',stock:2,state:'AVAILABLE'}]},...extra});
 const base={...make('BASE',94000,store('S0','본점')),mall:'HI_THEHYUNDAI',preferred:true};
 const chosen=make('REC',93000,store('S1','대구점'),{selected:true});
 const detailWith=(recommendations,state)=>{const p={...product,selectedSupplier:chosen};return {...detailOf(p),comparison:{selected:chosen,groups:[{id:'HI_THEHYUNDAI:S0',mall:'HI_THEHYUNDAI',store:base.store,branchRequired:true,minPrice:94000,maxPrice:94000,listings:[base]}],recommendations,recommendationStatus:{state}}};};
 let d=detailWith([],'SELECTION_CHANGED');
 const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([{...product,selectedSupplier:chosen}])});await settle();f.row({detail:'42'});await settle();
 let recs=f.$('recommendation-results').innerHTML;
 assert.match(recs,/supplier-group is-selected/);assert.match(recs,/listing-REC/);assert.match(recs,/선정 기준이 변경되었습니다/);
 assert.equal(f.$('selected-supplier-section').hidden,true);assert.equal(f.$('selected-supplier-results').innerHTML,'');
 // 같은 매장 추천 묶음이 있으면 새 묶음을 만들지 않고 합친다(가격순).
 const cheaper=make('CHEAP',91000,store('S1','대구점'),{recommendationSaving:2000});
 d=detailWith([{id:'LOTTE_ON:S1',mall:'LOTTE_ON',store:cheaper.store,branchRequired:true,minPrice:91000,maxPrice:91000,listings:[cheaper]}],'READY');f.row({detail:'42'});await settle();
 recs=f.$('recommendation-results').innerHTML;
 assert.equal((recs.match(/<section class="supplier-group/g)||[]).length,1);assert.match(recs,/is-selected/);assert.match(recs,/2개 판매글/);assert.match(recs,/91,000원 ~ 93,000원/);
 assert.equal(f.$('selected-supplier-section').hidden,true);
});

test('legacy ordinary Smartstore selection is reference-only and offers no manual reassignment',async()=>{
 const result={offer:{mall:'NAVER_SMART_STORE',mallName:'롯데백화점',url:'https://smartstore.naver.com/lotte/products/42',price:94000,deliveryFee:0},match:{state:'SEARCH_RESULT'},state:'CONFIRMED',options:[{id:'FREE',label:'FREE',stock:3,state:'AVAILABLE'}]};
 const listing={id:'L1',mall:'NAVER_SMART_STORE',url:result.offer.url,manual:true,store:{retailer:'롯데백화점',name:'본점'},storeStatus:'HISTORICAL',branchRequired:true,preferred:true,current:false,selected:true,selectable:false,priceStatus:'UNSUPPORTED_CHANNEL',referencePrice:94000,inventoryState:'UNCONFIRMED',result};
 const p={...product,selectedSupplier:listing};
 const d={...detailOf(p),comparison:{selected:listing,groups:[],recommendations:[],recommendationStatus:{state:'PRICE_UNCONFIRMED'}}};
 const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([p])});await settle();
 assert.match(f.$('product-rows').innerHTML,/네이버 스마트스토어/);assert.match(f.$('product-rows').innerHTML,/현재 조회 지원 대상 아님/);
 assert.doesNotMatch(f.$('product-rows').innerHTML,/네이버 쇼핑윈도/);
 f.row({detail:'42'});await settle();
 assert.match(f.$('detail-summary').innerHTML,/현재 조회 지원 대상 아님 - 이전 가격 및 재고/);
 assert.match(f.$('recommendation-results').innerHTML,/선정 매입처 가격 미확인으로 추천 보류/);
 assert.doesNotMatch(f.$('selected-supplier-results').innerHTML,/data-assign|data-unassign|data-pick/);
});

test('login retry and search cooldown stop spinner but keep polling until actual work resumes',async()=>{
 let status={productId:'42',status:'PENDING',runStatus:'RETRY_WAIT',runId:'7',nextRetryAt:'2026-09-28T01:30:00'};
 const f=fixture({load:async url=>url.endsWith('/refresh-status')?status:url.endsWith('/duplicates')?[]:url==='/api/products/42'?detailOf({...product,latestStatus:'PENDING'}):pageOf()});
 await settle();f.row({detail:'42'});await settle();await f.tick();
 assert.equal(f.$('detail-spinner').hidden,true);assert.match(f.$('detail-progress-text').textContent,/로그인 감지.*01:30/);assert.equal(f.$('detail-run-link').hidden,false);
 status={...status,runStatus:'RUNNING',nextRetryAt:null,nextSearchAt:'2026-09-28T01:31:00'};await f.tick();
 assert.equal(f.$('detail-spinner').hidden,true);assert.match(f.$('detail-progress-text').textContent,/다음 검색.*01:31/);assert.equal(f.$('detail-run-link').hidden,false);
 status={...status,status:'CHECKING',nextSearchAt:null};await f.tick();assert.equal(f.$('detail-spinner').hidden,false);
});


test('list hides historical store and option explanations but detail retains their evidence',async()=>{
 const listing={id:'L1',mall:'LFMALL',branchRequired:true,storeStatus:'HISTORICAL',store:{name:'천호점'},preferred:true,current:true,priceStatus:'CONFIRMED',referencePrice:10000,inventoryState:'OPTIONS_PARTIAL',result:{offer:{mall:'LFMALL',price:10000,deliveryFee:0},match:{state:'SEARCH_RESULT'},state:'OPTIONS_PARTIAL',options:[{id:'a',label:'FREE',stock:3,state:'AVAILABLE'}]}};
 const p={...product,latestStatus:'SOLD_OUT',selectedSupplier:listing};
 const d={...detailOf(p),comparison:{selected:listing,groups:[],recommendations:[]}};
 const f=fixture({load:async url=>url==='/api/products/42'?d:url.endsWith('/duplicates')?[]:pageOf([p])});await settle();
 const html=f.$('product-rows').innerHTML;
 assert.match(html,/product-badge bad[^"]*">품절/);assert.match(html,/재고 3개/);assert.doesNotMatch(html,/일부 옵션만 확인|이번 업체·지점 미확인|is-unavailable/);
 f.row({detail:'42'});await settle();
 assert.match(f.$('detail-summary').innerHTML,/일부 옵션만 확인/);assert.match(f.$('detail-summary').innerHTML,/이번 업체·지점 미확인/);
});
