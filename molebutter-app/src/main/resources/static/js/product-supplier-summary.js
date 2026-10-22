"use strict";
// Shared display only. Callers choose either their existing detail button or an internal detail link.
(() => {
  function create({escape:e,stamp,malls,label,safe,mallHtml,delta,money=v=>v==null?'미확인':Number(v).toLocaleString('ko-KR')+'원',link}) {
    link=link||((url,text)=>safe(url)?`<a href="${e(safe(url))}" target="_blank" rel="noopener noreferrer">${e(text)} ↗</a>`:e(text));
    const MallTag={html:mallHtml};
    const detailControl=(productId,view,text,classes)=>view.detailHref
      ?`<a class="${classes}" href="${e(view.detailHref)}" target="_blank" rel="noopener noreferrer">${e(text)}</a>`
      :`<button type="button" class="${classes}" data-detail="${e(productId)}" aria-label="${e(text)} · 상품 상세 보기">${e(text)}</button>`;
    const listingMall=l=>l.mall==='NAVER_SMART_STORE'&&l.priceStatus==='UNSUPPORTED_CHANNEL'?
        ((l.url||'').startsWith('https://smartstore.naver.com/')?'네이버 스마트스토어':'네이버 · 채널 미확인'):(malls[l.mall]||l.mall);
    const storeName=s=>[s?.retailer,s?.name].filter(Boolean).join(' · ');
    function taggedSource(l,externalMark=false){
        const content= MallTag.html(l.mall,listingMall(l))+(l.branchRequired===false?'':' '+e(storeName(l.store)||'지점 확인 필요'));
        const url=safe(l.url);
        return url?`<a class="supplier-list-source" href="${e(url)}" target="_blank" rel="noopener noreferrer">${content}${externalMark?' ↗':''}</a>`:`<span class="supplier-list-source">${content}</span>`;
    }
    const priceState={UNSUPPORTED_CHANNEL:'현재 조회 지원 대상 아님',CONFIRMED:'',MISSING:'이번 검색에서 미확인',FAILED:'최신 가격 확인 실패',STALE:'조회 기준 변경 · 재조회 필요',CHECKING:'조회 중',UNCONFIRMED:'최신 가격 미확인'};
    // 이번 조회분이 아니면 가격·재고 모두 이전 값이다. 재고 옆에 따로 적지 않고 상태 뒤에 한 번만 알린다.
    const staleNote=l=>l.selectedMissing?'선정 판매글 미발견':`${priceState[l.priceStatus]||'이전 조회 결과'} - 이전 가격 및 재고`;
    function productStockChoice(option) {
        const choices = option.simpleChoices || [];
        return option.stockScope === 'PRODUCT' && choices.length === 1 && String(choices[0].name || '').trim()
            ? choices[0].name : null;
    }
    function giftWrappingOnly(option) {
        const choices = option.simpleChoices || [];
        return choices.length === 2 && choices.every(choice => String(choice.groupName || '').replace(/\s/g, '') === '선물포장')
            && new Set(choices.map(choice => String(choice.name || '').trim().toUpperCase())).size === 2
            && choices.every(choice => ['O', 'X'].includes(String(choice.name || '').trim().toUpperCase()));
    }
    const stockTitle = option => option.stockScope === 'PRODUCT'
        ? (productStockChoice(option) || giftWrappingOnly(option) ? '재고' : '상품 전체 재고') : '재고';
    function listInventory(l,productId,options,single,inventory,view){
        const quantity=single&&Number.isFinite(options[0].stock)&&options[0].stock>=0;
        const confirmed=l.current&&!l.selectedMissing&&l.result?.state==='CONFIRMED'&&['SEARCH_RESULT','MATCHED'].includes(l.result?.match?.state)&&options.length>0;
        let status='';
        if(confirmed&&options.every(o=>o.stock===0))status='품절';
        else if(confirmed&&options.every(o=>['SOLD_OUT','UNAVAILABLE'].includes(o.state)))status='구매 불가능';
        const optionDetail=options.length>1||['OPTIONS_PARTIAL','OPTIONS_UNKNOWN','MULTIPLE'].includes(l.inventoryState);
        let text=quantity?`<strong class="supplier-list-quantity${status?' is-unavailable':confirmed&&options[0].stock<=3?' is-low-stock':''}" title="${e(stockTitle(options[0]))}">${e(view.stockLabel||stockTitle(options[0]))} ${Number(options[0].stock).toLocaleString('ko-KR')}개</strong>`:
            optionDetail?detailControl(productId,view,"재고 상세","product-name"):
            e(inventory.replace(/ · (구매 가능|구매 불가|품절)$/, '').replace(/^(구매 가능|구매 불가|품절)$/, ''));
        // 실제 실패·미확인은 남기되 옵션 구성 설명은 상세에서만 표시한다.
        const warning=(quantity||optionDetail)&&['FAILED','STOCK_UNKNOWN','UNCONFIRMED','CODE_REVIEW'].includes(l.inventoryState)&&l.current?`<span>${e(l.inventoryState==='FAILED'?'재고 조회 실패':label(l.inventoryState))}</span>`:'';
        return {text,warning,status:status&&!quantity?`<span class="product-badge bad">${status}</span>`:''};
    }
    function selectedSummary(l,productId=null,view={}){
        if(!l)return '<span class="product-meta">매입처 미선정</span>';
        const missing=l.selectedMissing?`<details class="selected-missing"><summary class="product-badge bad">선정 판매글 미발견</summary><span>이번 검색에서 찾지 못했습니다. 검색 범위 밖에 있을 수 있습니다.${l.selectedSearchAt?' 검색 확인 '+e(stamp(l.selectedSearchAt)):''}</span></details>`:'';
        const change=view.changes;
        const stockDelta=delta?.selectedStock(change,l,view.compact?productId:null)||'';
        const priceDelta=delta?.metric(change,l.id,'PRICE')||'',feeDelta=delta?.metric(change,l.id,'DELIVERY')||'';
        const state=priceState[l.priceStatus]??'이전 가격 · 참고용';let inventory=l.inventoryState==='OPTIONS_PARTIAL'?'일부 옵션만 확인':l.inventoryState==='MULTIPLE'?'옵션별 재고 확인':l.inventoryState==='FAILED'?'재고 조회 실패':l.current?label(l.inventoryState):'최신 재고 미확인';
        const options=l.result?.options||[],single=['SEARCH_RESULT','MATCHED'].includes(l.result?.match?.state)&&options.length===1;
        const quantity=o=>stockTitle(o)+' '+(o.stock==null?'수량 미제공':Number(o.stock).toLocaleString('ko-KR')+'개');
        // 이번 조회분이 아니어도 이전 재고 수치를 보여 주고, 이전 값이라는 안내는 경고 줄(staleNote)에 둔다.
        if(l.current&&single)inventory=quantity(options[0])+' · '+inventory;else if(!l.current&&options.length)inventory=single?quantity(options[0]):'옵션별 재고 확인';
        if(view.compact){
            const stock=listInventory(l,productId,options,single,inventory,view);
            const missingButton=l.selectedMissing?detailControl(productId,view,"선정 판매글 미발견","product-badge bad selected-missing-button"):'';
            const warnings=[!l.current?(l.selectedMissing?'':staleNote(l)):state?state+(l.referencePrice!=null?' · 마지막 가격 참고용':''):'',
                !l.preferred?'선호 목록에서 제외됨':'',l.branchRequired!==false&&l.conflict?'자동 지점 판별과 수동 지정이 다릅니다.':''].filter(Boolean);
            return `<div class="supplier-compact">
                <div class="supplier-compact-line supplier-compact-main"><span class="supplier-compact-price"><strong class="supplier-list-price">${money(l.referencePrice)}</strong><span class="supplier-price-change">${priceDelta}</span></span><span class="supplier-compact-delivery${l.deliveryFee!=null&&Number(l.deliveryFee)!==0?' supplier-delivery-paid':''}">배송비 ${money(l.deliveryFee)} ${feeDelta}</span><span class="supplier-compact-stock"><span class="supplier-stock-value">${stock.text}</span><span class="supplier-stock-notices">${stock.warning} ${stockDelta} ${stock.status} ${missingButton}</span></span>
                <span class="supplier-compact-source">${taggedSource(l)}</span></div>
                ${warnings.length?`<div class="supplier-compact-warnings product-warning">${warnings.map(e).join(' · ')}</div>`:''}
            </div>`;
        }
        const warnings=[!l.current?(l.selectedMissing?'':staleNote(l)):state?state+(l.referencePrice!=null?' · 마지막 가격 참고용':''):'',
            l.branchRequired!==false&&l.storeStatus==='HISTORICAL'?'이전 매장 정보 · 이번 업체·지점 미확인':'',
            !l.preferred?'선호 목록에서 제외됨':'',l.branchRequired!==false&&l.conflict?'자동 지점 판별과 수동 지정이 다릅니다.':''].filter(Boolean);
        const stock=l.inventoryState==='MULTIPLE'?`<a href="${l.preferred?'#supplier-results':'#selected-supplier-results'}">${e(inventory)}</a>`:e(inventory);
        const checked=l.priceCheckedAt?stamp(l.priceCheckedAt):null;
        return `${missing}<div class="selected-supplier-summary">
            <div class="selected-supplier-facts"><strong>${money(l.referencePrice)}</strong>${priceDelta}<span>배송비 ${money(l.deliveryFee)} ${feeDelta}</span><span class="selected-supplier-stock">${stock} ${stockDelta}</span></div>
            <div class="selected-supplier-source"><span>${view.mallTag?MallTag.html(l.mall,listingMall(l)):e(listingMall(l))}${l.branchRequired===false?'':(view.mallTag?' ':' · ')+e(storeName(l.store)||'지점 확인 필요')}</span>${link(l.url,'선정 판매글')}</div>
            ${checked&&checked!==stamp(view.latestAt)?`<span class="product-meta">가격 확인 ${e(checked)}</span>`:!checked?'<span class="product-meta">가격 확인 이력 없음</span>':''}
            ${warnings.length?`<div class="product-warning selected-supplier-warnings">${warnings.map(e).join(' · ')}</div>`:''}
        </div>`;
    }
    function rowFacts(l,productId){
        if(!l)return {price:'—',source:'매입처 미선정',delivery:'',stock:'매입처 미확인',notices:''};
        const options=l.result?.options||[],single=['SEARCH_RESULT','MATCHED'].includes(l.result?.match?.state)&&options.length===1;
        const stock=listInventory(l,productId,options,single,'매입처 미확인',{stockLabel:!l.current||l.selectedMissing?'이전 확인 재고':'매입처'});
        return {price:money(l.referencePrice),source:taggedSource(l,true),delivery:'배송비 '+money(l.deliveryFee),stock:stock.text,
            notices:[stock.warning,stock.status,l.selectedMissing?detailControl(productId,{},'정상 검색에서 미발견','product-badge bad selected-missing-button'):'',!l.preferred?'선호 목록에서 제외됨':'',l.branchRequired!==false&&l.conflict?'매장 확인 필요':''].filter(Boolean).join(' · ')};
    }
    return {render:selectedSummary,rowFacts,productStockChoice,stockTitle,storeName,priceState,staleNote};
  }
  window.ProductSupplierSummary={create};
})();
