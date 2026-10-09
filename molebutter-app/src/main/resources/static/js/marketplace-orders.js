(() => {
    'use strict';
    const labels=Object.fromEntries((globalThis.MarketplaceChannels||[]).map(c=>[c.id,c.label]));
    const active=status=>['QUEUED','RUNNING','CANCELLING'].includes(status);
    const retryable=status=>['PARTIAL','FAILED','CANCELLED','INTERRUPTED'].includes(status);
    const statusLabels={QUEUED:'수집 대기',RUNNING:'수집 중',CANCELLING:'취소 중',COMPLETED:'수집 완료',SUCCEEDED:'수집 완료',PARTIAL:'일부 수집 완료',FAILED:'수집 실패',CANCELLED:'수집 취소',INTERRUPTED:'수집 중단',PREPARING:'준비 중',PENDING:'대기'};
    function recentDates(now=new Date()) {const date=new Date(now.getTime()+9*3600000);const to=date.toISOString().slice(0,10);date.setUTCDate(date.getUTCDate()-6);return {from:date.toISOString().slice(0,10),to};}
    function dateError(from,to) {const a=Date.parse(from+'T00:00:00Z'),b=Date.parse(to+'T00:00:00Z');if(!/^\d{4}-\d{2}-\d{2}$/.test(from)||!/^\d{4}-\d{2}-\d{2}$/.test(to)||!Number.isFinite(a)||!Number.isFinite(b)||new Date(a).toISOString().slice(0,10)!==from||new Date(b).toISOString().slice(0,10)!==to)return '시작일과 종료일을 입력해 주세요.';if(a>b)return '종료일은 시작일 이후로 선택해 주세요.';if((b-a)/86400000>=31)return '수집 기간은 최대 31일입니다.';return '';}
    function money(value) {if(!value||value.amount==null)return '미확인';return value.currency==='KRW'?`${value.amount}원`:`${value.amount} ${value.currency||''}`.trim();}
    function orderStatus(market,status) {return market==='COUPANG'?({ACCEPT:'결제완료',INSTRUCT:'상품준비중',DEPARTURE:'배송지시',DELIVERING:'배송중',FINAL_DELIVERY:'배송완료',NONE_TRACKING:'직접배송',CLAIM_ONLY:'클레임 내역',ORDER_UNAVAILABLE:'취소·반품 내역만 조회 가능',ORDER_INVALID:'주문번호 오류',ORDER_ACCOUNT_MISMATCH:'판매자 불일치',ORDER_REJECTED:'주문 조회 거절',UNKNOWN:'주문 정보 미확인',NETWORK:'연결 실패',TIMEOUT:'조회 시간 초과',RATE_LIMIT:'호출 제한',UPSTREAM:'쿠팡 서버 오류',RESPONSE:'응답 형식 오류'}[status]||status||'미확인'):(status||'미확인');}
    function claimLabel(claim) {const type={CANCEL:'취소',RETURN:'반품',EXCHANGE:'교환'}[claim.type]||claim.type||'클레임';const codes=claim.type==='EXCHANGE'?{RECEIPT:'접수',PROGRESS:'진행',SUCCESS:'완료',REJECT:'불가',CANCEL:'철회'}:{RELEASE_STOP_UNCHECKED:'출고중지요청',RETURNS_UNCHECKED:'반품접수',VENDOR_WAREHOUSE_CONFIRM:'입고완료',REQUEST_COUPANG_CHECK:'쿠팡확인요청',RETURNS_COMPLETED:'반품완료'};return `${type} · ${codes[claim.status]||claim.status||'상태 미확인'}${claim.linked===false?' · 미연결':''}`;}
    function orderKey(row) {return JSON.stringify([row.market,row.orderId]);}
    if(typeof module!=='undefined'&&module.exports)module.exports={recentDates,dateError,money,orderKey,orderStatus,claimLabel,active,retryable};
    if(typeof document==='undefined')return;
    const {$,escape:e}=AppUI;if(!$('orders-query'))return;
    let page=0,result=null,listSeq=0,detailSeq=0,selected=null,job=null,poll=null,alive=true,mutationBusy=false,listBusy=false,pendingStart=null,history=[],jobSeq=0,historySeq=0,queryState=null;
    const read=(url)=>apiGet(url,{cache:'no-store'});
    const write=(url,body={})=>apiRequest(url,{method:'POST',cache:'no-store',headers:{'Content-Type':'application/json','X-Operation-Id':AppUI.uuid()},body:JSON.stringify(body)});
    const stamp=value=>{if(!value)return '미확인';const text=String(value);if(/(?:Z|[+-]\d{2}:\d{2})$/.test(text)){const time=Date.parse(text);if(Number.isFinite(time))return new Date(time+9*3600000).toISOString().slice(0,19).replace('T',' ')+' KST';}return text.replace('T',' ').slice(0,19);};
    function error(id,message) {$(id).textContent=message||'';$(id).hidden=!message;}
    function listButtons() {$('orders-prev').disabled=listBusy||page===0;$('orders-next').disabled=listBusy||!result||(page+1)*result.size>=result.total;}
    function renderRows(items) {let previous=null;return items.map(row=>{const key=orderKey(row),first=previous!==key;previous=key;const claimOnly=row.quantity==null&&(row.claims||[]).length>0;const claimValues=field=>Array.from(new Set((row.claims||[]).map(c=>c[field]).filter(v=>v!=null&&v!==''))).join(' / ');const claims=(row.claims||[]).map(claim=>e(claimLabel(claim))+claimBadge(claim)).join(' / ');return `<tr class="${first?'order-group-start':''}"><td>${e(labels[row.market]||row.market)}<span class="order-secondary">${e(row.orderId)}</span><span class="order-secondary">배송묶음 ${e(row.shipmentBoxId||claimValues('shipmentBoxId')||'미확인')}</span></td><td class="wrap">${e(claimOnly?((row.claims||[]).map(c=>c.productName||c.optionName).filter(Boolean).join(' / ')||'클레임 상품 정보 미제공'):(row.productName||'상품명 미확인'))}<span class="order-secondary">${e(row.optionName||claimValues('optionName')||'')}</span><span class="order-secondary">상품 ID ${e(row.vendorItemId||claimValues('vendorItemId')||'미확인')} · 관리코드 ${e(row.sellerProductCode||'미확인')}</span></td><td class="wrap" title="${e(row.status||'')}">${e(orderStatus(row.market,row.status))}<span class="order-secondary">${claims}</span></td><td>${claimOnly?'클레임 수량 '+e(claimValues('quantity')||'미제공'):e(row.quantity??'미확인')}<span class="order-secondary">${claimOnly?'주문 수량 미확인':'취소 '+e(row.cancelQuantity??'미확인')+' / 보류 '+e(row.holdQuantity??'미확인')}</span></td><td>${e(money(row.orderPrice))}</td><td>${e(stamp(row.orderedAt))}<span class="order-secondary">수집 ${e(stamp(row.collectedAt))}</span></td><td><button type="button" class="btn small" data-order-market="${e(row.market)}" data-order-id="${e(row.orderId)}">${row.status==='CLAIM_ONLY'||row.status==='ORDER_UNAVAILABLE'?'클레임 상세':'상세'}</button></td></tr>`;}).join('');}
    async function load(target=0) {const seq=++listSeq;listBusy=true;listButtons();error('orders-error','');$('orders-summary').textContent='수집한 주문 조회 중…';try{const params=new URLSearchParams({...queryState,page:String(target)});const data=await read('/api/marketplace-orders?'+params);if(seq!==listSeq||!alive)return;if(!Array.isArray(data?.items)||!Number.isSafeInteger(data.total)||!Number.isInteger(data.size)||data.size<1||!Number.isInteger(data.page)||data.page<0)throw Error('주문 목록 응답을 확인해 주세요.');result=data;page=data.page;$('orders-rows').innerHTML=renderRows(data.items)||'<tr><td colspan="7" class="empty">수집한 주문이 없습니다. 기간과 마켓을 선택해 주문을 수집해 주세요.</td></tr>';$('orders-summary').textContent=`상품 ${data.productTotal??data.total}건${data.claimOnlyTotal?' · 발주서 미확보 주문 '+data.claimOnlyTotal+'건':''} · 수집 시점 기준`;$('orders-page').textContent=data.total?`${page+1} / ${Math.ceil(data.total/data.size)}`:'';}catch(err){if(seq===listSeq&&alive){error('orders-error',err.message);$('orders-summary').textContent='조회 실패 · 다시 검색해 주세요.';}}finally{if(seq===listSeq){listBusy=false;listButtons();}}}
    const stageLabel=value=>({ORDER_PAGE:'주문 목록 조회',ORDER_DETAIL:'주문 상세 조회',CLAIMS:'클레임 조회',CLAIM_PAGE:'클레임 목록 조회',CLAIM_DETAIL:'클레임 상세 조회',COLLECT:'목록 조회',DETAIL:'상세 조회',PAGE:'목록 조회',SAVE:'조회 결과 저장',DONE:'완료',IDLE:'대기'}[value]||statusLabels[value]||value||'대기');
    const sourceLabel=value=>({RETURN_RU:'반품접수',RETURN_UC:'반품완료',RETURN_CC:'쿠팡확인요청',RETURN_PR:'출고중지요청',CANCEL:'취소',EXCHANGE:'교환',WITHDRAWN:'클레임 철회',CLAIMS_UNVERIFIED:'반품·취소'}[value]||orderStatus('COUPANG',value));
    const claimBadge=claim=>claim.latestVerified===true?'':' <span class="order-claim-unverified">최신 미확인</span>';
    function counts(progress) {return `<dl class="order-progress-counts">${[['주문',progress.orders],['상품',progress.items],['클레임',progress.claims],['미확인',progress.unknown]].map(([name,value])=>`<div><dt>${name}</dt><dd>${e(value??'미확인')}</dd></div>`).join('')}</dl>${progress.reconstructed===true?'<span class="order-count-reconstructed" title="현재 저장 자료로 복원한 집계">복원 수치</span>':''}`;}
    function drawJob() {
        if(!job)return;
        const running=active(job.status),progress=job.progress;
        $('orders-collect').disabled=running||mutationBusy;$('orders-cancel').hidden=!running;
        $('orders-cancel').disabled=mutationBusy||job.status==='CANCELLING';
        $('orders-retry').hidden=!retryable(job.status);$('orders-retry').disabled=mutationBusy;
        $('orders-job-history').disabled=mutationBusy;
        $('orders-collect-form').querySelectorAll('input').forEach(input=>{if(input.name!=='market'||input.value==='COUPANG')input.disabled=running||mutationBusy;});
        $('orders-job-summary').textContent=`${statusLabels[job.status]||job.status} · ${job.dateFrom} ~ ${job.dateTo}`;
        $('orders-job-progress').hidden=!progress;
        if(progress){
            const completed=progress.completedCheckpoints??0,total=progress.totalCheckpoints??0;
            const failed=progress.failedCheckpoints??(job.checkpoints||[]).filter(c=>['FAILED','PARTIAL','INTERRUPTED'].includes(c.status)).length;
            const current=job.currentStage||progress.currentStage;
            $('orders-job-progress').innerHTML=`<div><strong>구간 ${e(completed)} / ${e(total)} 완료</strong><span class="order-secondary">실패 ${e(failed)}구간${current?' · '+e(stageLabel(current)):''}</span></div>${total>0?`<progress max="${e(total)}" value="${e(completed)}" aria-label="완료한 수집 구간"></progress>`:''}${counts(progress)}`;
            const kept=retryable(job.status)&&completed>0;
            $('orders-job-retry-note').hidden=!kept;$('orders-job-retry-note').textContent=kept?`완료된 ${completed}구간을 유지하고 남은·실패 구간을 다시 수집합니다.`:'';
        }else{$('orders-job-progress').replaceChildren();$('orders-job-retry-note').hidden=true;}
        const failures=job.failures||[];$('orders-job-failures').hidden=!failures.length;
        $('orders-job-failure-summary').textContent=`실패 내역 ${failures.length}건${progress?.failedDetails>failures.length?' · 전체 '+progress.failedDetails+'건':''}`;
        $('orders-job-failure-content').innerHTML=failures.map(f=>`<article class="order-job-failure"><strong>${e(sourceLabel(f.source))} · ${e(f.dateFrom||'미확인')}${f.dateTo&&f.dateTo!==f.dateFrom?' ~ '+e(f.dateTo):''}</strong><span class="order-secondary">${e(stageLabel(f.stage))} · ${e(f.code||'미확인')}${f.orderId?' · 주문 '+e(f.orderId):''}</span>${f.message?'<p>'+e(f.message)+'</p>':''}</article>`).join('');
        $('orders-job-markets').innerHTML=(job.markets||[]).map(m=>`<li>${e(labels[m.market]||m.market)} · ${e(statusLabels[m.status]||m.status)}${m.progress?counts(m.progress):' · 상품 '+e(m.count??0)+'건'}${m.message?' · '+e(m.message):''}</li>`).join('');
    }
    async function loadHistory(selectLatest=false) {
        const seq=++historySeq,generation=jobSeq;
        try {
            const data=await read('/api/marketplace-orders/collections');
            if(!alive||seq!==historySeq||generation!==jobSeq)return;
            if(!Array.isArray(data))throw Error('수집 이력 응답을 확인해 주세요.');
            history=data;$('orders-job-history').innerHTML=history.map(j=>`<option value="${e(j.id)}">${e(j.dateFrom)} ~ ${e(j.dateTo)} · ${e(statusLabels[j.status]||j.status)}</option>`).join('')||'<option value="">수집 이력 없음</option>';
            if(selectLatest&&!job&&history.length){job=history[0];jobSeq++;drawJob();schedule();}
            if(job)$('orders-job-history').value=job.id;
        } catch(err){if(alive&&seq===historySeq&&generation===jobSeq)error('orders-collection-error',err.message);}
    }
    function schedule() {
        clearTimeout(poll);
        if(!job||!active(job.status)||!alive)return;
        poll=setTimeout(async()=>{
            const target=job.id,seq=jobSeq;
            try {
                const value=await read('/api/marketplace-orders/collections/'+encodeURIComponent(target));
                if(!alive||seq!==jobSeq||job?.id!==target)return;
                const wasActive=active(job.status);job=value;drawJob();
                if(wasActive&&!active(job.status)){load(page);loadHistory();}
                schedule();
            }catch(err){if(alive&&seq===jobSeq&&job?.id===target){error('orders-collection-error',err.message+' 수집 상태를 다시 확인합니다.');schedule();}}
        },1500);
    }
    async function mutate(action) {if(mutationBusy)return;error('orders-collection-error','');if(action==='start'){const message=dateError($('orders-from').value,$('orders-to').value);if(message){error('orders-collection-error',message);return;}}mutationBusy=true;drawJob();$('orders-collect').disabled=true;try{const url=action==='start'?'/api/marketplace-orders/collections':'/api/marketplace-orders/collections/'+encodeURIComponent(job.id)+'/'+action;const body=action==='start'?{markets:Array.from($('orders-collect-form').querySelectorAll('input[name="market"]:checked')).map(input=>input.value),dateFrom:$('orders-from').value,dateTo:$('orders-to').value}:{};if(action==='start'&&!body.markets.length)throw Error('수집할 마켓을 선택해 주세요.');if(action==='start'){const signature=JSON.stringify(body);if(!pendingStart||pendingStart.signature!==signature)pendingStart={signature,id:AppUI.uuid()};body.requestId=pendingStart.id;}const value=await write(url,body);if(!alive)return;job=value;jobSeq++;if(action==='start')pendingStart=null;drawJob();loadHistory();schedule();if(!active(job.status))load(page);}catch(err){if(alive)error('orders-collection-error',err.message);}finally{mutationBusy=false;if(job){drawJob();schedule();}else $('orders-collect').disabled=false;}}
    function renderDetail(data,market) {
        const field=(name,value)=>`<dt>${e(name)}</dt><dd>${e(value??'미제공')}</dd>`;
        const observed=(live)=>`<p class="order-detail-observed">${live?'최신 조회':'저장 관측'} · ${e(stamp(live?data.fetchedAt:data.storedCollectedAt))}</p>`;
        const shipments=data.shipments.map(shipment=>`<section><h4>배송묶음 ${e(shipment.shipmentBoxId||'미확인')}</h4><dl>${field('배송 상태',orderStatus(market,shipment.status))}${field('수령자',shipment.recipientName)}${field('연락처',shipment.phone)}${field('우편번호',shipment.postCode)}${field('주소',shipment.address)}${field('상세 주소',shipment.addressDetail)}${field('배송사',shipment.deliveryCompany)}${field('송장번호',shipment.invoiceNumber)}${field('배송 메시지',shipment.message)}</dl></section>`).join('');
        const products=data.items.map(item=>`<article><h4>${e(item.productName||'상품명 미확인')}</h4><dl>${field('옵션',item.optionName)}${field('배송묶음',item.shipmentBoxId)}${field('상품 ID',item.vendorItemId)}${field('관리코드',item.sellerProductCode)}${field('주문 상태',orderStatus(item.market||market,item.status))}${field('주문 수량',item.quantity)}${field('취소 수량',item.cancelQuantity)}${field('보류 수량',item.holdQuantity)}${field('단가',money(item.unitPrice))}${field('주문 금액',money(item.orderPrice))}${field('주문 일시',stamp(item.orderedAt))}${field('결제 일시',stamp(item.paidAt))}${field('상태 변경',stamp(item.statusUpdatedAt))}</dl></article>`).join('');
        const claims=new Map();for(const item of data.items)for(const claim of item.claims||[]){const key=JSON.stringify([claim.type,claim.id,claim.shipmentBoxId,claim.vendorItemId]);if(!claims.has(key))claims.set(key,claim);}
        const claimCards=Array.from(claims.values()).map(claim=>`<article><h4>${e(claimLabel(claim))}${claimBadge(claim)}</h4><dl>${field('접수번호',claim.id)}${field('상품명',claim.productName)}${field('옵션명',claim.optionName)}${field('옵션 ID',claim.vendorItemId)}${field('등록상품 ID',claim.sellerProductId)}${field('원 배송번호',claim.shipmentBoxId)}${field('취소·반품·교환 수량',claim.quantity)}${field('클레임 응답 주문 수량',claim.purchaseQuantity)}${field('접수 일시',stamp(claim.createdAt))}${field('변경 일시',stamp(claim.updatedAt))}${field('주문 연결',claim.linked===false?'미연결':'연결됨')}</dl></article>`).join('');
        if(data.unavailableReason)return `<p class="order-detail-observed">${e(orderStatus(market,data.unavailableReason))}</p><section class="order-detail-section"><h3>클레임</h3>${observed(false)}<div class="order-detail-products">${claimCards||'<p>저장된 클레임 정보가 없습니다. 주문 수집을 다시 실행해 주세요.</p>'}</div></section>${data.items.some(item=>item.quantity!=null)?'<section class="order-detail-section"><h3>기존 주문 관측</h3>'+observed(false)+'<div class="order-detail-products">'+products+'</div></section>':''}`;
        return `<section class="order-detail-section"><h3>배송묶음</h3>${observed(true)}<div class="order-detail-grid">${shipments||'<p>조회된 배송묶음이 없습니다.</p>'}</div></section><section class="order-detail-section"><h3>주문 상품</h3>${observed(true)}<div class="order-detail-products">${products||'<p>조회된 상품이 없습니다.</p>'}</div></section><section class="order-detail-section"><h3>클레임</h3>${observed(false)}<div class="order-detail-products">${claimCards||'<p>저장된 클레임 관측이 없습니다.</p>'}</div></section>`;
    }
    async function detail(market,id) {
        if(selected?.market===market&&selected?.id===id&&!$('orders-detail-loading').hidden)return;
        selected={market,id};const seq=++detailSeq;
        error('orders-detail-error','');$('orders-detail-content').replaceChildren();
        $('orders-detail-loading').hidden=false;$('orders-detail-retry').hidden=true;
        $('orders-detail-title').textContent=`${labels[market]||market} 주문 ${id}`;
        if(!$('orders-detail').open)$('orders-detail').showModal();
        try {
            const data=await read('/api/marketplace-orders/'+encodeURIComponent(market)+'/'+encodeURIComponent(id)+'/detail');
            if(seq!==detailSeq||!$('orders-detail').open||!alive)return;
            if(!Array.isArray(data?.shipments)||!Array.isArray(data.items))throw Error('주문 상세 응답을 확인해 주세요.');
            $('orders-detail-content').innerHTML=renderDetail(data,market);
        } catch(err){if(seq===detailSeq&&$('orders-detail').open&&alive){error('orders-detail-error',err.message);$('orders-detail-retry').hidden=false;}}
        finally{if(seq===detailSeq)$('orders-detail-loading').hidden=true;}
    }
    function captureQuery(){queryState={market:$('orders-market').value,status:$('orders-status').value.trim(),query:$('orders-search').value.trim(),claimType:$('orders-claim').value,dateBasis:$('orders-date-basis').value,dateFrom:$('orders-query-from').value,dateTo:$('orders-query-to').value,size:$('orders-size').value};}
    $('orders-query').addEventListener('submit',ev=>{ev.preventDefault();captureQuery();load(0);});$('orders-reset').addEventListener('click',()=>{$('orders-market').value='';$('orders-status').value='';$('orders-search').value='';$('orders-claim').value='';$('orders-date-basis').value='ORDERED';$('orders-query-from').value='';$('orders-query-to').value='';captureQuery();load(0);});$('orders-size').addEventListener('change',()=>{queryState={...queryState,size:$('orders-size').value};load(0);});$('orders-prev').addEventListener('click',()=>load(page-1));$('orders-next').addEventListener('click',()=>load(page+1));
    $('orders-collect-form').addEventListener('submit',ev=>{ev.preventDefault();mutate('start');});$('orders-cancel').addEventListener('click',()=>{clearTimeout(poll);mutate('cancel');});$('orders-retry').addEventListener('click',()=>mutate('retry'));
    $('orders-job-history').addEventListener('change',()=>{clearTimeout(poll);jobSeq++;job=history.find(j=>j.id===$('orders-job-history').value)||null;if(job){drawJob();schedule();}});
    $('orders-rows').addEventListener('click',ev=>{const button=ev.target.closest('[data-order-id]');if(button)detail(button.dataset.orderMarket,button.dataset.orderId);});$('orders-detail-close').addEventListener('click',()=>{$('orders-detail-content').replaceChildren();detailSeq++;selected=null;$('orders-detail').close();});$('orders-detail-retry').addEventListener('click',()=>{if(selected)detail(selected.market,selected.id);});
    $('orders-detail').addEventListener('close',()=>{detailSeq++;selected=null;$('orders-detail-content').replaceChildren();error('orders-detail-error','');$('orders-detail-loading').hidden=true;});
    window.addEventListener('pageshow',event=>{if(event.persisted){alive=true;selected=null;if($('orders-detail').open)$('orders-detail').close();load(page);loadHistory(!job);schedule();}});
    window.addEventListener('pagehide',()=>{alive=false;listSeq++;detailSeq++;historySeq++;jobSeq++;clearTimeout(poll);selected=null;$('orders-detail-content').replaceChildren();});
    $('orders-date-basis').value='ORDERED';const dates=recentDates();$('orders-from').value=dates.from;$('orders-to').value=dates.to;captureQuery();load(0);loadHistory(true);
})();
