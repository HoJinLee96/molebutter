// 실제 Thymeleaf 화면 + 격리된 모의 API. 실제 DB나 외부 쇼핑몰에 연결하지 않는다.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../molebutter-app/src/main/resources');
const stamp='2026-09-26T18:00:00';
const notices=[];
const malls={LFMALL:'LF몰',HAZZYS:'헤지스',NAVER_SMART_STORE:'네이버 쇼핑윈도',LOTTE_ON:'롯데온',LOTTE_IMALL:'롯데홈쇼핑',HI_THEHYUNDAI:'더현대Hi',HMALL:'현대Hmall'};
const paged=(items,page=0,size=20)=>({items:items.slice(page*size,(page+1)*size),page,totalPages:Math.ceil(items.length/size),totalElements:items.length});
const source=(mall,code,price,options,review=false)=>({offer:{mall,mallName:malls[mall],mallProductId:code,naverProductId:'NV-'+code,title:'헤지스 쇼퍼백 '+code,url:'https://www.hazzys.com/product.do?PROD_CD='+code,price,deliveryFee:3000,imageUrl:'https://fixture.test/bag.svg'},branch:{name:'목동점',state:'CONFIRMED',source:'네이버 상품명',evidence:'[현대백화점 목동점]'},match:{state:review?'REVIEW':'MATCHED',originalCode:code,comparisonCode:'HIBA311',message:review?'상품코드 확인 필요':'시즌 차이 · 원문 확인',season:'4F',color:'N2'},state:review?'REVIEW':'CONFIRMED',options,message:null});
const result={status:'PARTIAL',searchPrice:69000,searchMall:'LF몰',searchDeliveryFee:3000,checkedAt:stamp,message:'일부 매입처의 가격 또는 재고를 확인하지 못했습니다.',suppliers:[source('LFMALL','HIBA4F311N2',69000,[{id:'FREE',label:'네이비 / FREE',stock:5,state:'AVAILABLE'},{id:'BK',label:'BLACK',stock:0,state:'SOLD_OUT'}]),source('HAZZYS','HIBA6F311N2',75000,[{id:'M',label:'블랙 / M',stock:null,state:'STOCK_UNKNOWN'}]),source('NAVER_SMART_STORE','모델 미표기',10000,[],true)]};
result.suppliers[1].match.message='시즌 일치';
let brands=[{id:'1',name:'헤지스',codeBrand:'HAZZYS',revision:0},{id:'2',name:'닥스',codeBrand:'DAKS',revision:0}];
let products=Array.from({length:Number(process.env.PRODUCT_UI_COUNT)||65},(_,i)=>({id:String(9007199254740993n+BigInt(i)),brand:i===1?'':'헤지스',brandId:i===1?null:'1',brandKey:i===1?'':'HAZZYS',productCode:i===0?'HIBA6F311N2':`ABCD6F${String(i).padStart(3,'0')}BK`,comparisonCode:i===0?'HIBA311':i===1?'':`ABCD${String(i).padStart(3,'0')}`,codeType:i===1?'GENERAL':'LF_ACCESSORY',searchQuery:i===0?'HIBA311':`ABCD${String(i).padStart(3,'0')}`,searchMode:'AUTO',suggestedQuery:i===0?'HIBA311':`ABCD${String(i).padStart(3,'0')}`,managed:i!==1,revision:0,lookupRevision:0,duplicateCount:0,imageUrl:null,latestStatus:i===0?'PARTIAL':'NOT_CHECKED',latestAt:i===0?stamp:null,latestResult:i===0?result:null}));
// 최신화 이력: 실행 시각 기준으로 최근 약 8일에 걸친 종료 작업 45건 + 진행 중 작업 '7'. 날짜 필터는 한국 시간 문자열로 비교한다.
const kst=ms=>new Date(ms+9*3600e3).toISOString().slice(0,19),bootAt=Date.now();
const historyRuns=Array.from({length:45},(_,i)=>({id:String(200-i),status:i%9===4?'CANCELLED':'COMPLETED',triggerType:i%6===0?'SCHEDULED':'MANUAL',createdAt:kst(bootAt-(i+1)*4.5*3600e3),total:20,done:20,message:i%9===4?'자동 재개 중단됨':null,retryable:i%5===1?3:0}));
let sequence=300,runStatus='RUNNING',schedule={revision:0,scheduleEnabled:true,scheduleTime:'18:00'},lastScope=null,progressChecks=new Map();
const first=products[0].id;
function identity(input){const brand=brands.find(b=>b.id===input.brandId),code=(input.productCode||'').trim().toUpperCase(),type=input.codeType||(brand?.codeBrand?'LF_ACCESSORY':'GENERAL');const match=type==='LF_ACCESSORY'&&/^([A-Z]{4})[0-9][EF]([0-9]{3})[A-Z][A-Z0-9]$/.exec(code);return {brand:brand?.name||'',brandId:brand?.id??null,brandKey:brand?.codeBrand||'',productCode:code,codeType:type,comparisonCode:match?match[1]+match[2]:'',suggestedQuery:match?match[1]+match[2]:code,searchMode:'MANUAL'};}
let supplierStores=[{id:'S1',mall:'LFMALL',kind:'BRANCH',name:'목동점',revision:0,identityKey:'branch:목동점',aliases:['목동점']},{id:'S2',mall:'HAZZYS',kind:'ONLINE',name:'공식 온라인',revision:0,identityKey:'online',aliases:['공식 온라인']}];
let preferences={companyChoices:[{mall:"LOTTE_ON",kind:"COMPANY",name:"주식회사 LF"}],revision:0,branchRequirements:Object.fromEntries(Object.keys(malls).map(m=>[m,!['LFMALL','HAZZYS'].includes(m)])),stores:supplierStores,rules:Object.keys(malls).map((mall,i)=>({id:'R'+i,mall,storeId:null,revision:0}))};
function registerStore(input){const retailer=input.mall==='HI_THEHYUNDAI'&&input.kind==='BRANCH'?'현대백화점':input.retailer;const existing=supplierStores.find(s=>s.mall===input.mall&&s.kind===input.kind&&s.name===input.name&&s.retailer===retailer);if(existing)return existing;const saved={id:'S'+(++sequence),...input,retailer,identityKey:input.kind+':'+input.name,aliases:[input.name],revision:0};supplierStores.push(saved);preferences.stores=supplierStores;return saved;}
const pins=new Map([[first,'L1']]),assignments=new Map();
function comparison(p){
 const listings=p.id===first?result.suppliers.map((r,i)=>{const manual=assignments.get('L'+i),store=manual? supplierStores.find(s=>s.id===manual):i===0?supplierStores[0]:i===1?supplierStores[1]:null;
 const branchRequired=preferences.branchRequirements[r.offer.mall],ready=(!branchRequired||!!store)&&r.match.state==='MATCHED';
 return {id:'L'+i,revision:0,branchRequired,selectable:ready,selectionUnavailableReason:ready?null:'매장과 상품코드 판별을 먼저 확인해 주세요.',storeStatus:branchRequired?(store?'CONFIRMED':'UNCONFIRMED'):'NOT_REQUIRED',mall:r.offer.mall,store,manual:!!manual,conflict:false,preferred:true,current:!!p.latestResult,selected:pins.get(p.id)==='L'+i,priceStatus:p.latestResult?'CONFIRMED':'CHECKING',referencePrice:r.offer.price,deliveryFee:r.offer.deliveryFee,priceCheckedAt:stamp,inventoryState:r.options.length>1?'MULTIPLE':r.options[0]?.state||'UNCONFIRMED',url:r.offer.url,imageUrl:r.offer.imageUrl,result:r};}):[];
 const groups=listings.filter(l=>l.selectable).map(l=>({id:l.branchRequired?l.store.id:'mall:'+l.mall,branchRequired:l.branchRequired,mall:l.mall,store:l.branchRequired?l.store:null,selected:l.selected,minPrice:l.referencePrice,maxPrice:l.referencePrice,listings:[l]})).sort((a,b)=>a.minPrice-b.minPrice);
 return {groups,pending:[],excluded:[],recommendations:[],recommendationStatus:{state:pins.has(p.id)?'READY':'NO_SELECTION'},selected:listings.find(l=>l.selected)||null,stores:supplierStores,preferencesConfigured:preferences.rules.length>0};
}
const withSelection=p=>({...p,selectedSupplier:comparison(p).selected});
const detail=p=>({product:withSelection(p),comparison:comparison(p),suppliers:[],lastGoodResult:p.latestResult,history:paged(p.latestResult?[{id:'1',createdAt:stamp,legacy:false,payload:p.latestResult}]:[])});

