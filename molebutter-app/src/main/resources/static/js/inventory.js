(() => {
    'use strict';
    const $ = id => document.getElementById(id), admin = document.body.dataset.inventoryAdmin === 'true';
    const escape = AppUI.escape;
    const labels = {RECEIPT:'입고',CANCEL_PENDING:'미입고 취소',SALE_OUT:'판매 출고',SUPPLIER_RETURN:'매입 반품',CUSTOMER_RETURN:'고객 반품 입고',DISPOSE:'폐기',ADJUST_IN:'실사 정정 +',ADJUST_OUT:'실사 정정 −',REVERSE:'기록 취소',ORDER_INCREASE:'주문 수량 증가',ORDER_DECREASE:'주문 수량 감소',NONE:'기록 없음',PENDING:'환불 대기',COMPLETED:'환불 완료'};
    const money = v => v == null ? '미확인' : BigInt(v).toLocaleString('ko-KR') + '원';
    const now = () => new Date(Date.now() + 9 * 3600000).toISOString().slice(0,16);
    const params = new URLSearchParams(location.search);
    let tab='items',page=0,productId=params.get('product')||'',currentOrder=null,currentRefund=null,movementRows=[],busy=false,loadSequence=0;
    const requestKeys = new WeakMap(), noteValues=new WeakMap();let deletingOrder=null;
    let orderEditing=false,editingItemId=null,orderSequence=0;
    const itemHistories=new Map(),historyPages=new Map(),historySequences=new Map();
    let currentStock=null,stockItemPage=0,stockMovementPage=0,stockMovementRows=[],stockSequence=0;
    function message(id,value) { const el=$(id); if(el){el.textContent=value||'';el.hidden=!value;} }
    function field(form,name){return form.elements.namedItem(name);}
    function value(form,name){const el=field(form,name),note=el&&noteValues.get(el);return note&&!note.edited?note.original:el?.value.trim()||'';}
    function values(form,names){return Object.fromEntries(names.map(n=>[n,value(form,n)]));}
    function fill(form,row){for(const [key,val] of Object.entries(row)){const el=field(form,key);if(!el)continue;if(['privateNote','publicNote'].includes(key)){const original=String(val??'');el.value=original.replace(/\r\n|\r|\n/g,' ');noteValues.set(el,{original,edited:false});}else el.value=val??'';}}
    document.addEventListener('input',ev=>{const note=noteValues.get(ev.target);if(note)note.edited=true;});
    function integer(v,optional=false){if(v===''&&optional)return null;const n=Number(v);if(!Number.isSafeInteger(n))throw Error('정수 수량·금액을 입력해 주세요.');return n;}
    function facts(row,keys) { return '<dl class="inventory-facts">'+keys.filter(([key])=>Object.hasOwn(row,key)&&(key!=='paymentAmount'||row[key]!=null)).map(([key,label])=>`<div><dt>${label}</dt><dd>${['unitPrice','remainingAmount','refundAmount','paymentAmount','purchaseAmount'].includes(key)?money(row[key]):escape(row[key]??'미입력')}</dd></div>`).join('')+'</dl>'; }
    function pager(id,data,go) {const el=$(id);el.innerHTML=`<span>${data.totalElements}건 · ${data.page+1} / ${Math.max(1,data.totalPages)}</span> <button class="btn small" type="button" data-prev ${data.page===0?'disabled':''}>이전</button> <button class="btn small" type="button" data-next ${data.page+1>=data.totalPages?'disabled':''}>다음</button>`;el.querySelector('[data-prev]').onclick=()=>go(data.page-1);el.querySelector('[data-next]').onclick=()=>go(data.page+1);}
    async function run(form,errorId,fn) {
        if(busy)return;busy=true;message(errorId,null);const controls=[...document.querySelectorAll('dialog button, dialog input, dialog select, dialog textarea')];const old=controls.map(e=>e.disabled);
        try { // Collect values before disabling controls, then keep dialogs open during the write.
            const work=fn();controls.forEach(e=>e.disabled=true);await work;
        } catch(err){message(errorId,err.message);}
        finally {controls.forEach((e,i)=>e.disabled=old[i]);busy=false;syncOrderActions();syncPurchaseItemActions();}
    }
    async function postOnce(form,url,body) {
        const fingerprint=JSON.stringify({url,body}),old=requestKeys.get(form),key=old?.fingerprint===fingerprint?old.key:crypto.randomUUID();
        requestKeys.set(form,{fingerprint,key});
        const result=await apiRequest(url,{method:'POST',headers:{'Content-Type':'application/json','X-Operation-Id':key},body:JSON.stringify(body)});
        requestKeys.delete(form);return result;
    }
    document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>{if(!busy)$(b.dataset.close).close();}));
    document.querySelectorAll('dialog').forEach(d=>d.addEventListener('cancel',e=>{if(busy)e.preventDefault();}));
    function itemTable(rows,inOrder=false,inStock=false) {
        const headings=['구매 상품 / 옵션','매입일 / 구매처','보유','미입고',...(inStock?['상품 메모']:[]),...(admin?['단가 / 잔여 금액']:[]),'작업'];
        return '<table class="data"><thead><tr>'+headings.map(h=>`<th>${h}</th>`).join('')+'</tr></thead><tbody>'+ (rows.map(i=>`<tr><td><strong>${escape(i.productCode)}</strong><div>${escape([i.color,i.size,i.optionLabel].filter(Boolean).join(' · '))}</div>${i.productDeleted?'<small>관리 목록에서 삭제된 상품</small>':''}</td><td>${escape(i.purchasedOn)}<div>${escape(i.supplierName)}</div></td><td>${i.onHand}개</td><td>${i.pending}개</td>${inStock?`<td class="inventory-note">${escape(i.publicNote)}</td>`:''}${admin?`<td>${money(i.unitPrice)}<div>${money(i.remainingAmount)}</div></td>`:''}<td><div class="inventory-actions"><button class="btn small" type="button" data-item="${escape(i.id)}">주문에서 보기</button>${inOrder&&!i.purchaseDeleted&&i.pending>0?`<button type="button" class="btn small" data-receive="${escape(i.id)}">입고</button>${admin?`<button type="button" class="btn small" data-cancel-pending="${escape(i.id)}">미입고 취소</button>`:''}`:''}</div></td></tr>`).join('')||`<tr><td colspan="${headings.length}" class="empty">등록된 매입 상품이 없습니다.</td></tr>`)+ '</tbody></table>';
    }
    function movementTable(rows,inItem=false,inStock=false) {
        return '<table class="data"><thead><tr><th>발생일 / 유형</th><th>상품 / 수량 변동</th><th>사유 / 담당자</th><th>작업</th></tr></thead><tbody>'+(rows.map(m=>`<tr><td>${escape(m.occurredAt.replace('T',' '))}<div>${escape(labels[m.kind]||m.kind)}${m.reversed?' · 취소됨':''}${m.purchaseDeleted?' · 삭제된 주문':''}</div><small>기록 ${escape(m.id)}</small></td><td>${escape(m.productCode)}${inStock?`<div>${escape([m.color,m.size].filter(Boolean).join(' · '))}</div><small>매입 상품 ${escape(m.itemId)}</small>`:''}<div>보유 ${m.handDelta>0?'+':''}${m.handDelta} · 미입고 ${m.pendingDelta>0?'+':''}${m.pendingDelta}</div>${m.referenceId?`<small>원기록 ${escape(m.referenceId)}</small>`:''}</td><td>${escape(m.reason)}<div>${escape(m.actorName)}${m.referenceNumber?' · '+escape(m.referenceNumber):''}</div>${admin&&m.kind==='SUPPLIER_RETURN'?`<div>${escape(labels[m.refundStatus])} ${m.refundAmount==null?'':money(m.refundAmount)}</div>`:''}</td><td><div class="inventory-actions">${!m.purchaseDeleted&&!m.reversed&&!['REVERSE','ORDER_INCREASE','ORDER_DECREASE'].includes(m.kind)&&(admin||m.kind!=='CANCEL_PENDING')?`<button type="button" class="btn small" data-reverse="${escape(m.id)}">기록 취소</button>`:''}${admin&&!m.purchaseDeleted&&m.kind==='SUPPLIER_RETURN'&&!m.reversed?`<button type="button" class="btn small" data-refund="${escape(m.id)}">환불 관리</button>`:''}${!inItem?`<button type="button" class="btn small" data-item="${escape(m.itemId)}">주문에서 보기</button>`:''}</div></td></tr>`).join('')||'<tr><td colspan="4" class="empty">입출고 기록이 없습니다.</td></tr>')+'</tbody></table>';
    }
    function stockStatus(row){return row.onHand>0?(row.pending>0?'보유 · 미입고':'보유'):row.pending>0?'미입고':'재고 없음';}
    function stockIdentity(row){return `<div class="inventory-stock-identity">${productImage(row)}<span>${escape(row.brand||'브랜드 미지정')}<br><strong>${escape(row.productCode||'상품코드 없음')}</strong>${row.productDeleted?'<small>관리 목록에서 삭제된 상품</small>':''}</span></div>`;}
    function stockTable(rows){const columns=admin?6:5;return '<table class="data"><thead><tr><th>상품 정보</th><th>보유 수량</th><th>미입고 수량</th><th>상태</th>'+(admin?'<th>잔여 재고 금액</th>':'')+'<th>상세</th></tr></thead><tbody>'+(rows.map(r=>`<tr><td>${stockIdentity(r)}</td><td>${r.onHand}개</td><td>${r.pending}개</td><td>${escape(stockStatus(r))}</td>${admin?`<td>${money(r.remainingAmount)}</td>`:''}<td><button type="button" class="btn small" data-stock-product="${escape(r.productId)}">상세</button></td></tr>`).join('')||`<tr><td colspan="${columns}" class="empty">등록된 재고 상품이 없습니다.</td></tr>`)+ '</tbody></table>';}
    async function refreshStockProduct(){
        if(!currentStock||!$('stock-dialog').open)return;const anchor=currentStock.productId,sequence=++stockSequence;
        try {const query={groupProductId:anchor,size:20};const [summary,lots,history]=await Promise.all([apiGet('/api/inventory/stock-products/'+encodeURIComponent(anchor)),apiGet('/api/inventory/items?'+new URLSearchParams({...query,page:stockItemPage})),apiGet('/api/inventory/movements?'+new URLSearchParams({...query,page:stockMovementPage}))]);
            if(sequence!==stockSequence||!$('stock-dialog').open)return;currentStock=summary;message('stock-error',null);
            $('stock-title').textContent=(summary.productCode||'상품코드 없음')+' · 상품 재고';
            $('stock-summary').innerHTML=stockIdentity(summary)+facts({...summary,onHand:summary.onHand+'개',pending:summary.pending+'개',state:stockStatus(summary)},[['remainingAmount','잔여 재고 금액'],['state','상태'],['onHand','총 보유 수량'],['pending','총 미입고 수량']]);imageFallback($('stock-summary'));
            $('stock-options').innerHTML='<table class="data"><thead><tr><th>색상</th><th>사이즈</th><th>보유 수량</th><th>미입고 수량</th></tr></thead><tbody>'+summary.options.map(o=>`<tr><td>${escape(o.color||'미입력')}</td><td>${escape(o.size||'미입력')}</td><td>${o.onHand}개</td><td>${o.pending}개</td></tr>`).join('')+'</tbody></table>';
            if((stockItemPage>0&&!lots.items.length)||(stockMovementPage>0&&!history.items.length)){stockItemPage=Math.min(stockItemPage,Math.max(0,lots.totalPages-1));stockMovementPage=Math.min(stockMovementPage,Math.max(0,history.totalPages-1));return refreshStockProduct();}
            $('stock-items').innerHTML=itemTable(lots.items,false,true);pager('stock-item-pager',lots,n=>{stockItemPage=n;refreshStockProduct();});
            stockMovementRows=history.items;$('stock-movements').innerHTML=movementTable(history.items,false,true);pager('stock-movement-pager',history,n=>{stockMovementPage=n;refreshStockProduct();});
        }catch(err){if(sequence!==stockSequence||!$('stock-dialog').open)return;if(err.status===404){$('stock-dialog').close();message('inventory-success','이 상품에 남아 있는 구매 주문이 없습니다.');}else message('stock-error',err.message);}
    }
    async function showStockProduct(id){currentStock={productId:id};stockItemPage=0;stockMovementPage=0;stockMovementRows=[];$('stock-title').textContent='상품 재고';message('stock-error',null);for(const name of ['stock-summary','stock-options','stock-items','stock-movements','stock-item-pager','stock-movement-pager'])$(name).textContent='';if(!$('stock-dialog').open)$('stock-dialog').showModal();await refreshStockProduct();}
    $('stock-dialog').addEventListener('close',()=>{++stockSequence;currentStock=null;stockMovementRows=[];});
    async function load() {
        const seq=++loadSequence;message('inventory-error',null);const size=$('inventory-size').value,q=$('inventory-q').value,brandId=$('inventory-brand').value;
        $('inventory-query-label').textContent=tab==='items'?'상품코드·브랜드·검색어':tab==='purchases'?(admin?'구매처·주문번호':'구매처'):'검색어 검색 미지원';
        $('inventory-q').disabled=tab==='movements';$('inventory-clear-product').hidden=!productId;
        $('inventory-context').textContent=productId&&tab!=='purchases'?`연결 상품 ${productId}의 재고·이력`:'';$('inventory-context').hidden=!$('inventory-context').textContent;
        const query=new URLSearchParams({page,size,brandId});let path;
        if(tab==='items'){path='stock-products';query.set('q',q);query.set('productId',productId);}
        else if(tab==='purchases'){path='purchases';query.set('q',q);}
        else {path='movements';if(productId)query.set('groupProductId',productId);}
        try {const [data,totals]=await Promise.all([apiGet('/api/inventory/'+path+'?'+query),apiGet('/api/inventory/stock-totals?'+new URLSearchParams(tab==='items'?{q,productId,brandId}:{}))]);if(seq!==loadSequence)return;
            $('inventory-total-quantity').textContent=BigInt(totals.totalOnHand).toLocaleString('ko-KR')+'개';$('inventory-filtered-total').hidden=tab!=='items'||(!q.trim()&&!productId&&!brandId);$('inventory-filtered-total').textContent='검색 결과 보유 수량 '+BigInt(totals.filteredOnHand).toLocaleString('ko-KR')+'개';
            if(page>0&&!data.items.length){page=Math.max(0,data.totalPages-1);return load();}
            if(tab==='items'){$('inventory-results').innerHTML=stockTable(data.items);imageFallback($('inventory-results'));if(productId&&data.items.length)$('inventory-context').textContent=`${data.items[0].productCode||'상품코드 없음'}의 전체 재고`;}
            else if(tab==='movements'){movementRows=data.items;$('inventory-results').innerHTML=movementTable(data.items);}
            else $('inventory-results').innerHTML='<table class="data"><thead><tr><th>매입일</th><th>구매처</th>'+(admin?'<th>주문번호 / 결제</th>':'')+'<th>주문 / 입고 / 미입고</th><th>작업</th></tr></thead><tbody>'+(data.items.map(o=>`<tr><td>${escape(o.purchasedOn)}</td><td>${escape(o.supplierName||'미입력')}</td>${admin?`<td>${escape(o.orderNumber)}<div>${escape(o.paymentMethod)} ${escape(o.paymentAlias)}</div><div>매입 합계 ${money(o.purchaseAmount)}</div></td>`:''}<td>${o.orderedQuantity}개 / ${o.receivedQuantity}개 / ${o.pending}개</td><td><div class="inventory-actions"><button class="btn small" type="button" data-order="${escape(o.id)}">주문 상세</button><button class="btn small primary" type="button" data-order-receipt="${escape(o.id)}" ${o.pending>0?'':'disabled'}>입고 확인</button></div></td></tr>`).join('')||`<tr><td colspan="${admin?5:4}" class="empty">구매 주문이 없습니다.</td></tr>`)+ '</tbody></table>';
            pager('inventory-pager',data,n=>{page=n;load();});
            await refreshStockProduct();
        }catch(err){if(seq===loadSequence){$('inventory-total-quantity').textContent='—';$('inventory-filtered-total').hidden=true;message('inventory-error',err.message);}}
    }
    document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{tab=b.dataset.tab;page=0;document.querySelectorAll('[data-tab]').forEach(x=>{x.classList.toggle('active',x===b);x.setAttribute('aria-pressed',String(x===b));});load();});
    $('inventory-filter').onsubmit=ev=>{ev.preventDefault();page=0;load();};$('inventory-clear-product').onclick=()=>{productId='';history.replaceState(null,'','/inventory');page=0;load();};
    const orderFields=['purchasedOn','supplierName','orderNumber','orderUrl','paidOn','privateNote'];
    let pickerRow=null,pickerPage=0,pickerRows=[],pickerSequence=0,movementContext=null,autoSupplier='',supplierManual=false,paymentReady=false,purchaseSequence=0;
    function reindexItems() {$('purchase-items').querySelectorAll('.inventory-purchase-item').forEach((row,n)=>{row.querySelector('h4').textContent=`구매 항목 ${n+1}`;const remove=row.querySelector('[data-remove]');remove.setAttribute('aria-label',`구매 항목 ${n+1} 제거`);remove.title=`구매 항목 ${n+1} 제거`;});updatePurchaseAmount();}
    function updatePurchaseAmount() {
        if(!$('purchase-amount'))return;
        let amount=0n;
        for(const row of $('purchase-items').children){const q=row.querySelector('[name=orderedQuantity]').value,p=row.querySelector('[name=unitPrice]').value;
            if(!/^[1-9]\d*$/.test(q)||!/^\d+$/.test(p)){$('purchase-amount').value='미확인';return;}amount+=BigInt(q)*BigInt(p);}
        $('purchase-amount').value=money(amount);
    }
    $('purchase-items')?.addEventListener('input',ev=>{if(['orderedQuantity','unitPrice'].includes(ev.target.name))updatePurchaseAmount();});
    function productImage(product) {
        const image=product.imageUrl;
        return image&&/^https:\/\//i.test(image)?`<img class="inventory-product-image" src="${escape(image)}" alt="" loading="lazy" referrerpolicy="no-referrer">`:'<span class="inventory-product-image inventory-product-placeholder">이미지 없음</span>';
    }
    function imageFallback(container) {container.querySelectorAll('img').forEach(img=>img.onerror=()=>{const fallback=document.createElement('span');fallback.className='inventory-product-image inventory-product-placeholder';fallback.textContent='이미지 없음';img.replaceWith(fallback);});}
    function chooseProduct(row,product) {
        row.dataset.productId=product.id;
        row.querySelector('[data-selected-product]').innerHTML=productImage(product)+`<span>${escape(product.brand||'브랜드 미지정')}<br><strong>${escape(product.productCode)}</strong></span>`;
        imageFallback(row);
    }
    async function addItem(prefill=null) {
        const row=document.createElement('section');row.className='inventory-purchase-item';
        const input=(name,label,type='text',attrs='')=>`<label><span>${name==='orderedQuantity'?'<span class="required-mark" aria-hidden="true">*</span>':''}${label}</span><input name="${name}" type="${type}" ${attrs}></label>`;
        row.innerHTML=`<div class="inventory-item-heading"><h4></h4><button type="button" class="btn inventory-remove-item" data-remove><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/></svg></button></div><div class="inventory-grid inventory-purchase-fields"><div class="inventory-purchase-product"><div class="inventory-product-heading"><strong><span class="required-mark" aria-hidden="true">*</span>상품 정보</strong><button type="button" class="btn small" data-product-query>상품 찾기</button></div><div data-selected-product class="inventory-selected-product">상품을 선택해 주세요.</div></div>${input('color','색상','text','maxlength="100"')}${input('size','사이즈','text','maxlength="100"')}${input('orderedQuantity','주문 수량','number','min="1" max="1000000" step="1" required value="1"')}${input('unitPrice','단가 (원)','number','min="0" max="1000000000" step="1" placeholder="미확인"')}${input('publicNote','상품 메모','text','maxlength="2000"')}</div>`;
        $('purchase-items').append(row);reindexItems();
        row.querySelector('[data-remove]').onclick=()=>{row.remove();reindexItems();};
        row.querySelector('[data-product-query]').onclick=()=>{pickerRow=row;pickerPage=0;message('product-picker-error',null);$('product-picker-query').value='';$('product-picker-dialog').showModal();loadPicker();};
        if(prefill?.product)try {const d=await apiGet('/api/products/'+encodeURIComponent(prefill.product));if(row.isConnected)chooseProduct(row,d.product);}catch(err){message('purchase-error',err.message);}
    }
    async function loadPicker() {
        const seq=++pickerSequence;message('product-picker-error',null);
        try {const data=await apiGet('/api/products?'+new URLSearchParams({q:$('product-picker-query').value,page:pickerPage,size:20}));if(seq!==pickerSequence)return;pickerRows=data.items;
            $('product-picker-results').innerHTML=data.items.map(p=>`<button type="button" class="inventory-product-choice" data-choose-product="${escape(p.id)}">${productImage(p)}<span>${escape(p.brand||'브랜드 미지정')}<br><strong>${escape(p.productCode)}</strong></span></button>`).join('')||'<p class="empty">검색된 상품이 없습니다.</p>';
            imageFallback($('product-picker-results'));pager('product-picker-pager',data,n=>{pickerPage=n;loadPicker();});
        }catch(err){if(seq===pickerSequence)message('product-picker-error',err.message);}
    }
    $('product-picker-form')?.addEventListener('submit',ev=>{ev.preventDefault();pickerPage=0;loadPicker();});
    $('product-picker-results')?.addEventListener('click',ev=>{const button=ev.target.closest('[data-choose-product]'),product=pickerRows.find(p=>p.id===button?.dataset.chooseProduct);if(product&&pickerRow?.isConnected){chooseProduct(pickerRow,product);$('product-picker-dialog').close();}});
    async function loadPaymentMethods(selected=null,sequence=purchaseSequence,targetForm=$('purchase-form')) {
        const methods=await apiGet('/api/settings/payment-methods'),select=field(targetForm,'paymentMethodId');
        if(sequence!==purchaseSequence)return;
        select.innerHTML='<option value="">미입력</option>'+methods.map(m=>`<option value="${escape(m.id)}">${escape(selected?.paymentMethodId===m.id&&selected.paymentMethod!==m.name?selected.paymentMethod+' (현재: '+m.name+')':m.name)}</option>`).join('');
        if(selected?.paymentMethodId) {
            if(!methods.some(m=>m.id===selected.paymentMethodId)){const option=new Option(selected.paymentMethod+' (삭제됨)',selected.paymentMethodId);option.disabled=true;select.add(option);}
            select.value=selected.paymentMethodId;
        } else if(selected?.paymentMethod){select.add(new Option(selected.paymentMethod+' (기존 기록)','legacy'));select.value='legacy';}
        paymentReady=true;
    }
    function syncDates() {const form=$('purchase-form'),same=$('purchase-same-date').checked;field(form,'paidOn').disabled=same;if(same)field(form,'paidOn').value=value(form,'purchasedOn');}
    $('purchase-same-date')?.addEventListener('change',syncDates);
    if(admin)field($('purchase-form'),'purchasedOn').addEventListener('input',syncDates);
    function supplierFromUrl(raw) {
        try {const url=new URL(raw);if(!['http:','https:'].includes(url.protocol)||url.username||url.password)return '';const host=url.hostname.toLowerCase();
            const malls=[['lfmall.co.kr','LF몰'],['hazzys.com','헤지스'],['smartstore.naver.com','네이버 스마트스토어'],['brand.naver.com','네이버 스마트스토어'],['shopping.naver.com','네이버 쇼핑'],['pay.naver.com','네이버페이'],['lotteon.com','롯데온'],['lotteimall.com','롯데아이몰'],['thehyundai.com','더현대닷컴'],['hmall.com','현대H몰']];
            return malls.find(([domain])=>host===domain||host.endsWith('.'+domain))?.[1]||'';
        }catch{return '';}
    }
    if(admin) {
        field($('purchase-form'),'supplierName').addEventListener('input',()=>supplierManual=true);
        field($('purchase-form'),'orderUrl').addEventListener('input',()=>{if(supplierManual)return;const supplier=field($('purchase-form'),'supplierName');if(supplier.value&&supplier.value!==autoSupplier)return;autoSupplier=supplierFromUrl(value($('purchase-form'),'orderUrl'));supplier.value=autoSupplier;});
    }
    async function openCreate(prefill=null) {
        const sequence=++purchaseSequence;paymentReady=false;autoSupplier='';supplierManual=false;const form=$('purchase-form');form.reset();fill(form,{privateNote:''});requestKeys.delete(form);field(form,'paymentMethodId').innerHTML='<option value="">미입력</option>';
        $('purchase-title').textContent='매입 주문 등록';$('purchase-items-section').hidden=false;$('purchase-items').innerHTML='';field(form,'purchasedOn').value=now().slice(0,10);$('purchase-same-date').checked=true;syncDates();message('purchase-error',null);$('purchase-dialog').showModal();
        await addItem(prefill);try {await loadPaymentMethods(null,sequence);}catch(err){if(sequence===purchaseSequence)message('purchase-error',err.message);}
    }
    $('purchase-create')?.addEventListener('click',()=>{if(!busy)openCreate();});$('purchase-add-item')?.addEventListener('click',()=>{if($('purchase-items').children.length>=100){message('purchase-error','최대 100개 항목까지 등록할 수 있습니다.');return;}addItem();});
    $('purchase-form')?.addEventListener('submit',ev=>{ev.preventDefault();const form=ev.currentTarget;run(form,'purchase-error',async()=>{
        if(!paymentReady)throw Error('결제 수단 목록을 불러온 뒤 저장해 주세요. 불러오지 못했다면 창을 다시 열어 주세요.');
        const body=values(form,orderFields);body.paidOn=body.paidOn||null;body.paymentAmount=null;
        body.paymentMethodId=value(form,'paymentMethodId')||null;
        body.items=[...$('purchase-items').children].map(row=>{if(!row.dataset.productId)throw Error('각 구매 항목의 연결 상품을 선택해 주세요.');const get=n=>row.querySelector(`[name="${n}"]`).value.trim();return {productId:row.dataset.productId,...Object.fromEntries(['color','size','publicNote'].map(n=>[n,get(n)])),orderedQuantity:integer(get('orderedQuantity')),unitPrice:integer(get('unitPrice'),true)};});
        const result=await postOnce(form,'/api/inventory/purchases',body);$('purchase-dialog').close();tab='purchases';document.querySelectorAll('[data-tab]').forEach(x=>x.classList.toggle('active',x.dataset.tab===tab));await showOrder(result.id);
        message('inventory-success','구매 정보를 저장했습니다.');await load();
    });});
    function syncOrderActions() {
        $('purchase-view-content').querySelectorAll('[data-item],[data-receive],[data-cancel-pending]').forEach(b=>b.disabled=busy||orderEditing||!!editingItemId);
        if(!admin)return;
        const form=$('purchase-view-form'),locked=busy||!orderEditing||!!currentOrder?.deletedAt;
        if(form){form.querySelectorAll('input[name]').forEach(el=>el.readOnly=locked);field(form,'paymentMethodId').disabled=locked;$('purchase-view-same-date').disabled=locked;syncViewDates();}
        if($('purchase-edit')){$('purchase-edit').disabled=busy||!!editingItemId||!!currentOrder?.deletedAt;$('purchase-edit').hidden=orderEditing;}
        if($('purchase-save')){$('purchase-save').hidden=!orderEditing;$('purchase-edit-cancel').hidden=!orderEditing;$('purchase-save').disabled=busy;$('purchase-edit-cancel').disabled=busy;}
        if($('purchase-delete')){$('purchase-delete').disabled=busy||orderEditing||!!editingItemId||!!currentOrder?.deleteBlockedReason||!!currentOrder?.deletedAt;message('purchase-delete-blocked',currentOrder?.deleteBlockedReason);}
        const confirm=$('purchase-delete-form')?.querySelector('[type=submit]');if(confirm)confirm.disabled=busy||!deletingOrder;
    }
    function quantitySummary(row) {
        return facts({...row,orderedQuantity:row.orderedQuantity+'개',receivedQuantity:row.receivedQuantity+'개',pending:row.pending+'개',cancelledQuantity:row.cancelledQuantity+'개'},[['orderedQuantity','주문 수량'],['receivedQuantity','입고된 수량'],['pending','미입고 수량'],...(row.cancelledQuantity>0?[['cancelledQuantity','미입고 취소 수량']]:[])]).replace('inventory-facts','inventory-facts inventory-quantity-summary');
    }
    function productFields(row) {
        const input=(name,label,type='text',attrs='')=>`<label><span>${name==='orderedQuantity'?'<span class="required-mark" aria-hidden="true">*</span>':''}${label}</span><input name="${name}" type="${type}" ${attrs} readonly></label>`;
        return `<div class="inventory-purchase-product"><div class="inventory-product-heading"><strong>상품 정보</strong></div>${stockIdentity(row)}</div>${input('orderedQuantity','주문 수량','number','min="1" max="1000000" step="1" required')}${input('color','색상','text','maxlength="100"')}${input('size','사이즈','text','maxlength="100"')}${admin?input('unitPrice','단가 (원)','number','min="0" max="1000000000" step="1" placeholder="미확인"'):''}${input('publicNote','상품 메모','text','maxlength="2000"')}`;
    }
    function orderProductCards(rows) {
        return rows.map((item,n)=>`<section class="inventory-purchase-item" data-purchase-product="${escape(item.id)}"><h4>매입 상품 ${n+1}</h4><p id="purchase-item-error-${escape(item.id)}" class="form-error" role="alert" hidden></p><form data-purchase-item-form="${escape(item.id)}" class="inventory-grid inventory-purchase-fields inventory-order-item-fields ${admin?'':'inventory-order-item-fields--public'}">${productFields(item)}<div class="inventory-actions inventory-item-edit-actions">${!item.purchaseDeleted?`<button type="button" class="btn small" data-edit-product="${escape(item.id)}">수정</button><button type="submit" class="btn small primary" data-save-product hidden>저장</button><button type="button" class="btn small" data-cancel-product="${escape(item.id)}" hidden>취소</button>`:''}</div></form><div class="inventory-order-item-summary">${quantitySummary(item)}<div class="inventory-actions">${!item.purchaseDeleted&&item.pending>0?`<button type="button" class="btn small" data-receive="${escape(item.id)}">입고</button>${admin?`<button type="button" class="btn small" data-cancel-pending="${escape(item.id)}">미입고 취소</button>`:''}`:''}</div></div><details data-item-history="${escape(item.id)}" ${historyPages.has(item.id)?'open':''}><summary>입출고 이력</summary><div id="purchase-item-history-${escape(item.id)}" class="table-wrap"></div><div id="purchase-item-pager-${escape(item.id)}" class="pager"></div></details></section>`).join('');
    }
    function orderMetadata(row) {
        if(!admin)return `<div class="purchase-order-fields"><div class="purchase-order-row"><label>매입일<input name="purchasedOn" type="date" readonly></label><label>실제 구매처·지점<input name="supplierName" readonly></label></div></div>`;
        return `<div class="purchase-order-fields"><div class="purchase-order-row purchase-order-row--three"><label><span><span class="required-mark" aria-hidden="true">*</span>매입일</span><input name="purchasedOn" type="date" required readonly></label><label>결제일<input name="paidOn" type="date" readonly></label><label class="inventory-date-sync"><input id="purchase-view-same-date" type="checkbox" disabled ${row.paidOn&&row.paidOn===row.purchasedOn?'checked':''}> 일치</label></div><div class="purchase-order-row purchase-order-row--three"><label>주문번호<input name="orderNumber" maxlength="255" readonly></label><label>주문서 URL<input name="orderUrl" type="url" maxlength="2000" readonly></label><label>실제 구매처·지점<input name="supplierName" maxlength="255" readonly></label></div><div class="purchase-order-row purchase-order-row--three"><label>매입 합계 (원, 주문 수량 기준)<input type="text" value="${money(row.purchaseAmount)}" readonly></label><label>결제 수단<select name="paymentMethodId" disabled><option value="${escape(row.paymentMethodId|| (row.paymentMethod?'legacy':''))}">${escape(row.paymentMethod||'미입력')}</option></select></label><label>결제 메모<input name="privateNote" type="text" maxlength="2000" readonly></label></div></div>`;
    }
    async function showOrder(id,focusItem=null) {
        const sequence=++orderSequence;
        try {const order=await apiGet('/api/inventory/purchases/'+id);if(sequence!==orderSequence)return;if(currentOrder?.id!==order.id){itemHistories.clear();historyPages.clear();}currentOrder=order;orderEditing=false;editingItemId=null;message('purchase-view-error',null);
            $('purchase-view-content').innerHTML=(currentOrder.deletedAt?'<p class="field-hint">삭제된 구매 주문입니다. 이력만 조회할 수 있습니다.</p>':'')+`<form id="purchase-view-form" class="form">${orderMetadata(currentOrder)}</form>`+(admin&&(currentOrder.paymentAmount!=null||currentOrder.paymentAlias)?'<details class="inventory-legacy-payment"><summary>기존 결제 정보</summary>'+facts(currentOrder,[['paymentAmount','기존 입력 결제 금액'],...(currentOrder.paymentAlias?[['paymentAlias','기존 결제 별칭']]:[])])+'</details>':'')+quantitySummary(currentOrder)+'<h3>매입 상품</h3>'+orderProductCards(currentOrder.items);
            fill($('purchase-view-form'),currentOrder);if(admin)field($('purchase-view-form'),'paymentMethodId').value=currentOrder.paymentMethodId||(currentOrder.paymentMethod?'legacy':'');currentOrder.items.forEach(item=>{const card=$('purchase-view-content').querySelector(`[data-purchase-product="${item.id}"]`);fill({elements:{namedItem:name=>card.querySelector(`[name="${name}"]`)}},item);});imageFallback($('purchase-view-content'));syncOrderActions();syncPurchaseItemActions();
            $('purchase-view-content').querySelectorAll('[data-purchase-item-form]').forEach(form=>form.onsubmit=savePurchaseItem);
            $('purchase-view-content').querySelectorAll('[data-item-history]').forEach(details=>{details.ontoggle=()=>{if(!details.isConnected)return;if(!details.open){historyPages.delete(details.dataset.itemHistory);return;}if(!details.querySelector('table')&&details.querySelector('.table-wrap').dataset.loading!=='true'){historyPages.set(details.dataset.itemHistory,0);loadPurchaseItemHistory(details.dataset.itemHistory).catch(err=>message('purchase-item-error-'+details.dataset.itemHistory,err.message));}};if(details.open)loadPurchaseItemHistory(details.dataset.itemHistory).catch(err=>message('purchase-item-error-'+details.dataset.itemHistory,err.message));});
            if(admin&&currentOrder.orderUrl){const a=document.createElement('a');a.textContent='주문서 열기';a.href=currentOrder.orderUrl;a.target='_blank';a.rel='noopener noreferrer';a.referrerPolicy='no-referrer';$('purchase-view-content').prepend(a);}
            $('purchase-view-form').onsubmit=saveOrderDetails;
            if(admin){$('purchase-view-same-date').onchange=syncViewDates;field($('purchase-view-form'),'purchasedOn').oninput=syncViewDates;}
            if(!$('purchase-view-dialog').open)$('purchase-view-dialog').showModal();
            if(focusItem){const card=$('purchase-view-content').querySelector(`[data-purchase-product="${focusItem}"]`);if(card){card.scrollIntoView({block:'center'});card.querySelector('[data-item-history]').open=true;}}
        }catch(err){message('purchase-view-error',err.message);}
    }
    function syncViewDates(){const form=$('purchase-view-form'),same=$('purchase-view-same-date');if(!form||!same)return;field(form,'paidOn').readOnly=!orderEditing||same.checked||busy;if(orderEditing&&same.checked)field(form,'paidOn').value=value(form,'purchasedOn');}
    function setOrderEditing(editing){orderEditing=editing;syncOrderActions();syncPurchaseItemActions();}
    async function saveOrderDetails(ev){ev.preventDefault();if(!orderEditing||!admin)return;const form=ev.currentTarget;run(form,'purchase-view-error',async()=>{
        const original=currentOrder,body=values(form,orderFields),method=value(form,'paymentMethodId');Object.assign(body,{revision:original.revision,paidOn:body.paidOn||null,paymentAmount:original.paymentAmount??null,paymentAlias:original.paymentAlias||'',paymentMethodId:method&&method!=='legacy'?method:null,paymentMethod:method==='legacy'?original.paymentMethod:''});
        await apiPost(`/api/inventory/purchases/${original.id}`,body);await showOrder(original.id);await load();message('inventory-success','구매 정보를 저장했습니다.');
    });}
    $('purchase-delete')?.addEventListener('click',async()=>{
        if(busy||!currentOrder||currentOrder.deletedAt)return;
        await run($('purchase-delete-form'),'purchase-view-error',async()=>{
            const order=await apiGet('/api/inventory/purchases/'+encodeURIComponent(currentOrder.id));currentOrder=order;
            if(order.deleteBlockedReason||order.deletedAt){await showOrder(order.id);message('purchase-view-error',order.deleteBlockedReason||'이미 삭제된 구매 주문입니다.');return;}
            deletingOrder=order;requestKeys.delete($('purchase-delete-form'));message('purchase-delete-error',null);
            $('purchase-delete-summary').textContent=`${order.purchasedOn} · ${order.supplierName||'구매처 미입력'}${order.orderNumber?' · '+order.orderNumber:''} · 구매 항목 ${order.items.length}개 · 미입고 ${order.pending}개 취소`;
            $('purchase-delete-dialog').showModal();
        });
    });
    $('purchase-delete-form')?.addEventListener('submit',ev=>{ev.preventDefault();const form=ev.currentTarget;if(!deletingOrder)return;run(form,'purchase-delete-error',async()=>{
        const order=deletingOrder;
        try {await postOnce(form,`/api/inventory/purchases/${order.id}/delete`,{revision:order.revision,deleteToken:order.deleteToken});}
        catch(err){if(err.status===409){deletingOrder=null;requestKeys.delete(form);$('purchase-delete-dialog').close();await showOrder(order.id);message('purchase-view-error',err.message+' 최신 주문을 확인한 뒤 다시 진행해 주세요.');return;}throw err;}
        deletingOrder=null;currentOrder=null;editingItemId=null;receiptOrder=null;++receiptSequence;
        document.querySelectorAll('dialog[open]').forEach(d=>d.close());
        await load();message('inventory-success','구매 주문을 삭제했습니다. 남은 미입고 수량을 취소하고 기존 이력을 보존했습니다.');
    });});
    let receiptOrder=null,receiptSequence=0;
    function renderReceiptItems(resetItem=null) {
        const container=$('purchase-receipt-items'),previous=new Map([...container.querySelectorAll('[data-receipt-item]')].map(form=>[form.dataset.receiptItem,form]));
        const forms=receiptOrder.items.filter(item=>item.pending>0).map(item=>{
            let form=previous.get(item.id);
            if(!form){
                form=document.createElement('form');form.className='inventory-receipt-item';form.dataset.receiptItem=item.id;
                form.innerHTML=`<div class="inventory-receipt-product"><strong>${escape(item.productCode)}</strong><span>색상 ${escape(item.color||'미입력')} · 사이즈 ${escape(item.size||'미입력')}</span></div><div class="inventory-receipt-pending">미입고 <strong data-receipt-pending></strong></div><label class="field inventory-receipt-quantity">입고 수량<input name="quantity" type="number" min="1" step="1" required></label><button class="btn primary" type="submit">입고 확인</button>`;
                field(form,'quantity').value=item.pending;
            }
            form.querySelector('[data-receipt-pending]').textContent=item.pending+'개';field(form,'quantity').max=item.pending;
            if(resetItem===item.id){field(form,'quantity').value=item.pending;}
            return form;
        });
        if(forms.length)container.replaceChildren(...forms);
        else {const empty=document.createElement('p');empty.className='empty';empty.textContent='입고할 미입고 상품이 없습니다.';container.replaceChildren(empty);}
    }
    async function openOrderReceipt(id) {
        const sequence=++receiptSequence;receiptOrder=null;message('purchase-receipt-error',null);message('purchase-receipt-success',null);
        $('purchase-receipt-summary').textContent='';$('purchase-receipt-date').value=now();$('purchase-receipt-items').textContent='주문을 불러오는 중입니다.';
        if(!$('purchase-receipt-dialog').open)$('purchase-receipt-dialog').showModal();
        try {const order=await apiGet('/api/inventory/purchases/'+encodeURIComponent(id));if(sequence!==receiptSequence||!$('purchase-receipt-dialog').open)return;
            receiptOrder=order;$('purchase-receipt-summary').textContent=order.purchasedOn+' · '+(order.supplierName||'구매처 미입력');renderReceiptItems();
        }catch(err){if(sequence===receiptSequence&&$('purchase-receipt-dialog').open){$('purchase-receipt-items').textContent='';message('purchase-receipt-error',err.message+' 창을 닫고 다시 열어 주세요.');}}
    }
    $('purchase-receipt-dialog').addEventListener('close',()=>{receiptSequence++;receiptOrder=null;});
    $('purchase-receipt-items').addEventListener('submit',ev=>{
        ev.preventDefault();const form=ev.target.closest('[data-receipt-item]');if(!form||busy||!receiptOrder)return;
        if(!$('purchase-receipt-date').reportValidity())return;
        run(form,'purchase-receipt-error',async()=>{
            message('purchase-receipt-success',null);const orderId=receiptOrder.id,item=receiptOrder.items.find(i=>i.id===form.dataset.receiptItem),quantity=integer(value(form,'quantity'));
            if(!item||quantity<1||quantity>item.pending)throw Error('입고 수량은 해당 상품의 미입고 수량 이내로 입력해 주세요.');
            let updated;
            try {updated=await postOnce(form,`/api/inventory/items/${item.id}/movements`,{revision:item.revision,kind:'RECEIPT',quantity,occurredAt:$('purchase-receipt-date').value,reason:'',referenceId:null});}
            catch(err){
                if(err.status===409){
                    try {receiptOrder=await apiGet('/api/inventory/purchases/'+encodeURIComponent(orderId));renderReceiptItems();await load();}
                    catch {throw Error(err.message+' 최신 수량을 불러오지 못했습니다. 창을 다시 열어 주세요.');}
                    throw Error(err.message+' 최신 미입고 수량을 확인한 뒤 다시 입고해 주세요.');
                }
                throw err;
            }
            receiptOrder.items=receiptOrder.items.map(i=>i.id===updated.id?updated:i);renderReceiptItems(updated.id);
            message('purchase-receipt-success',item.productCode+' '+quantity+'개 입고를 저장했습니다.');
            try {receiptOrder=await apiGet('/api/inventory/purchases/'+encodeURIComponent(orderId));renderReceiptItems();}
            catch(err){message('purchase-receipt-error','입고는 저장되었습니다. 최신 주문 조회에 실패했습니다. '+err.message);}
            if($('purchase-view-dialog').open&&currentOrder?.id===orderId)await showOrder(orderId);
            await load();
        });
    });
    $('purchase-edit')?.addEventListener('click',()=>{if(!currentOrder||orderEditing||editingItemId||busy)return;run($('purchase-view-form'),'purchase-view-error',async()=>{await loadPaymentMethods(currentOrder,purchaseSequence,$('purchase-view-form'));setOrderEditing(true);});});
    $('purchase-edit-cancel')?.addEventListener('click',()=>{if(!busy&&currentOrder)run($('purchase-view-form'),'purchase-view-error',()=>showOrder(currentOrder.id));});
    async function showItem(id) {
        try {const item=await apiGet('/api/inventory/items/'+encodeURIComponent(id));await showOrder(item.purchaseId,item.id);}
        catch(err){message('inventory-error',err.message);}
    }
    function syncPurchaseItemActions(){
        $('purchase-view-content').querySelectorAll('[data-purchase-item-form]').forEach(form=>{
            const editing=editingItemId===form.dataset.purchaseItemForm,locked=busy||orderEditing||!!currentOrder?.deletedAt;
            form.querySelectorAll('input').forEach(el=>el.readOnly=locked||!editing||(!admin&&el.name==='orderedQuantity'));
            const edit=form.querySelector('[data-edit-product]'),save=form.querySelector('[data-save-product]'),cancel=form.querySelector('[data-cancel-product]');
            if(edit){edit.hidden=editing;edit.disabled=locked||!!editingItemId;save.hidden=!editing;cancel.hidden=!editing;save.disabled=locked;cancel.disabled=locked;}
        });
        $('purchase-view-content').querySelectorAll('[data-reverse],[data-refund]').forEach(b=>b.disabled=busy||orderEditing||!!editingItemId);
    }
    async function loadPurchaseItemHistory(id){
        const container=$('purchase-item-history-'+id);if(!container)return;
        const sequence=(historySequences.get(id)||0)+1;historySequences.set(id,sequence);container.dataset.loading='true';
        let data;try {data=await apiGet(`/api/inventory/movements?itemId=${encodeURIComponent(id)}&page=${historyPages.get(id)||0}&size=20`);}finally{if(sequence===historySequences.get(id))delete container.dataset.loading;}
        if(!container.isConnected||sequence!==historySequences.get(id))return;itemHistories.set(id,data.items);container.innerHTML=movementTable(data.items,true);
        pager('purchase-item-pager-'+id,data,n=>{historyPages.set(id,n);loadPurchaseItemHistory(id).catch(err=>message('purchase-item-error-'+id,err.message));});syncPurchaseItemActions();
    }
    async function savePurchaseItem(ev){ev.preventDefault();const form=ev.currentTarget,id=form.dataset.purchaseItemForm;if(editingItemId!==id)return;
        run(form,'purchase-item-error-'+id,async()=>{
            const item=currentOrder.items.find(i=>i.id===id),body={revision:item.revision,...values(form,['color','size','publicNote'])};
            if(admin){body.orderedQuantity=integer(value(form,'orderedQuantity'));body.unitPrice=integer(value(form,'unitPrice'),true);}
            try {await apiPost(`/api/inventory/items/${id}`,body);}
            catch(err){if(err.status===409)throw Error(err.message+' 입력한 내용은 유지했습니다. 취소 후 최신 정보를 확인하고 다시 수정해 주세요.');throw err;}
            editingItemId=null;await showOrder(currentOrder.id);await load();message('inventory-success','매입 상품 정보를 저장했습니다.');
        });
    }
    $('purchase-view-dialog').addEventListener('close',()=>{++orderSequence;orderEditing=false;editingItemId=null;});
    async function openMovement(itemId,kind,origin=null) {
        try {const item=await apiGet('/api/inventory/items/'+encodeURIComponent(itemId));movementContext={item,kind,origin};const form=$('movement-form');form.reset();requestKeys.delete(form);message('movement-error',null);
            $('movement-title').textContent=item.productCode+' · '+labels[kind];$('movement-submit').textContent=kind==='RECEIPT'?'입고 처리':kind==='CANCEL_PENDING'?'미입고 취소':'기록 취소';
            field(form,'quantity').value=origin?origin.quantity:item.pending;field(form,'quantity').readOnly=kind==='REVERSE';field(form,'quantity').max=kind==='REVERSE'?origin.quantity:item.pending;field(form,'occurredAt').value=now();field(form,'reason').required=kind==='REVERSE';$('movement-dialog').showModal();
        }catch(err){message('inventory-error',err.message);}
    }
    $('movement-form').onsubmit=ev=>{ev.preventDefault();const form=ev.currentTarget;run(form,'movement-error',async()=>{const {item,kind,origin}=movementContext;const body={revision:item.revision,kind,...values(form,['occurredAt','reason']),quantity:integer(value(form,'quantity')),referenceId:origin?.id||null};
        let updated;try {updated=await postOnce(form,`/api/inventory/items/${item.id}/movements`,body);}
        catch(err){if(err.status===409){const latest=await apiGet('/api/inventory/items/'+encodeURIComponent(item.id));movementContext.item=latest;if(kind==='RECEIPT')field(form,'quantity').max=latest.pending;throw Error(err.message+' 최신 수량을 확인한 뒤 다시 처리해 주세요.');}throw err;}
        $('movement-dialog').close();
        if($('purchase-view-dialog').open)await showOrder(item.purchaseId);await load();message('inventory-success',labels[kind]+' 기록을 저장했습니다.');
    });};
    document.addEventListener('click',ev=>{const b=ev.target.closest('[data-edit-product],[data-cancel-product],[data-stock-product],[data-item],[data-order],[data-order-receipt],[data-receive],[data-cancel-pending],[data-reverse],[data-refund]');if(!b||b.disabled||busy)return;
        if(b.dataset.editProduct){if(orderEditing||editingItemId||currentOrder?.deletedAt)return;editingItemId=b.dataset.editProduct;message('purchase-item-error-'+editingItemId,null);syncOrderActions();syncPurchaseItemActions();return;}
        if(b.dataset.cancelProduct){run(b.closest('form'),'purchase-item-error-'+b.dataset.cancelProduct,()=>showOrder(currentOrder.id));return;}
        if((orderEditing||editingItemId)&&b.matches('[data-item],[data-order],[data-order-receipt],[data-receive],[data-cancel-pending],[data-reverse],[data-refund]')){message('purchase-view-error','수정 중인 내용을 저장하거나 취소해 주세요.');return;}
        if(b.dataset.stockProduct){showStockProduct(b.dataset.stockProduct);return;}
        if(b.dataset.orderReceipt){openOrderReceipt(b.dataset.orderReceipt);return;}
        if(b.dataset.item){showItem(b.dataset.item);return;}if(b.dataset.order){showOrder(b.dataset.order);return;}
        if(b.dataset.receive){openMovement(b.dataset.receive,'RECEIPT');return;}if(b.dataset.cancelPending){openMovement(b.dataset.cancelPending,'CANCEL_PENDING');return;}
        const id=b.dataset.reverse||b.dataset.refund,m=(b.closest('[data-item-history]')?itemHistories.get(b.closest('[data-item-history]').dataset.itemHistory)||[]:b.closest('#stock-movements')?stockMovementRows:movementRows).find(x=>x.id===id);if(!m)return;
        if(b.dataset.refund){currentRefund=m;fill($('refund-form'),{status:m.refundStatus,amount:m.refundAmount,refundedOn:m.refundedOn});message('refund-error',null);$('refund-dialog').showModal();return;}
        openMovement(m.itemId,'REVERSE',m);
    });
    $('refund-form')?.elements.namedItem('status').addEventListener('change',()=>{if(value($('refund-form'),'status')!=='COMPLETED')field($('refund-form'),'refundedOn').value='';if(value($('refund-form'),'status')==='NONE')field($('refund-form'),'amount').value='';});
    $('refund-form')?.addEventListener('submit',ev=>{ev.preventDefault();const form=ev.currentTarget;run(form,'refund-error',async()=>{await apiPost(`/api/inventory/movements/${currentRefund.id}/refund`,{revision:currentRefund.refundRevision,status:value(form,'status'),amount:integer(value(form,'amount'),true),refundedOn:value(form,'refundedOn')||null});$('refund-dialog').close();if($('purchase-view-dialog').open)await showOrder(currentOrder.id);await load();});});
    async function loadBrands(){try {const brands=await apiGet('/api/settings/brands');$('inventory-brand').innerHTML='<option value="">전체 브랜드</option><option value="UNASSIGNED">미지정</option>'+brands.map(b=>`<option value="${escape(b.id)}">${escape(b.name)}</option>`).join('');$('inventory-brand').disabled=false;}catch(err){message('inventory-brand-error','브랜드 목록을 불러오지 못했습니다. '+err.message);}}
    loadBrands();load();if(admin&&params.get('create')==='1')openCreate({product:productId});
})();
