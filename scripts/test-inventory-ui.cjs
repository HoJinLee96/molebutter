// Rendered Thymeleaf and real frontend code; isolated mock APIs, no production data.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../src/main/resources'),fixtures=path.resolve(__dirname,'../target/ui-fixtures');
let role='ADMIN',orders=[],items=[],movements=[],createRequests=0,ids=100,paymentMethods=[{id:'1',name:'카드',revision:0},{id:'2',name:'계좌이체',revision:0},{id:'3',name:'현금',revision:0}];
const fixtureBrands=[{id:'1',name:'헤지스'},{id:'2',name:'닥스'}];
const productBrand=p=>p.brandId||fixtureBrands.find(b=>b.name===p.brand)?.id||null;
const products=Array.from({length:22},(_,n)=>({id:String(n+1),brand:'헤지스',productCode:n===0?'HIBA6F311BK':'CODE-'+n,searchQuery:'헤지스 가방',imageUrl:n===0?'https://images.test/product.svg':null}));
const paged=rows=>({items:rows,page:0,totalPages:rows.length?1:0,totalElements:rows.length});
const movementRequests=new Map(),movementAttempts=[];
const deleteAttempts=[];
const redact=row=>role==='ADMIN'?row:Object.fromEntries(Object.entries(row).filter(([k])=>!['unitPrice','remainingAmount','orderNumber','orderUrl','paymentMethod','paymentMethodId','paymentAmount','purchaseAmount','paymentAlias','paidOn','privateNote','refundStatus','refundAmount','refundedOn','refundRevision','deleteToken','deleteBlockedReason'].includes(k)));
const receiptTotals=i=>{const records=movements.filter(m=>m.itemId===i.id&&!m.reversed);return {receivedQuantity:records.filter(m=>m.kind==='RECEIPT').reduce((n,m)=>n+m.quantity,0),cancelledQuantity:records.filter(m=>m.kind==='CANCEL_PENDING').reduce((n,m)=>n+m.quantity,0)};};
const decorateItem=i=>{const p=products.find(p=>p.id===i.productId),{purchasedCode,purchasedName,currentProductCode,location,...row}=i;return {...row,...receiptTotals(i),productCode:p.productCode,brand:p.brand,imageUrl:p.imageUrl,remainingAmount:i.unitPrice==null?null:(BigInt(i.unitPrice)*BigInt(i.onHand)).toString(),purchaseDeleted:!!orders.find(o=>o.id===i.purchaseId)?.deletedAt};};
const orderData=o=>{const lines=items.filter(i=>i.purchaseId===o.id).map(decorateItem),onHand=lines.reduce((n,i)=>n+i.onHand,0),pending=lines.reduce((n,i)=>n+i.pending,0),refunds=movements.filter(m=>lines.some(i=>i.id===m.itemId));return {...redact({...o,onHand,pending,orderedQuantity:lines.reduce((n,i)=>n+i.orderedQuantity,0),receivedQuantity:lines.reduce((n,i)=>n+i.receivedQuantity,0),cancelledQuantity:lines.reduce((n,i)=>n+i.cancelledQuantity,0),purchaseAmount:lines.some(i=>i.unitPrice==null)?null:lines.reduce((n,i)=>n+BigInt(i.orderedQuantity)*BigInt(i.unitPrice),0n).toString(),deleteToken:JSON.stringify([o.revision,lines.map(i=>[i.id,i.revision]),refunds.map(m=>[m.id,m.refundRevision])]),deleteBlockedReason:o.deletedAt?'이미 삭제된 구매 주문입니다.':onHand?'보유 재고가 남아 있어 삭제할 수 없습니다.':refunds.some(m=>m.refundStatus==='PENDING')?'환불 대기 기록이 남아 있어 삭제할 수 없습니다.':null}),items:lines.map(redact)};};
const pageFor=(u,rows)=>{const size=Number(u.searchParams.get('size')||20),page=Number(u.searchParams.get('page')||0);return {items:rows.slice(page*size,(page+1)*size),page,totalPages:Math.ceil(rows.length/size),totalElements:rows.length};};
const codeKey=p=>p.productCode?.trim().toUpperCase()||'ID:'+p.id;
const groupIds=id=>{const product=products.find(p=>p.id===id);return products.filter(p=>codeKey(p)===codeKey(product)).map(p=>p.id);};
const stockGroups=()=>{
 const live=items.filter(i=>!orders.find(o=>o.id===i.purchaseId)?.deletedAt),keys=[...new Set(live.map(i=>codeKey(products.find(p=>p.id===i.productId))))];
 return keys.map(key=>{const members=products.filter(p=>codeKey(p)===key).sort((a,b)=>Number(!!a.productDeleted)-Number(!!b.productDeleted)||Number(a.id)-Number(b.id)),rep=members[0],lots=live.filter(i=>members.some(p=>p.id===i.productId)),options=[];
  for(const i of lots){let option=options.find(o=>o.color===(i.color||'')&&o.size===(i.size||''));if(!option){option={color:i.color||'',size:i.size||'',onHand:0,pending:0};options.push(option);}option.onHand+=i.onHand;option.pending+=i.pending;}
  return {productId:rep.id,brandId:productBrand(rep),productCode:rep.productCode.trim().toUpperCase(),brand:rep.brand,imageUrl:rep.imageUrl,productDeleted:!!rep.productDeleted,onHand:lots.reduce((n,i)=>n+i.onHand,0),pending:lots.reduce((n,i)=>n+i.pending,0),itemCount:lots.length,remainingAmount:lots.some(i=>i.onHand>0&&i.unitPrice==null)?null:lots.reduce((n,i)=>n+BigInt(i.unitPrice||0)*BigInt(i.onHand),0n).toString(),options};
 }).sort((a,b)=>a.productCode.localeCompare(b.productCode));
};
const matchingStocks=u=>{const filter=u.searchParams.get('productId'),brand=u.searchParams.get('brandId'),q=(u.searchParams.get('q')||'').toLowerCase();return stockGroups().filter(r=>(!filter||groupIds(filter).includes(r.productId))&&(!brand||(brand==='UNASSIGNED'?!r.brandId:r.brandId===brand))&&(!q||[r.productCode,r.brand,...items.filter(i=>groupIds(r.productId).includes(i.productId)).map(i=>products.find(p=>p.id===i.productId).searchQuery)].join(' ').toLowerCase().includes(q)));};
const server=http.createServer(async(req,res)=>{
 try {
  const u=new URL(req.url,'http://fixture'),p=u.pathname;
  if(p==='/settings'){res.setHeader('Content-Type','text/html');return res.end(fs.readFileSync(path.join(fixtures,`settings-${role}.html`)));}
  if(p==='/inventory'){res.setHeader('Content-Type','text/html');return res.end(fs.readFileSync(path.join(fixtures,`inventory-${role}.html`)));}
  if(p.startsWith('/js/')||p.startsWith('/css/')){res.setHeader('Content-Type',p.endsWith('.js')?'application/javascript':'text/css');return res.end(fs.readFileSync(path.join(root,'static',p)));}
  let raw='';for await(const chunk of req)raw+=chunk;const body=raw?JSON.parse(raw):{},get=req.method==='GET';let data;
  if(p==='/api/notifications/summary')data={count:0,latestId:null};
  else if(p==='/api/notifications')data={items:[],nextCursor:null};
  else if(p==='/api/auth/csrf')data={token:'fixture',headerName:'X-XSRF-TOKEN'};
  else if(p==='/api/settings/brands')data=fixtureBrands;
  else if(p==='/api/settings/suppliers')data=[];
  else if(p==='/api/supplier-preferences')data=[];
  else if(p==='/api/product-refresh/settings')data={revision:0,scheduleEnabled:false,scheduleTime:'18:00'};
  else if(p==='/api/settings/payment-methods'){
   if(!get)paymentMethods.push({id:String(++ids),name:body.name,revision:0});data=get?paymentMethods:paymentMethods.at(-1);
  }
  else if(p.startsWith('/api/settings/payment-methods/')){
   const method=paymentMethods.find(m=>m.id===p.split('/')[4]);assert.equal(body.revision,method.revision);
   if(p.endsWith('/delete'))paymentMethods=paymentMethods.filter(m=>m!==method);else {method.name=body.name;method.revision++;}data=method;
  }
  else if(p==='/api/products'){const q=u.searchParams.get('q')||'',page=Number(u.searchParams.get('page')||0),found=products.filter(p=>p.productCode.includes(q)||p.brand.includes(q));data={items:found.slice(page*20,(page+1)*20),page,totalPages:Math.ceil(found.length/20),totalElements:found.length};}
  else if(p==='/api/products/1')data={product:products[0]};
  else if(p==='/api/inventory/stock-products')data=pageFor(u,matchingStocks(u).map(redact));
  else if(p==='/api/inventory/stock-totals')data={totalOnHand:stockGroups().reduce((n,i)=>n+BigInt(i.onHand),0n).toString(),filteredOnHand:matchingStocks(u).reduce((n,i)=>n+BigInt(i.onHand),0n).toString()};
  else if(/^\/api\/inventory\/stock-products\/\d+$/.test(p)){const id=p.split('/').at(-1),row=stockGroups().find(r=>groupIds(id).includes(r.productId));if(!row)throw Object.assign(Error('재고 상품을 찾을 수 없습니다.'),{status:404});data=redact(row);}
  else if(p==='/api/inventory/items')data=pageFor(u,items.filter(i=>!orders.find(o=>o.id===i.purchaseId)?.deletedAt&&(u.searchParams.get('state')!=='ON_HAND'||i.onHand>0)&&(!u.searchParams.get('groupProductId')||groupIds(u.searchParams.get('groupProductId')).includes(i.productId))).map(i=>redact(decorateItem(i))));
  else if(/^\/api\/inventory\/items\/\d+$/.test(p)){
   const i=items.find(i=>i.id===p.split('/').at(-1));if(!get){if(body.revision!==i.revision)throw Object.assign(Error('다른 작업에서 상품이 변경되었습니다.'),{status:409});if(body.orderedQuantity!=null)i.pending+=body.orderedQuantity-i.orderedQuantity;Object.assign(i,body);i.revision++;}data=redact(decorateItem(i));
  }
  else if(/^\/api\/inventory\/items\/\d+\/movements$/.test(p)){
   const i=items.find(i=>i.id===p.split('/')[4]);if(!get){const key=req.headers['x-operation-id'],fingerprint=JSON.stringify({item:i.id,body});assert.match(key,/^[0-9a-f-]{36}$/);movementAttempts.push({key,itemId:i.id,body});
    if(movementRequests.has(key)){assert.equal(movementRequests.get(key),fingerprint);res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({code:'SUCCESS',data:redact(decorateItem(i))}));}
    if(body.revision!==i.revision)throw Object.assign(Error('다른 작업에서 수량이 변경되었습니다.'),{status:409});
    const qty=body.quantity;if(body.kind==='RECEIPT'&&qty>i.pending)throw Object.assign(Error('미입고 수량 범위를 초과했습니다.'),{status:409});
    const original=movements.find(m=>m.id===body.referenceId);const dh=body.kind==='RECEIPT'||body.kind==='CUSTOMER_RETURN'?qty:body.kind==='REVERSE'?-original.handDelta:body.kind==='CANCEL_PENDING'?0:-qty,dp=body.kind==='RECEIPT'?-qty:body.kind==='CANCEL_PENDING'?-qty:body.kind==='REVERSE'?-original.pendingDelta:0;
    assert.equal(body.location,undefined);i.onHand+=dh;i.pending+=dp;i.revision++;i.remainingAmount=i.unitPrice==null?null:i.unitPrice*i.onHand;
    if(body.kind==='REVERSE')original.reversed=true;
    movements.unshift({id:String(++ids),itemId:i.id,productId:i.productId,purchasedCode:i.purchasedCode,kind:body.kind,quantity:qty,handDelta:dh,pendingDelta:dp,occurredAt:body.occurredAt,reason:body.reason,referenceId:body.referenceId,referenceNumber:body.referenceNumber,actorName:'재고 담당자',refundStatus:'NONE',refundAmount:null,refundRevision:0,reversed:false});
    movementRequests.set(key,fingerprint);
   }data=redact(decorateItem(i));
  }
  else if(p==='/api/inventory/purchases'){
   if(get)data=paged(orders.filter(o=>!o.deletedAt).map(orderData));
   else {
    assert.match(req.headers['x-operation-id'],/^[0-9a-f-]{36}$/);createRequests++;const o={id:String(++ids),revision:0,...body,paymentMethod:paymentMethods.find(m=>m.id===body.paymentMethodId)?.name||''};delete o.items;orders.push(o);
    for(const x of body.items)items.push({id:String(++ids),purchaseId:o.id,productId:x.productId,revision:0,location:'',purchasedOn:o.purchasedOn,supplierName:o.supplierName,onHand:x.receivedQuantity||0,pending:x.orderedQuantity-(x.receivedQuantity||0),remainingAmount:x.unitPrice==null?null:x.unitPrice*(x.receivedQuantity||0),...x});
    data=orderData(o);
   }
  }
  else if(/^\/api\/inventory\/purchases\/\d+\/delete$/.test(p)){
   const o=orders.find(o=>o.id===p.split('/')[4]),key=req.headers['x-operation-id'],fingerprint=JSON.stringify(body);deleteAttempts.push({key,orderId:o.id,fingerprint});
   if(role!=='ADMIN')throw Object.assign(Error('권한 없음'),{status:403});
   if(o.deletedAt){if(o.deleteKey!==key||o.deleteFingerprint!==fingerprint)throw Object.assign(Error('이미 삭제된 구매 주문입니다.'),{status:409});}
   else {
    const current=orderData(o);if(body.revision!==o.revision||body.deleteToken!==current.deleteToken)throw Object.assign(Error('주문 항목이 변경되었습니다.'),{status:409});if(current.deleteBlockedReason)throw Object.assign(Error(current.deleteBlockedReason),{status:409});
    for(const i of items.filter(i=>i.purchaseId===o.id&&i.pending>0)){movements.unshift({id:String(++ids),itemId:i.id,productId:i.productId,purchasedCode:i.purchasedCode,kind:'CANCEL_PENDING',quantity:i.pending,handDelta:0,pendingDelta:-i.pending,occurredAt:'2026-10-02T12:00:00',reason:'구매 주문 삭제로 미입고 취소',actorName:'관리자',refundStatus:'NONE',refundRevision:0});i.pending=0;i.revision++;}
    Object.assign(o,{deletedAt:'2026-10-02T12:00:00',deleteKey:key,deleteFingerprint:fingerprint,revision:o.revision+1});
   }data=orderData(o);
  }
  else if(/^\/api\/inventory\/purchases\/\d+$/.test(p)){
   const o=orders.find(o=>o.id===p.split('/').at(-1));if(!get){assert.equal(body.revision,o.revision);const method=body.paymentMethodId===o.paymentMethodId?o.paymentMethod:paymentMethods.find(m=>m.id===body.paymentMethodId)?.name||body.paymentMethod||'';Object.assign(o,body,{paymentMethod:method});o.revision++;}data=orderData(o);
  }
  else if(p==='/api/inventory/movements')data=pageFor(u,movements.map(m=>({...m,productCode:products.find(p=>p.id===items.find(i=>i.id===m.itemId)?.productId)?.productCode,purchaseId:items.find(i=>i.id===m.itemId)?.purchaseId,color:items.find(i=>i.id===m.itemId)?.color,size:items.find(i=>i.id===m.itemId)?.size})).filter(m=>(!u.searchParams.get('itemId')||m.itemId===u.searchParams.get('itemId'))&&(!u.searchParams.get('groupProductId')||groupIds(u.searchParams.get('groupProductId')).includes(m.productId)&&!orders.find(o=>o.id===m.purchaseId)?.deletedAt)).map(m=>redact({...m,purchaseDeleted:!!orders.find(o=>o.id===m.purchaseId)?.deletedAt})));
  else if(p.endsWith('/refund')){const m=movements.find(m=>m.id===p.split('/')[4]);Object.assign(m,{refundStatus:body.status,refundAmount:body.amount,refundedOn:body.refundedOn,refundRevision:m.refundRevision+1});data=m;}
  else throw Error('Unknown route: '+p);
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify({code:'SUCCESS',data}));
 }catch(err){res.statusCode=err.status||400;res.end(JSON.stringify({message:err.message}));}
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,channel:'chrome'}),base='http://127.0.0.1:'+server.address().port;
 try {
  for(const width of [1440,390]) {
   role='ADMIN';orders=[];items=[];movements=[];createRequests=0;movementRequests.clear();movementAttempts.length=0;
   const context=await browser.newContext({viewport:{width,height:width===390?844:1080}}),page=await context.newPage(),errors=[];
   page.setDefaultTimeout(8000);page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',r=>r.abort());
   await page.goto(base+'/inventory');await page.locator('#purchase-create').click();
   const purchase=page.locator('#purchase-form'),row=page.locator('#purchase-items .inventory-purchase-item').first();
   await purchase.locator('[name=purchasedOn]').fill('2026-10-01');assert.equal(await purchase.locator('[name=paidOn]').inputValue(),'2026-10-01');
   await purchase.locator('[name=orderUrl]').fill('https://www.lfmall.co.kr/order/123');
   assert.equal(await purchase.locator('[name=supplierName]').inputValue(),'LF몰');
   await purchase.locator('[name=supplierName]').fill('직접 매장');await purchase.locator('[name=orderUrl]').fill('https://www.hazzys.com/order/123');
   assert.equal(await purchase.locator('[name=supplierName]').inputValue(),'직접 매장');
   await row.locator('[data-product-query]').click();await page.locator('[data-choose-product="1"]').click();

   // Frontend arithmetic must preserve unknown, zero and integers above Number.MAX_SAFE_INTEGER.
   assert.equal(await page.locator('#purchase-amount').inputValue(),'미확인');await row.locator('[name=unitPrice]').fill('1');
   for(let k=0;k<10;k++){await page.locator('#purchase-add-item').click();const added=page.locator('#purchase-items .inventory-purchase-item').last();await added.locator('[name=orderedQuantity]').fill('1000000');await added.locator('[name=unitPrice]').fill('1000000000');}
   assert.equal(await page.locator('#purchase-amount').inputValue(),'10,000,000,000,000,001원');
   await page.locator('#purchase-items').evaluate(el=>[...el.children].slice(1).forEach(row=>row.querySelector('[data-remove]').click()));
   await row.locator('[name=orderedQuantity]').fill('5');await row.locator('[name=unitPrice]').fill('50000');
   await page.locator('#purchase-add-item').click();const second=page.locator('#purchase-items .inventory-purchase-item').last();
   assert.equal(await page.locator('#purchase-amount').inputValue(),'미확인');
   await second.locator('[data-product-query]').click();await page.locator('[data-choose-product="1"]').click();
   await second.locator('[name=orderedQuantity]').fill('3');await second.locator('[name=unitPrice]').fill('0');
   assert.equal(await page.locator('#purchase-amount').inputValue(),'250,000원');
   await purchase.locator('[name=paymentMethodId]').selectOption('1');await purchase.locator('[name=privateNote]').fill('결제 메모');
   await purchase.locator('button[type=submit]').click();await page.locator('#purchase-view-dialog').waitFor({state:'visible'});
   assert.equal(createRequests,1);assert.equal(items.reduce((n,i)=>n+i.onHand,0),0);assert.equal(items.reduce((n,i)=>n+i.pending,0),8);
   await page.locator('[data-close=purchase-view-dialog]').click();const order=orders[0],item=items[0];
   await page.locator('[data-tab=items]').click();await page.locator('[data-stock-product="1"]').waitFor();
   assert.equal(await page.locator('#inventory-results tbody tr').count(),1);

   // Receive only the selected item. A lost response is retried with the same identity.
   await page.locator('[data-tab=purchases]').click();await page.locator(`[data-order-receipt="${order.id}"]`).click();
   const receipt=page.locator(`[data-receipt-item="${item.id}"]`);await receipt.locator('[name=quantity]').fill('2');
   const receiptUrl=`**/api/inventory/items/${item.id}/movements`;let loseResponse=true;
   await page.route(receiptUrl,async r=>{if(loseResponse){loseResponse=false;await r.fetch();await r.abort('failed');}else await r.continue();});
   await receipt.locator('button').click();await page.locator('#purchase-receipt-error').waitFor({state:'visible'});
   await receipt.locator('button').click();await page.locator('#purchase-receipt-success').waitFor({state:'visible'});await page.unroute(receiptUrl);
   assert.equal(item.onHand,2);assert.equal(item.pending,3);assert.equal(items[1].onHand,0);
   assert.equal(movements.filter(m=>m.kind==='RECEIPT').length,1);assert.equal(movementAttempts[0].key,movementAttempts[1].key);
   await page.locator('[data-close=purchase-receipt-dialog]').click();

   // Preserve legacy multiline notes when editing unrelated fields.
   order.privateNote='결제 첫 줄\n결제 둘째 줄';order.paymentAmount=0;item.publicNote='상품 첫 줄\n상품 둘째 줄';
   await page.locator(`[data-order="${order.id}"]`).click();await page.locator('#purchase-view-dialog').waitFor({state:'visible'});
   await page.locator('#purchase-edit').click();await page.locator('#purchase-view-form [name=supplierName]').fill('수정 매장');
   await page.locator('#purchase-save').click();await page.waitForFunction(()=>document.querySelector('#purchase-view-form [name=supplierName]').readOnly);
   assert.equal(order.privateNote,'결제 첫 줄\n결제 둘째 줄');assert.equal(order.paymentAmount,0);
   const card=page.locator(`[data-purchase-product="${item.id}"]`);
   await card.locator('[data-edit-product]').click();await card.locator('[name=size]').fill('L');await card.locator('[data-save-product]').click();
   await page.waitForFunction(id=>document.querySelector(`[data-purchase-product="${id}"] [name=size]`).readOnly,item.id);
   assert.equal(item.publicNote,'상품 첫 줄\n상품 둘째 줄');
   await card.locator('[data-cancel-pending]').click();await page.locator('#movement-form [name=quantity]').fill('1');await page.locator('#movement-submit').click();
   await page.locator('#movement-dialog').waitFor({state:'hidden'});assert.equal(item.pending,2);
   await card.locator('[data-item-history] summary').click();await card.locator('[data-reverse]').first().click();
   await page.locator('#movement-form [name=reason]').fill('입력 오류');await page.locator('#movement-submit').click();
   await page.locator('#movement-dialog').waitFor({state:'hidden'});assert.equal(item.pending,3);assert.equal(item.onHand,2);
   fs.mkdirSync('target/ui-check',{recursive:true});await page.screenshot({path:`target/ui-check/inventory-smoke-${width}.png`});
   await page.locator('[data-close=purchase-view-dialog]').click();await page.locator('[data-tab=items]').click();
   await page.waitForFunction(()=>document.querySelector('#inventory-total-quantity').textContent==='2개');

   role='PRODUCT';await page.goto(base+'/inventory');await page.locator('[data-stock-product="1"]').click();
   await page.locator(`#stock-items [data-item="${item.id}"]`).click();await page.locator('#purchase-view-dialog').waitFor({state:'visible'});
   assert.equal(await page.locator('#purchase-create,#purchase-edit,[name=unitPrice],[name=privateNote]').count(),0);
   await card.locator('[data-edit-product]').click();assert(await card.locator('[name=orderedQuantity]').evaluate(e=>e.readOnly));
   await card.locator('[name=color]').fill('블랙');await card.locator('[data-save-product]').click();
   await page.waitForFunction(id=>document.querySelector(`[data-purchase-product="${id}"] [name=color]`).readOnly,item.id);
   assert.equal(item.color,'블랙');assert.deepEqual(errors,[]);await context.close();
   console.log(`PASS inventory smoke: exact sums, order/pending stock, per-item partial receipt/retry, edit/memo preservation, cancellation/reversal and PRODUCT permissions (${width}px)`);
  }
 }finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(err=>{console.error(err);process.exitCode=1;server.close();});