function html(page,role){const file=path.resolve(__dirname,`../molebutter-app/target/ui-fixtures/${page}-${role}.html`);if(!fs.existsSync(file))throw Error('LayoutViewTest를 먼저 실행하세요.');return fs.readFileSync(file,'utf8');}
const server=http.createServer(async(req,res)=>{try{
 const u=new URL(req.url,'http://localhost'),url=u.pathname;res.setHeader('Cache-Control','no-store');
 if(['/','/products','/product-refresh','/settings','/attendance','/attendance-manage'].includes(url)){const role=u.searchParams.get('role')||(/fixtureRole=(ADMIN|PRODUCT)/.exec(req.headers.cookie||'')?.[1]??'ADMIN');res.setHeader('Set-Cookie',`fixtureRole=${role}; Path=/; SameSite=Lax`);res.setHeader('Content-Type','text/html; charset=utf-8');return res.end(html(url==='/'?'home':url.slice(1),role));}
 if(/^\/(js|css)\/[a-z.-]+$/.test(url)){res.setHeader('Content-Type',url.endsWith('.js')?'text/javascript':'text/css');return res.end(fs.readFileSync(path.join(root,'static',url)));}
 if(url==='/favicon.ico'){res.statusCode=204;return res.end();}
 const chunks=[];for await(const c of req)chunks.push(c);const body=Buffer.concat(chunks).toString(),input=body&&req.headers['content-type']?.includes('application/json')?JSON.parse(body):{};
 let data;
 if(url==='/api/auth/csrf')data={token:'fixture',headerName:'X-XSRF-TOKEN'};
 else if(['POST','PUT','DELETE'].includes(req.method)&&req.headers['x-xsrf-token']!=='fixture'){res.statusCode=403;return res.end(JSON.stringify({code:'INVALID_CSRF_TOKEN'}));}
 else if(url==='/api/inventory/summaries')data=(u.searchParams.get('productIds')||'').split(',').filter(Boolean).map(productId=>({productId,onHand:productId===first?5:0,pending:productId===first?2:0}));
 else if(url==='/fixture-state')data={lastScope,runStatus};
 else if(url==='/api/auth/signout')data=null;
 else if(url==='/api/attendance/current')data={serverNow:stamp,active:null,today:null};
 else if(['/api/attendance','/api/attendance/corrections','/api/attendance-manage/corrections','/api/attendance-manage/records','/api/attendance-manage/summary'].includes(url))data={items:[],page:0,totalPages:0,totalElements:0};
 else if(['/api/attendance/corrections/91','/api/attendance-manage/corrections/91'].includes(url))data={id:'91',userId:'1',userName:'검증 직원',workDate:'2026-09-20',revision:1,status:'APPROVED',reason:'누락 보완',before:null,after:{clockIn:'2026-09-20T09:00:00',clockOut:'2026-09-20T18:00:00',status:'COMPLETED',breaks:[]},requestedAt:stamp,reviewedAt:stamp,reviewerName:'관리자'};
 else if(url==='/api/notifications/summary')data={count:notices.filter(n=>!n.dismissed).length,latestId:notices.filter(n=>!n.dismissed).sort((a,b)=>BigInt(a.id)>BigInt(b.id)?-1:1)[0]?.id||null};
 else if(url==='/api/notifications'){const all=notices.filter(n=>!n.dismissed&&(!u.searchParams.get('cursor')||BigInt(n.id)<BigInt(u.searchParams.get('cursor')))).sort((a,b)=>BigInt(a.id)>BigInt(b.id)?-1:1);data={items:all.slice(0,20),nextCursor:all.length>20?all[19].id:null};}
 else if(/^\/api\/notifications\/\d+(?:\/target)?$/.test(url)){const n=notices.find(n=>n.id===url.split('/')[3]);if(!n)throw Error('알림 없음');if(req.method==='DELETE'){n.dismissed=true;data=null;}else data={url:n.target||null,reason:n.target?null:'대상이 삭제되었습니다.'};}
 else if(url==='/api/settings/payment-methods')data=[{id:'1',name:'카드',revision:0},{id:'2',name:'계좌이체',revision:0},{id:'3',name:'현금',revision:0}];
 else if(url==='/api/settings/suppliers')data=Object.entries(malls).map(([code,name])=>({code,name,stockSupported:true}));
 else if(url==='/api/settings/supplier-store-identities')data=[];
 else if(url.startsWith('/api/settings/preferred-suppliers')){const id=url.split('/')[4];if(['PUT','DELETE'].includes(req.method)){
 if(input.revision!==preferences.revision){res.statusCode=409;return res.end(JSON.stringify({message:'설정이 변경되었습니다.'}));}
 preferences.rules=preferences.rules.filter(r=>r.mall!==id);if(req.method==='PUT'){if(input.branchRequired!==undefined)preferences.branchRequirements[id]=input.branchRequired;const ids=[...(input.storeIds||[]),...(input.newStores||[]).map(s=>registerStore(s).id)];for(const storeId of input.scope==='ALL'?[null]:[...new Set(ids)])preferences.rules.push({id:'R'+(++sequence),mall:id,storeId,revision:0});}preferences.revision++;
 }else if(req.method==='POST'){if(url.endsWith('/delete'))preferences.rules=preferences.rules.filter(r=>r.id!==id);else if(id)Object.assign(preferences.rules.find(r=>r.id===id),input,{revision:input.revision+1});else preferences.rules.push({id:'R'+(++sequence),...input,revision:0});preferences.revision++;}data=preferences;}
 else if(url.startsWith('/api/settings/supplier-stores')){const id=url.split('/')[4];if(url.endsWith('/delete')){supplierStores=supplierStores.filter(s=>s.id!==id);data=null;}else if(id){data=supplierStores.find(s=>s.id===id);Object.assign(data,input,{revision:data.revision+1});}else {data=registerStore(input);preferences.revision++;}preferences.stores=supplierStores;}
 else if(/^\/api\/products\/\d+\/selection$/.test(url)){const p=products.find(p=>p.id===url.split('/')[3]);if(p.revision!==input.revision)throw Error('다른 작업에서 변경되었습니다.');input.supplierId?pins.set(p.id,input.supplierId):pins.delete(p.id);p.revision++;data=comparison(p);}
 else if(/^\/api\/products\/\d+\/suppliers\/[^/]+\/store$/.test(url)){const p=products.find(p=>p.id===url.split('/')[3]);const storeId=input.newStore?registerStore(input.newStore).id:input.storeId;assignments.set(url.split('/')[5],storeId);if(input.addPreferred&&!preferences.rules.some(r=>r.mall===input.newStore?.mall&&(!r.storeId||r.storeId===storeId))){preferences.rules.push({id:'R'+(++sequence),mall:supplierStores.find(s=>s.id===storeId).mall,storeId,revision:0});preferences.revision++;}p.revision++;data=comparison(p);}
 else if(url.startsWith('/api/settings/brands')){const id=url.split('/')[4],b=brands.find(b=>b.id===id);if(req.method==='POST'){if(id&&b.revision!==input.revision)throw Error('다른 작업에서 변경되었습니다.');if(url.endsWith('/delete')){if(products.some(p=>p.brandId===id))throw Error('사용 중인 브랜드입니다.');brands=brands.filter(b=>b.id!==id);data=null;}else if(b){b.name=input.name;b.revision++;products.filter(p=>p.brandId===id).forEach(p=>p.brand=b.name);data=b;}else {data={id:String(++sequence),name:input.name,codeBrand:'',revision:0};brands.push(data);}}else data=brands.map(b=>({...b,usageCount:products.filter(p=>p.brandId===b.id).length}));}
 else if(url==='/api/products/change-counts')data={selected:0,all:0};
 else if(url==='/api/products/code-preview')data=identity(input);
 else if(url==='/api/products/imports')data={totalRows:286,created:0,existing:65,duplicates:220,excluded:1,brandsAssigned:0,warnings:['289행: 상품코드 누락']};
 else if(url==='/api/products/delete'){const ids=input.products.map(p=>p.id);products=products.filter(p=>!ids.includes(p.id));data=null;}
 else if(url==='/api/products/bulk'){for(const v of input.products){const p=products.find(p=>p.id===v.id);if(p.revision!==v.revision)throw Error('다른 작업에서 변경되었습니다.');if(input.managed!=null)p.managed=input.managed;if(input.brandId!=null)Object.assign(p,identity({...p,...input}));p.revision++;}data=null;}
 else if(url==='/api/products/infer-brands')data={assigned:0,preserved:input.products.length,unresolved:0};
 else if(url==='/api/products'&&req.method==='POST'){data={...products[0],...identity(input),id:String(++sequence),revision:0,lookupRevision:0,managed:true,latestStatus:'NOT_CHECKED',latestResult:null,latestAt:null};data.searchQuery=input.searchQuery.trim();products.unshift(data);}
 else if(url==='/api/products'){let rows=products.filter(p=>!u.searchParams.get('q')||[p.brand,p.productCode,p.searchQuery].some(s=>s.includes(u.searchParams.get('q'))));const mode=u.searchParams.get('mode'),status=u.searchParams.get('status');if(mode==='AUTO')rows=rows.filter(p=>p.managed);if(mode==='MANUAL')rows=rows.filter(p=>!p.managed);if(status)rows=rows.filter(p=>p.latestStatus===status);data=paged(rows.map(withSelection),Number(u.searchParams.get('page')),Number(u.searchParams.get('size')||20));}
 else if(/\/api\/products\/\d+\/(duplicates|history)$/.test(url)){data=url.endsWith('duplicates')?[]:detail(products.find(p=>p.id===url.split('/')[3])).history;}
 else if(/^\/api\/products\/\d+\/refresh-status$/.test(url)){const id=url.split('/')[3],p=products.find(p=>p.id===id);let checks=progressChecks.get(id);if(checks!=null){checks++;progressChecks.set(id,checks);if(checks>=2){p.latestStatus='PARTIAL';p.latestResult=result;progressChecks.delete(id);}}data={productId:id,status:p.latestStatus,runId:'7',runStatus,message:null};}
 else if(/^\/api\/products\/\d+$/.test(url)){const p=products.find(p=>p.id===url.split('/').at(-1));if(req.method==='POST'){Object.assign(p,identity(input));p.searchQuery=input.searchQuery.trim();p.revision++;}data=req.method==='GET'?detail(p):p;}
 else if(url==='/api/product-refresh/settings'){if(req.method==='POST')schedule={...input,revision:input.revision+1};data=schedule;}
 else if(url==='/api/product-refresh'){if(req.method==='POST'){lastScope=input;runStatus='RUNNING';for(const id of input.ids||[]){const p=products.find(p=>p.id===id);if(p){p.latestStatus='CHECKING';p.latestResult=null;progressChecks.set(id,0);}}data={id:'7'};}else{const q=u.searchParams,size=Number(q.get('size')||20),page=Number(q.get('page')||0),from=q.get('from')||'',to=q.get('to')||'',status=q.get('status')||'',failed=q.get('failed')==='true';
  if(page<0||![20,50,100].includes(size)){res.statusCode=400;return res.end(JSON.stringify({code:'BAD_REQUEST',message:'페이지와 표시 개수(20·50·100)를 확인해 주세요.'}));}
  const current={id:'7',status:runStatus,triggerType:'MANUAL',createdAt:kst(bootAt),total:2,done:1,message:null,retryable:['COMPLETED','CANCELLED'].includes(runStatus)?1:0},all=[current,...historyRuns];
  const rows=all.filter(r=>(!from||r.createdAt.slice(0,10)>=from)&&(!to||r.createdAt.slice(0,10)<=to)&&(!status||r.status===status)&&(!failed||r.retryable>0));
  data={...paged(rows,page,size),active:all.filter(r=>['RUNNING','PAUSED','BLOCKED','RETRY_WAIT'].includes(r.status)),selected:all.find(r=>r.id===q.get('run'))||null,stockLookupBlock:null};}}
 else if(/^\/api\/product-refresh\/(?!7$)\d+$/.test(url))data=paged([]);
 else if(url==='/api/product-refresh/7')data=paged([{productId:first,productCode:'HIBA6F311N2',comparisonCode:'HIBA311',status:'PARTIAL',result}]);
 else if(/^\/api\/product-refresh\/7\/(pause|resume|cancel|retry)$/.test(url)){runStatus={pause:'PAUSED',resume:'RUNNING',cancel:'CANCELLED',retry:'RUNNING'}[url.split('/').at(-1)];data=url.endsWith('retry')?{id:'7'}:null;}
 else {res.statusCode=404;return res.end(JSON.stringify({message:'fixture route missing: '+url}));}
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify({code:'SUCCESS',data}));
}catch(err){res.statusCode=409;res.end(JSON.stringify({code:'CONFLICT',message:err.message}));}});
if(require.main===module)server.listen(0,'127.0.0.1',()=>console.log('UI fixture: http://127.0.0.1:'+server.address().port+'/products'));
module.exports={server,first,notices};
