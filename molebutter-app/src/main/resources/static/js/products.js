(() => {
    const { $, escape: e, stamp, pager } = AppUI;
    document.querySelectorAll('[data-close]:not([data-inventory-dialogs] [data-close])').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
    if (document.body.dataset.productView !== 'lookup-v11') { setError('page-error','서버를 다시 실행한 뒤 새로고침해 주세요.'); return; }
    const page = document.body.dataset.page, catalog = page === 'products';
    const money = v => v == null ? '미확인' : Number(v).toLocaleString('ko-KR')+'원';
    // 과거 조회에 저장된 제외 건수도 화면에서는 표시하지 않는다.
    const resultMessage = message => (message||'').split(' · ').filter(part=>!/^매입처(?:·코드)? 미확인(?:으로 제외)? \d+건$/.test(part.trim())&&!/^최대 (?:3|5)페이지 (?:조회|범위의 검색 결과입니다\. 이후 페이지는 확인하지 않았습니다\.)$/.test(part.trim())).join(' · ');
    const names = {SKIPPED_SAME_STORE:'재고 미조회',GROUP_UNCONFIRMED:'매장 재확인 필요',SUCCEEDED:'조회 완료',NOT_CHECKED:'미조회',PENDING:'대기',CHECKING:'조회 중',SUCCESS:'조회 완료',PARTIAL:'일부 확인 필요',SOLD_OUT:'품절',UNCONFIRMED:'확인 필요',NO_MATCH:'비교 결과 없음',FAILED:'조회 실패',BLOCKED:'접속 제한',STALE:'재조회 필요',CANCELLED:'취소',RUNNING:'진행 중',PAUSED:'중단',RETRY_WAIT:'자동 재개 대기',COMPLETED:'완료',AVAILABLE:'구매 가능',UNAVAILABLE:'구매 불가',STOCK_UNKNOWN:'재고 미확인',REVIEW:'확인 필요',OPTIONS_PARTIAL:'일부 옵션만 확인',OPTIONS_UNKNOWN:'옵션 미확인',CONFIRMED:'옵션 확인',DEFERRED:'재고 확인 보류',CODE_REVIEW:'이전 결과 · 재조회 필요'};
    const label = v => names[v] ?? v ?? '미조회';
    const badge = v => `<span class="product-badge ${['SUCCESS','MATCHED','AVAILABLE','COMPLETED'].includes(v)?'good':['FAILED','BLOCKED','SOLD_OUT'].includes(v)?'bad':'warn'}${v==='SOLD_OUT'?' stock-soldout':''}">${e(label(v))}</span>`;
    const safe = ProductSourceSearch.webUrl;
    const link = (url,text) => safe(url)?`<a href="${e(safe(url))}" target="_blank" rel="noopener noreferrer">${e(text)} ↗</a>`:e(text);
    const diagnosticSummary = diagnostics => {
        const rows=diagnostics||[],failed=rows.filter(d=>d.kind==='FAILED').length,skipped=rows.filter(d=>d.kind==='SKIPPED').length;
        return [failed?`추천 조회 실패 ${failed}건`:'',skipped?`추천 조회 생략 ${skipped}건`:''].filter(Boolean).join(' · ');
    };
    const diagnosticRows = diagnostics => !(diagnostics||[]).length?'':`<details class="recommendation-diagnostics"><summary>${e(diagnosticSummary(diagnostics))}</summary><p class="field-hint">추천 실패만 있는 조회 완료 상품은 ‘대상 재조회’에 포함되지 않습니다. 판매글을 확인하고 필요하면 새 최신화를 실행해 주세요.</p><ul>${diagnostics.map(d=>`<li><strong>${e(({LOTTE_IMALL:'롯데홈쇼핑',LOTTE_ON:'롯데ON',NAVER_SMART_STORE:'네이버 쇼핑윈도',LFMALL:'LF몰',HAZZYS:'헤지스',HI_THEHYUNDAI:'더현대Hi',HMALL:'현대Hmall'})[d.mall]||d.mall)}</strong> · ${e(money(d.searchPrice))} · ${d.kind==='SKIPPED'?'추천 조회 생략':'추천 조회 실패'}<br>${e(d.message||d.causeCode)} · ${e(stamp(d.createdAt))} · ${link(d.url,'판매글 확인')}</li>`).join('')}</ul></details>`;
    const naverSearchButton = query => query?.trim()
        ? `<a class="btn small naver-search" href="https://search.shopping.naver.com/search/all?${e(new URLSearchParams({adQuery:query,origQuery:query,pagingIndex:'1',pagingSize:'80',productSet:'total',query,sort:'price_asc',timestamp:'',viewType:'list'}).toString())}" target="_blank" rel="noopener noreferrer" aria-label="네이버 검색 (새 탭)">네이버</a>`
        : '<button type="button" class="btn small naver-search" disabled title="상품 검색어를 입력해 주세요.">네이버</button>';
    const photo = (url,name='상품') => {
        const src=safe(url);
        if(!src.startsWith('https:'))return '<span class="product-photo"><span class="product-photo-empty">사진 없음</span></span>';
        return `<button type="button" class="product-photo-button" data-image="${e(src)}" data-image-label="${e(name)} 상품 이미지" aria-label="${e(name)} 이미지 크게 보기" aria-haspopup="dialog"><span class="product-photo"><img src="${e(src)}" alt="" loading="lazy" referrerpolicy="no-referrer"></span></button>`;
    };
    document.addEventListener('error',event=>{if(event.target.matches?.('.product-photo img'))event.target.replaceWith(document.createTextNode('사진 없음'));},true);
    const delta=window.ProductChanges;
    let changeFilter='ALL';
    let mode='ALL',currentPage=0,listSequence=0,visible=[],total=0,brands=[],brandsLoaded=false,detail=null,detailSequence=0,dirty=false,busy=false,refreshing=false,editRevision=null,duplicates=[],deleteTargets=[],runId=/^[0-9]+$/.test(new URLSearchParams(location.search).get('run')||'')?new URLSearchParams(location.search).get('run'):null,runPage=0,runSequence=0;
    const selected = new Map();
    $('page-title').textContent=catalog?'상품 목록':'상품 최신화';
    // 서버에 이전 템플릿이 캐시되어 있어도 목록 작업 영역을 같은 구조로 맞춘다.
    if(catalog){
        $('selection-clear')?.remove();
        const count=$('product-selection'),actions=$('refresh-selected').parentElement;
        if(actions&&count.parentElement===actions.parentElement&&!count.parentElement.classList.contains('product-selection-bar')){
            const bar=document.createElement('div');bar.className='product-selection-bar';
            actions.before(bar);bar.append(count,actions);
        }
    }

    const error = (err,id='page-error') => setError(id,err?.message ?? String(err));
    function selection() {
        if(!catalog)return;
        $('product-selection').textContent=`조회 ${total}개 · 선택 ${selected.size}개`;
        for(const id of ['refresh-selected','bulk-on','bulk-off','bulk-brand','bulk-brand-infer','product-delete-selected'])$(id).disabled=busy||!selected.size;
        const n=visible.filter(p=>selected.has(p.id)).length;$('product-select-page').checked=!!visible.length&&n===visible.length;$('product-select-page').indeterminate=n>0&&n<visible.length;
    }
    async function action(button,fn,errorId='page-error') {
        if(busy||button?.disabled)return;busy=true;if(button)button.disabled=true;selection();setError(errorId,null);
        try {return await fn();} catch(err) {error(err,errorId);if(err.status===409){if(catalog){selected.clear();await loadProducts().catch(()=>{});}if(errorId==='detail-error'&&detail&&$('product-dialog').open){await loadDetail(detail.product.id,!dirty).catch(()=>{});}error(err,errorId);}} finally {busy=false;if(button)button.disabled=false;selection();updateRefreshButton();}
    }
    async function loadBrands() {
        brands=await apiGet('/api/settings/brands');
        for(const id of ['create-brand','edit-brand','bulk-brand-name']){
            $(id).innerHTML='<option value="">미지정</option>'+brands.map(b=>`<option value="${e(b.id)}">${e(b.name)}</option>`).join('');
            $(id).disabled=false;
        }
        if(detail)$('edit-brand').value=detail.product.brandId||'';
        brandsLoaded=true;
    }
    function selectedBrand(id){if(!brandsLoaded)throw Error('브랜드 목록을 불러오지 못했습니다. 화면을 새로고침해 주세요.');return $(id).value;}
    function inventoryLinks(p) {
        return `<div class="product-inventory"><span data-inventory-summary="${e(p.id)}">보유 재고 조회 중…</span><button type="button" class="btn small" data-stock-product="${e(p.id)}">재고 조회</button></div>`;
    }
    async function loadProducts() {
        if(!catalog)return;const seq=++listSequence;$('product-query').disabled=true;$('products-panel').setAttribute('aria-busy','true');
        setError('page-error',null);
        try {const params=new URLSearchParams({q:$('product-q').value,mode,status:$('product-status').value,page:currentPage,size:$('product-page-size').value,change:changeFilter});const data=await apiGet('/api/products?'+params);if(seq!==listSequence)return;
            if(!Array.isArray(data.items)||data.items.some(p=>typeof p.managed!=='boolean'||typeof p.searchQuery!=='string'))throw Error('화면과 서버 버전이 맞지 않습니다. 서버를 다시 실행해 주세요.');
            if(currentPage>0&&!data.items.length){currentPage=Math.max(0,data.totalPages-1);return loadProducts();}
            visible=data.items;total=data.totalElements;
            const counts=await apiGet('/api/products/change-counts?'+new URLSearchParams({q:$('product-q').value,mode,status:$('product-status').value}));if(seq!==listSequence)return;
            if($('selected-change-count'))$('selected-change-count').textContent=counts.selected||0;if($('all-change-count'))$('all-change-count').textContent=counts.all||0;
            $('product-rows').innerHTML=visible.map(p=>`<tr class="${p.changes?.anyChanged?'value-changed':''}"><td><input type="checkbox" data-select="${e(p.id)}" aria-label="${e(p.productCode)} 선택" ${selected.has(p.id)?'checked':''}></td><td class="product-description"><div class="product-identity">${photo(p.imageUrl,p.productCode)}<div class="product-code-inventory"><div class="product-code-info"><span class="product-meta">${e(p.brand||'브랜드 미지정')}</span><button type="button" class="product-name" data-detail="${e(p.id)}">${e(p.productCode||'코드 보완 필요')}</button>${p.duplicateCount?`<span class="product-warning">동일 전체 코드 ${p.duplicateCount}개 · 통합 후보</span>`:''}</div>${inventoryLinks(p)}</div></div></td><td><div class="product-market-cell">${selectedSummary(p.selectedSupplier,p.id||p.productId,{compact:true,latestAt:p.latestAt,changes:p.changes})}</div></td><td>${badge(p.latestStatus)}<span class="product-meta">${p.latestAt?e(stamp(p.latestAt)):'조회 이력 없음'}</span></td><td><button class="managed-switch" type="button" role="switch" aria-checked="${p.managed}" aria-label="${e(p.productCode)} 자동관리" data-managed="${e(p.id)}"><span></span><span>${p.managed?'켜짐':'꺼짐'}</span></button></td><td><div class="product-row-actions">${naverSearchButton(p.searchQuery)}<button type="button" class="btn small primary" data-refresh="${e(p.id)}" ${p.searchQuery&&p.productCode?'':'disabled'}>최신화</button><button type="button" class="btn small danger" data-delete="${e(p.id)}">삭제</button></div></td></tr>`).join('')||'<tr><td colspan="6" class="empty">상품이 없습니다. 직접 등록하거나 엑셀로 일괄등록하세요.</td></tr>';
            pager('product-pager',data,p=>{currentPage=p;loadProducts().catch(error);},{numbered:true});selection();
        }catch(err){if(seq===listSequence)error(err);}
        finally {if(seq===listSequence){$('product-query').disabled=false;$('products-panel').setAttribute('aria-busy','false');}}
    }
    const malls=MallTag.names;
    const listingMall=l=>l.mall==='NAVER_SMART_STORE'&&l.priceStatus==='UNSUPPORTED_CHANNEL'?
        ((l.url||'').startsWith('https://smartstore.naver.com/')?'네이버 스마트스토어':'네이버 · 채널 미확인'):(malls[l.mall]||l.mall);
    const storeName=s=>[s?.retailer,s?.name].filter(Boolean).join(' · ');
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
    function listInventory(l,productId,options,single,inventory){
        const quantity=single&&Number.isFinite(options[0].stock)&&options[0].stock>=0;
        const confirmed=l.current&&!l.selectedMissing&&l.result?.state==='CONFIRMED'&&['SEARCH_RESULT','MATCHED'].includes(l.result?.match?.state)&&options.length>0;
        let status='';
        if(confirmed&&options.every(o=>o.stock===0))status='품절';
        else if(confirmed&&options.every(o=>['SOLD_OUT','UNAVAILABLE'].includes(o.state)))status='구매 불가능';
        const optionDetail=options.length>1||['OPTIONS_PARTIAL','OPTIONS_UNKNOWN','MULTIPLE'].includes(l.inventoryState);
        let text=quantity?`<strong class="supplier-list-quantity${status?' is-unavailable':''}">${e(stockTitle(options[0]))} ${Number(options[0].stock).toLocaleString('ko-KR')}개</strong>`:
            optionDetail?`<button class="product-name" type="button" data-detail="${e(productId)}">재고 상세</button>`:
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
            const supplier=listingMall(l)+(l.branchRequired===false?'':' · '+(storeName(l.store)||'지점 확인 필요'));
            const stock=listInventory(l,productId,options,single,inventory);
            const missingButton=l.selectedMissing?`<button type="button" class="product-badge bad selected-missing-button" data-detail="${e(productId)}" aria-label="선정 판매글 미발견 · 상품 상세 보기">선정 판매글 미발견</button>`:'';
            const warnings=[!l.current?(l.selectedMissing?'':staleNote(l)):state?state+(l.referencePrice!=null?' · 마지막 가격 참고용':''):'',
                !l.preferred?'선호 목록에서 제외됨':'',l.branchRequired!==false&&l.conflict?'자동 지점 판별과 수동 지정이 다릅니다.':''].filter(Boolean);
            return `<div class="supplier-compact">
                <div class="supplier-compact-line supplier-compact-main"><span class="supplier-compact-price"><strong class="supplier-list-price">${money(l.referencePrice)}</strong><span class="supplier-price-change">${priceDelta}</span></span><span class="supplier-compact-delivery${l.deliveryFee!=null&&Number(l.deliveryFee)!==0?' supplier-delivery-paid':''}">배송비 ${money(l.deliveryFee)} ${feeDelta}</span><span class="supplier-compact-stock"><span class="supplier-stock-value">${stock.text}</span><span class="supplier-stock-notices">${stock.warning} ${stockDelta} ${stock.status} ${missingButton}</span></span>
                <span class="supplier-compact-source">${safe(l.url)?`<a class="supplier-list-source" href="${e(safe(l.url))}" target="_blank" rel="noopener noreferrer">${e(supplier)}</a>`:`<span class="supplier-list-source">${e(supplier)}</span>`}</span></div>
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
    function listingCard(l,needsReview=false,cards={}){
        const result=l.result||{offer:{mall:l.mall,mallName:malls[l.mall],url:l.url,imageUrl:l.imageUrl,price:l.referencePrice,deliveryFee:l.deliveryFee},match:{state:'REVIEW'},options:[],state:'UNCONFIRMED'};
        const view={...result,listingId:l.id,reference:!l.current,delta:detail.changes,branchRequired:l.branchRequired,branch:l.store?{...result.branch,name:storeName(l.store)}:result.branch};
        const selectable=l.selectable===true;
        const job=(detail.comparison?.stockLookups||[]).find(j=>j.supplierId===l.id);
        const pending=job&&['PENDING','RUNNING','BLOCKED'].includes(job.status);
        const stockAction=l.mall==='NAVER_SMART_STORE'&&l.current&&['SKIPPED_SAME_STORE','FAILED','OPTIONS_UNKNOWN','OPTIONS_PARTIAL','CONFIRMED'].includes(result.state)?`<button type="button" class="btn small" data-stock-query="${e(l.id)}" ${pending?'disabled':''}>${pending?(job.status==='RUNNING'?'재고 조회 중…':job.status==='BLOCKED'?'접속 제한':'재고 조회 대기'):'재고 조회'}</button>`:'';
        const actions=`<div class="supplier-listing-actions">${document.body.dataset.productAdmin==='true'?`<a class="btn small" href="/inventory?product=${encodeURIComponent(detail.product.id)}&supplier=${encodeURIComponent(l.id)}&create=1">이 매입처로 매입 등록</a>`:''}${stockAction}${l.selected?'<span class="product-badge good">선정 매입처</span>':`<button type="button" class="btn small primary" data-pick="${e(l.id)}" ${selectable?'':`disabled title="${e(l.selectionUnavailableReason||'선정 조건을 확인해 주세요.')}"`}>매입처로 선정</button>`}
            ${l.branchRequired!==false&&l.storeStatus==='HISTORICAL'?'<span class="product-warning">이전 매장 정보 · 이번 업체·지점 미확인</span>':''}${l.recommendationSaving!=null?`<span class="product-badge good">선정가보다 ${money(l.recommendationSaving)} 저렴</span>`:''}${l.priceStatus==='CONFIRMED'&&l.current?'':`<span class="field-hint">${e(l.current?priceState[l.priceStatus]||'이전 조회 결과':staleNote(l))}</span>`}
            ${l.branchRequired!==false&&l.conflict?`<span class="product-warning">자동 판별: ${e(result.branch?.name||result.branch?.evidence||'확인 필요')} · 지정 매장: ${e(storeName(l.store)||'미지정')}. 상품 페이지를 확인해 주세요.</span>`:''}
            ${l.priceStatus==='UNSUPPORTED_CHANNEL'||l.branchRequired===false||!needsReview&&!l.manual?'':`<div class="supplier-assignment toolbar">${needsReview||l.conflict?`<button type="button" class="btn small" data-assign="${e(l.id)}">${l.store?'매장 지정 변경':'업체·지점 확인 후 지정'}</button>`:''}${l.manual?`<button type="button" class="btn small" data-unassign="${e(l.id)}">수동 지정 해제</button>`:''}</div>`}</div>`;
        return supplierCards([view],actions,cards);
    }
    function renderStockBlock(job){
        let area=$('stock-global-block');if(!area){area=document.createElement('div');area.id='stock-global-block';area.className='stock-lookup-job';$('run-active').before(area);}
        area.hidden=!job;if(!job){area.innerHTML='';return;}
        area.innerHTML=`<span>${e(job.message||'개별 재고 조회의 접속 제한으로 최신화를 대기합니다.')}</span><a href="/products?product=${encodeURIComponent(job.productId)}">해당 상품</a><button type="button" class="btn small">접속 제한 해제·재개</button>`;
        area.querySelector('button').addEventListener('click',ev=>action(ev.currentTarget,async()=>{await apiPost(`/api/products/${job.productId}/suppliers/${job.supplierId}/stock-lookups/${job.id}/resume`,{revision:job.revision});await loadRuns();}));
    }
    function renderStockJobs(jobs){
        const area=$('stock-lookup-jobs');if(!area)return;
        const visible=jobs.filter(j=>['PENDING','RUNNING','BLOCKED','FAILED','STALE','UNCONFIRMED'].includes(j.status)||j.blocking);
        area.hidden=!visible.length;
        area.innerHTML=visible.map(j=>`<div class="stock-lookup-job" role="status"><span>${e(j.status==='PENDING'?'개별 재고 조회 대기 · 현재 상품 처리 후 순서대로 조회합니다.':j.status==='RUNNING'?'개별 재고 조회 중…':j.message||label(j.status))}</span>${['PENDING','RUNNING','BLOCKED'].includes(j.status)?`<button class="btn small" type="button" data-stock-cancel="${e(j.id)}">취소</button>`:''}${j.blocking?`<button class="btn small" type="button" data-stock-resume="${e(j.id)}">접속 제한 해제·재개</button>`:''}</div>`).join('');
    }
    let pollingStock=false;
    setInterval(async()=>{
        if(pollingStock||busy||document.hidden||!$('product-dialog').open||!(detail?.comparison?.stockLookups||[]).some(j=>['PENDING','RUNNING','BLOCKED'].includes(j.status)))return;
        const seq=detailSequence,id=detail.product.id;pollingStock=true;
        try{const data=await apiGet('/api/products/'+id);if(seq!==detailSequence||!$('product-dialog').open||detail?.product.id!==id)return;detail=data;renderDetail(!dirty);}
        catch(err){setError('detail-error','개별 조회 상태를 가져오지 못했습니다. '+err.message);}
        finally{pollingStock=false;}
    },5000);
    $('product-dialog').addEventListener('click',ev=>{
        const b=ev.target.closest('[data-stock-query],[data-stock-cancel],[data-stock-resume]');if(!b||busy)return;
        action(b,async()=>{
            const p=detail.product,c=detail.comparison;
            if(b.dataset.stockQuery){const id=b.dataset.stockQuery;const l=[...c.groups.flatMap(g=>g.listings),...(c.recommendations||[]).flatMap(g=>g.listings),...(c.selected?[c.selected]:[])].find(l=>l.id===id);
                await apiPost(`/api/products/${p.id}/suppliers/${id}/stock-lookups`,{revision:p.revision,supplierRevision:l.revision});
            }else{const job=c.stockLookups.find(j=>j.id===(b.dataset.stockCancel||b.dataset.stockResume));const base=`/api/products/${p.id}/suppliers/${job.supplierId}/stock-lookups/${job.id}`;
                if(b.dataset.stockCancel)await apiRequest(base,{method:'DELETE',headers:{'Content-Type':'application/json'},body:JSON.stringify({revision:job.revision})});
                else await apiPost(base+'/resume',{revision:job.revision});
            }
            await loadDetail(p.id,!dirty);
        },'detail-error');
    });
    // 같은 매장 묶음은 기본 접힘. 재고를 확인한 판매글(없으면 첫 판매글)과 선정 판매글은 접혀도 맨 위에 보인다.
    // 모달은 선정·폴링·최신화 때마다 다시 그리므로 펼침 상태는 여기 두고, 모달을 새로 열거나 상품이 바뀔 때만 초기화한다.
    const expandedGroups=new Set();
    const stockConfirmed=l=>(l.result?.options||[]).length>0&&!['SKIPPED_SAME_STORE','GROUP_UNCONFIRMED','FAILED'].includes(l.result?.state);
    const toggleLabel=(count,changed,open)=>open?'접기':`나머지 ${count}개 펼치기${changed?` · 변동 ${changed}`:''}`;
    function supplierGroups(groups,section){
        return groups.map((g,i)=>{
            const anchor=g.listings.find(stockConfirmed)||g.listings[0],pinned=[anchor,...g.listings.filter(l=>l.selected&&l!==anchor)];
            const rest=g.listings.filter(l=>!pinned.includes(l)),key=section+':'+g.id,open=expandedGroups.has(key),restId=`group-rest-${section}-${i}`;
            const changed=rest.filter(l=>delta?.listing(detail.changes,l.id)?.changed).length;
            const toggle=rest.length?`<button type="button" class="btn small supplier-group-toggle" data-group-toggle="${e(key)}" data-rest-count="${rest.length}" data-changed-count="${changed}" aria-expanded="${open}" aria-controls="${restId}">${e(toggleLabel(rest.length,changed,open))}</button>`:'';
            return `<section class="supplier-group${g.listings.length>1?' has-multiple-listings':''}${g.selected?' is-selected':''}"><div class="supplier-group-heading"><strong>${MallTag.html(g.mall)}${g.branchRequired===false?'':' '+e(storeName(g.store))}</strong><span>${g.listings.length}개 판매글</span><span>${g.minPrice==null?'이번 조회 가격 미확인':g.minPrice===g.maxPrice?money(g.minPrice):money(g.minPrice)+' ~ '+money(g.maxPrice)}</span>${g.selected?'<span class="product-badge good">선정 매입처</span>':''}${delta?.group(detail.changes,g.id)||''}${toggle}</div><div class="supplier-group-list">${pinned.map(l=>listingCard(l)).join('')}${rest.length?`<div class="supplier-group-rest" id="${restId}" data-group-rest="${e(key)}"${open?'':' hidden'}>${rest.map(l=>listingCard(l)).join('')}</div>`:''}</div></section>`;
        }).join('');
    }
    function setGroupOpen(key,open){
        if(open)expandedGroups.add(key);else expandedGroups.delete(key);
        const dialog=$('product-dialog'),button=[...dialog.querySelectorAll('[data-group-toggle]')].find(b=>b.dataset.groupToggle===key),rest=[...dialog.querySelectorAll('[data-group-rest]')].find(r=>r.dataset.groupRest===key);
        if(rest)rest.hidden=!open;
        if(button){button.setAttribute('aria-expanded',String(open));button.textContent=toggleLabel(Number(button.dataset.restCount),Number(button.dataset.changedCount),open);}
    }
    // 묶음 토글과, 변동 요약의 판매글 링크(#listing-…)가 접힌 판매글을 가리킬 때 먼저 펼친 뒤 이동한다.
    $('product-dialog').addEventListener('click',ev=>{
        const toggle=ev.target.closest('[data-group-toggle]');
        if(toggle){setGroupOpen(toggle.dataset.groupToggle,toggle.getAttribute('aria-expanded')!=='true');return;}
        const link=ev.target.closest('a[href^="#listing-"]');if(!link)return;
        const target=document.getElementById(decodeURIComponent(link.getAttribute('href').slice(1)));if(!target)return;
        const rest=target.closest('[data-group-rest]');if(rest?.hidden)setGroupOpen(rest.dataset.groupRest,true);
        ev.preventDefault();target.scrollIntoView({behavior:'smooth',block:'center'});
    });
    // 비선호 판매글을 선정하면(추천에서 선정했거나 선정 뒤 선호에서 빠진 경우) 서버 추천 목록에서는 빠진다.
    // 별도 '선정 매입처' 영역으로 올리지 않고, 추천 영역의 같은 매장 묶음(서버 groups()와 같은 키)에 넣어 제자리에 둔다.
    const shownPrice=l=>l?.result?.offer?.price??l?.referencePrice??null;
    const byShownPrice=(a,b)=>(shownPrice(a)??Infinity)-(shownPrice(b)??Infinity)||String(a.id).localeCompare(String(b.id));
    function recommendationGroups(c){
        const groups=(c.recommendations||[]).map(g=>({...g,listings:[...g.listings]})),s=c.selected;
        if(!s||s.preferred!==false||[...c.groups,...groups].some(g=>g.listings.some(l=>l.id===s.id)))return groups;
        const key=s.branchRequired!==false&&s.store?`${s.mall}:${s.store.id}`:`mall:${s.mall}`,priced=s.current&&s.priceStatus==='CONFIRMED'?shownPrice(s):null;
        const existing=groups.find(g=>g.id===key);
        if(existing){existing.listings=[...existing.listings,s].sort(byShownPrice);existing.selected=true;
            if(priced!=null){existing.minPrice=Math.min(existing.minPrice??priced,priced);existing.maxPrice=Math.max(existing.maxPrice??priced,priced);}}
        else groups.push({id:key,mall:s.mall,store:s.branchRequired!==false?s.store:null,branchRequired:s.branchRequired!==false,selected:true,minPrice:priced,maxPrice:priced,listings:[s]});
        return groups.sort((a,b)=>byShownPrice(a.listings[0],b.listings[0])||String(a.id).localeCompare(String(b.id)));
    }
    function renderComparison(){
        const c=detail.comparison;if(!c)return false;
        renderStockJobs(c.stockLookups||[]);
        const recommendations=recommendationGroups(c);
        const separateSelection=c.selected&&![...c.groups,...recommendations].some(g=>g.listings.some(l=>l.id===c.selected.id));
        $('selected-supplier-section').hidden=!separateSelection;$('selected-supplier-results').innerHTML=separateSelection?listingCard(c.selected,false,{mallTag:true}):'';
        $('supplier-results').innerHTML=supplierGroups(c.groups,'pref')||'<p class="field-hint">확인된 선호 매입처가 없습니다.</p>';
        const status=c.recommendationStatus||{state:c.selected?'REFRESH_REQUIRED':'NO_SELECTION'};
        $('recommendation-section').hidden=status.state==='NO_SELECTION';
        const messages={PRICE_UNCONFIRMED:'선정 매입처 가격 미확인으로 추천 보류',REFRESH_REQUIRED:'최신화 후 추천을 확인할 수 있습니다.',SELECTION_CHANGED:'선정 기준이 변경되었습니다. 최신화 후 추천을 확인해 주세요.',PARTIAL:'추천 일부 조회 · 추가 조회 한도에 도달했습니다.'};
        $('recommendation-results').innerHTML=(messages[status.state]?`<p class="field-hint">${e(messages[status.state])}</p>`:'')+(supplierGroups(recommendations,'rec')||(['READY','PARTIAL'].includes(status.state)?'<p class="field-hint">추천 조건에 맞는 비선호 매입처가 없습니다.</p>':''));return true;
    }
    function simpleChoiceDescription(option) {
        if (option.stockScope !== 'PRODUCT' || productStockChoice(option)) return '';
        const groups = new Map();
        for (const choice of option.simpleChoices || []) {
            if (!groups.has(choice.groupName)) groups.set(choice.groupName, []);
            groups.get(choice.groupName).push(choice.name);
        }
        return [...groups].map(([group, names]) => `<div class="lookup-option-name">${e(group)}: ${names.map(e).join(' / ')}</div>`).join('');
    }
    function supplierCards(list,actions='',{mallTag=false}={}) {
        return list.map(s=>{
            const o=s.offer, branch=s.branch;
            const branchRequired=s.branchRequired!==false;
            const branchText=branch?.name|| (branch?.state==='CONFLICT'?'지점 확인 필요':'지점 미확인');
            const options=s.options||[];
            const change=s.listingId?delta?.listing(s.delta,s.listingId):null;
            return `<article ${s.listingId?`id="listing-${e(s.listingId)}"`:''} class="lookup-supplier${change?.changed?' value-changed':''}"><div class="lookup-supplier-head">
                <div class="lookup-supplier-image">${photo(o.imageUrl,o.mallName)}</div>
                <div class="lookup-supplier-info"><div class="lookup-supplier-title">${mallTag&&o.mall?MallTag.html(o.mall):''}<strong class="lookup-mall">${e(o.mallName)}</strong>${branchRequired&&String(o.mallName||'').trim().toLowerCase()!==String(branchText).trim().toLowerCase()?`<span class="lookup-branch">${e(branchText)}</span>`:''}${link(o.url,'상품 페이지')}${change?.fresh?'<span class="value-new">신규 판매글</span>':''}</div>
                    ${branchRequired&&branch?.state==='CONFLICT'?`<p class="field-hint">${e(branch.evidence)}</p>`:''}
                    ${(s.sourceModelCode||s.match?.originalCode)?`<div class="lookup-code-line"><span>${e(s.sourceModelCode||s.match.originalCode)}</span></div>`:''}
                </div>
                <div class="lookup-search-price"><div class="lookup-price-line"><strong>${money(o.price)}</strong>${delta?.metric(s.delta,s.listingId,'PRICE')||''}<span class="product-meta">배송비 ${money(o.deliveryFee)} ${delta?.metric(s.delta,s.listingId,'DELIVERY')||''}</span></div>
                    ${options.map(simpleChoiceDescription).join('')}${options.length?`<ul class="lookup-options" aria-label="옵션별 재고와 구매 가능 여부">${options.map(v=>`<li class="lookup-option-row${s.reference?' is-reference':''}">${v.stockScope==='PRODUCT'&&!productStockChoice(v)?'':`<span class="lookup-option-name">옵션 ${e(productStockChoice(v)||v.label)}</span>`}<span class="lookup-stock ${v.stock===0?'empty-stock':''}">${e(stockTitle(v))} <strong>${v.stock==null?'수량 미제공':Number(v.stock).toLocaleString('ko-KR')+'개'}</strong></span>${delta?.option(s.delta,s.listingId,v)||''}${s.reference?`<span class="product-badge">${e(v.state==='SOLD_OUT'?'품절':label(v.state))}</span>`:v.state==='SOLD_OUT'?'<span class="product-badge bad">구매 불가 · 품절</span>':badge(v.state)}</li>`).join('')}</ul>`:`<p class="lookup-stock-note">${badge(s.state)}${['DEFERRED','SKIPPED_SAME_STORE'].includes(s.state)?'':' · 구매 가능 여부와 재고 미확인'}</p>`}
                    ${s.message?`<p class="field-hint">${e(s.message)}</p>`:''}${!options.length&&change?.lastStockAt?`<p class="field-hint">마지막 재고 확인 ${e(stamp(change.lastStockAt))}</p>`:''}${(change?.deltas||[]).filter(d=>d.kind==='OPTION_MISSING').map(d=>`<span class="product-meta">옵션 ${e(d.optionLabel)} · 이번 응답에서 미확인</span>`).join('')}
                    ${delta?.alternative(s.delta,s.listingId)?`<span class="change-cheaper">선정가보다 ${money(delta.alternative(s.delta,s.listingId).saving)} 저렴 · 구매 가능 확인</span>`:''}${change?.comparisonNote&&!change.fresh?`<p class="field-hint">${e(change.comparisonNote)}</p>`:''}${actions}
                </div></div></article>`;
        }).join('');
    }
    let historySequence=0;
    async function loadHistoryPage(page,id,sequence) {
        const currentDetail=()=>sequence===detailSequence&&$('product-dialog').open&&detail?.product.id===id;
        if(!currentDetail())return;
        const request=++historySequence;
        const current=()=>request===historySequence&&currentDetail();
        try {
            const data=await apiGet(`/api/products/${id}/history?page=${page}`);
            if(current())historyRows(data);
        } catch(err) {if(current())error(err,'detail-error');}
    }
    function historyRows(data) {
        historySequence++;
        const id=detail.product.id,sequence=detailSequence;
        $('history-results').innerHTML=data.items.map(h=>h.legacy?`<details class="lookup-history-item"><summary>${e(stamp(h.createdAt))} · 구조 전환 전 조회</summary><p>${e(h.payload.optionLabel||'이전 옵션 조회')} · ${e(h.payload.latestStatus||h.payload.status||'참고 이력')}</p>${(h.payload.latestResult?.observations||h.payload.result?.observations||h.payload.lastGoodResult?.observations||[]).map(o=>`<p>${link(o.url,o.mall)} · ${e(o.optionLabel)} · ${e(o.state)}</p>`).join('')}<p class="field-hint">이전 조회 기준의 참고 이력입니다. 현재 가격은 다시 조회해 주세요.</p></details>`:`<details class="lookup-history-item"><summary>${e(stamp(h.createdAt))} · ${e(label(h.payload.status))}</summary>${supplierCards(h.payload.suppliers||[],'',{mallTag:true})}</details>`).join('')||'<p class="field-hint">조회 이력이 없습니다.</p>';
        pager('history-pager',data,p=>loadHistoryPage(p,id,sequence),{numbered:true});
    }
    function renderDetail(fillForm=true) {
        const p=detail.product,r=p.latestResult,items=r?.suppliers||[];
        const message=resultMessage(r?.message);
        $('detail-summary').innerHTML=`<div class="detail-overview"><div class="product-identity product-detail-identity">${photo(p.imageUrl,p.productCode)}<div><span class="product-meta">${e(p.brand||'브랜드 미지정')}</span><strong>${e(p.productCode||'코드 보완 필요')}</strong><p class="detail-search-query"><span>네이버 검색어</span> ${e(p.searchQuery||'미입력')}</p>${inventoryLinks(p)}</div></div><section class="detail-selection"><div class="detail-selection-heading"><span>선정 매입처 · 사입 기준가</span>${p.selectedSupplier?'<button type="button" class="btn small" data-clear-selection>선정 해제</button>':''}</div>${selectedSummary(p.selectedSupplier,p.id,{latestAt:p.latestAt,changes:detail.changes,mallTag:true})}</section></div>`;
        $('detail-naver-search').innerHTML=naverSearchButton(p.searchQuery);
        $('detail-status').innerHTML=`<div class="detail-status-line">${badge(p.latestStatus)}<span>${p.latestAt?'최신화 '+e(stamp(p.latestAt)):'조회 이력 없음'}</span></div>${message?`<p class="detail-status-message">${e(message)}</p>`:''}`;
        if(detail.assessmentSettingsChanged)$('detail-status').insertAdjacentHTML('beforeend','<p class="detail-status-message">선호 설정이 변경되었습니다. 조회 상태는 마지막 최신화 기준이며, 변경한 매입처는 다음 최신화에서 확인합니다.</p>');
        const diagnostics=r?.recommendationDiagnostics||[];
        if(diagnostics.length)$('detail-status').insertAdjacentHTML('beforeend',`<p class="detail-status-message">${e(diagnosticSummary(diagnostics))} · <a href="/product-refresh?run=${encodeURIComponent(diagnostics[0].runId)}">작업 결과 확인</a></p>`);
        if(!renderComparison()){$('selected-supplier-section').hidden=true;$('supplier-results').innerHTML='<p class="field-hint">선호 매입처 정보를 다시 조회해 주세요.</p>';$('recommendation-section').hidden=true;}
        $('previous-result').innerHTML=detail.lastGoodResult?`<p class="field-hint">${e(stamp(detail.lastGoodResult.checkedAt))} 조회 · 현재 결과를 대신하지 않습니다.</p>${supplierCards(detail.lastGoodResult.suppliers||[],'',{mallTag:true})}`:'<p class="field-hint">이전 정상 조회가 없습니다.</p>';
        if($('product-change-summary'))$('product-change-summary').innerHTML=delta?.summary(detail.changes)||'';
        const oldSupplier=$('change-supplier')?.value||'';
        if($('change-supplier')){$('change-supplier').innerHTML='<option value="">전체 판매글</option>'+[...new Map([...(detail.changeSuppliers||[]),...(detail.changes?.listings||[])].map(l=>[l.supplierId,l])).values()].map(l=>`<option value="${e(l.supplierId)}">${e(l.name)} · ${e(l.supplierId.slice(-6))}</option>`).join('');$('change-supplier').value=oldSupplier;}
        if($('value-change-history')?.open)loadChangeHistory(0);
        updateRefreshButton();historyRows(detail.history);
        if(fillForm){editRevision=p.revision;$('edit-brand').value=p.brandId||'';$('edit-code').value=p.productCode;$('edit-query').value=p.searchQuery;dirty=false;window.AppNotifications?.cleanForm($('edit-form'));}
    }
    $('product-change-filters')?.addEventListener('click',ev=>{const b=ev.target.closest('[data-change-filter]');if(!b)return;changeFilter=b.dataset.changeFilter;currentPage=0;document.querySelectorAll('[data-change-filter]').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));loadProducts().catch(error);});
    $('product-dialog').addEventListener('click',ev=>{const b=ev.target.closest('#change-review');if(b)action(b,async()=>{await apiPost(`/api/products/${detail.product.id}/change-reviews`,{version:detail.changes.version});await loadDetail(detail.product.id,!dirty);if(catalog)await loadProducts();},'detail-error');});
    let changeHistorySequence=0;
    async function loadChangeHistory(page){
        if(!detail)return;const id=detail.product.id,seq=++changeHistorySequence;
        try{const data=await apiGet(`/api/products/${id}/changes?`+new URLSearchParams({page,kind:$('change-kind').value,supplier:$('change-supplier').value}));if(seq!==changeHistorySequence||detail?.product.id!==id)return;$('value-change-results').innerHTML=delta.history(data.items,id);pager('value-change-pager',data,loadChangeHistory,{numbered:true});}
        catch(err){if(seq===changeHistorySequence)error(err,'detail-error');}
    }
    $('value-change-history')?.addEventListener('toggle',()=>{if($('value-change-history').open)loadChangeHistory(0);});
    $('value-change-filter')?.addEventListener('submit',ev=>{ev.preventDefault();loadChangeHistory(0);});
    function updateRefreshButton() {
        if(!detail)return;
        const p=detail.product;
        $('detail-refresh').disabled=busy||refreshing||!p.productCode||!p.searchQuery||detail.activeRefresh===true||['PENDING','CHECKING','BLOCKED'].includes(p.latestStatus);
        $('detail-refresh').textContent=refreshing?'조회 중…':'최신화';
    }
    function progress(text,spinning=false,state=null) {
        refreshing=spinning;
        $('detail-progress').hidden=!text;$('detail-spinner').hidden=!spinning;$('detail-progress-text').textContent=text||'';
        const link=$('detail-run-link');link.hidden=!state?.runId||!['PAUSED','BLOCKED','RETRY_WAIT'].includes(state.runStatus)&&!state?.nextSearchAt&&!state?.searchGate?.manualResumeRequired&&!gateWaiting(state?.searchGate);
        if(state?.runId)link.href='/product-refresh?run='+encodeURIComponent(state.runId);
        $('supplier-results').setAttribute('aria-busy',String(spinning));updateRefreshButton();
    }
    const watcher=ProductRefreshWatch.create({
        fetchStatus:id=>apiGet(`/api/products/${id}/refresh-status`),
        canPoll:()=>!document.hidden&&!busy&&$('product-dialog').open,
        onState:state=>{
            if(state.active===true||state.active==null&&['PENDING','CHECKING','BLOCKED'].includes(state.status)) {
                if(state.runStatus!=='RETRY_WAIT'&&(state.searchGate?.manualResumeRequired||gateWaiting(state.searchGate)))progress(state.searchGate.manualResumeRequired?'네이버 검색 자동 재개 중단 · 작업 화면에서 확인해 주세요.':`네이버 검색 휴식 · ${stamp(state.searchGate.untilAt)} 이후 재개 예정`,false,state);
                else if(state.runStatus==='RETRY_WAIT')progress(`${searchReason(state)} · ${stamp(state.nextRetryAt)} 재개 예정`,false,state);
                else if(state.runStatus==='RUNNING'&&state.nextSearchAt)progress(`다음 검색 ${stamp(state.nextSearchAt)} 예정`,false,state);
                else if(state.runStatus==='PAUSED'&&state.nextRetryAt)progress(`자동 재개 중단 · ${stamp(state.nextRetryAt)} 이후 작업 화면에서 재개할 수 있습니다.`,false,state);
                else if(['PAUSED','BLOCKED'].includes(state.runStatus))progress(state.runStatus==='BLOCKED'?(state.message||'접속 제한으로 조회가 멈췄습니다. 작업 화면에서 재개할 수 있습니다.'):'작업이 중단되었습니다. 작업 화면에서 재개할 수 있습니다.',false,state);
                else progress(state.status==='PENDING'?'조회 대기 중입니다. 완료되면 결과를 자동으로 표시합니다.':'네이버 검색·옵션 재고 확인 중입니다. 완료되면 결과를 자동으로 표시합니다.',true,state);
            }
        },
        onComplete:async(state,current)=>{
            const seq=detailSequence, id=state.productId;
            const data=await apiGet('/api/products/'+id);
            if(!current()||seq!==detailSequence||!$('product-dialog').open||detail?.product.id!==id)return;
            detail=data;renderDetail(!dirty);
            // 완료 응답 직후 다른 탭에서 새 작업을 시작했으면 그 작업을 계속 확인한다.
            if(data.activeRefresh===true||['PENDING','CHECKING','BLOCKED'].includes(data.product.latestStatus)) {
                progress('새 조회 작업의 진행 상태를 확인하고 있습니다.',true);watcher.start(id);return;
            }
            const messages={SUCCESS:'조회가 완료되었습니다.',PARTIAL:'조회가 완료되었습니다. 일부 매입처·재고는 확인이 필요합니다.',SOLD_OUT:'조회가 완료되었습니다. 매입 가능한 대상이 없습니다.',NO_MATCH:'조회가 완료되었습니다. 표시할 매입처가 없습니다.',UNCONFIRMED:'조회가 완료되었습니다. 확인이 필요한 결과를 확인해 주세요.',FAILED:'조회에 실패했습니다. 다시 시도해 주세요.',STALE:'조회 기준이 변경되었습니다. 다시 조회해 주세요.',CANCELLED:'조회가 취소되었습니다.'};
            progress(messages[state.status]||state.message||'최신 상태를 표시했습니다.');
            await loadProducts();
        },
        onError:err=>{
            const denied=[401,403,404].includes(err.status);
            progress(denied?err.message:'진행 상태를 가져오지 못했습니다. 잠시 후 자동으로 다시 확인합니다.',!denied);
        }
    });
    async function loadDetail(id,fill=true) {
        watcher.stop();progress('');
        if(!$('product-dialog').open||String(detail?.product?.id)!==String(id))expandedGroups.clear();
        const seq=++detailSequence;const data=await apiGet('/api/products/'+id);if(seq!==detailSequence)return;
        detail=data;renderDetail(fill);setError('detail-success',null);setError('detail-error',null);
        if(!$('product-dialog').open)$('product-dialog').showModal();
        if(data.activeRefresh===true||['PENDING','CHECKING','BLOCKED'].includes(data.product.latestStatus)) {
            progress('조회 진행 상태를 확인하고 있습니다.',true);watcher.start(id);
        }
        duplicates=await apiGet(`/api/products/${id}/duplicates`);if(seq!==detailSequence)return;$('duplicate-section').hidden=!duplicates.length;
        $('duplicate-list').innerHTML=duplicates.map(p=>`<label class="inline-choice"><input type="checkbox" data-duplicate="${e(p.id)}"> ${e(p.brand||'브랜드 미지정')} · ${e(p.productCode)} · 검색어 ${e(p.searchQuery)}</label>`).join('');
        const choices=[detail.product,...duplicates].filter(p=>p.selectedSupplier);$('merge-selection').innerHTML='<option value="">선정이 하나면 유지 · 여러 개면 직접 선택</option>'+choices.map(p=>`<option value="${e(p.selectedSupplier.id)}">${e(p.productCode)} · ${e(malls[p.selectedSupplier.mall])} ${e(p.selectedSupplier.store?.name||'')} · ${money(p.selectedSupplier.referencePrice)}</option>`).join('');
    }
    async function start(ids,stay=false) {
        const seq=detailSequence;
        if(stay){watcher.stop();setError('detail-success',null);progress('조회 작업을 시작하고 있습니다.',true);}
        let r;
        try {r=await apiPost('/api/product-refresh',{scope:ids.length?'SELECTED':'ALL_MANAGED',ids});}
        catch(err){if(stay&&seq===detailSequence)progress('');throw err;}
        if(stay) {
            if(seq!==detailSequence||!$('product-dialog').open)return;
            detail.product.latestStatus='PENDING';detail.product.latestResult=null;renderDetail(false);
            progress('네이버 검색·옵션 재고 확인 중입니다. 완료되면 결과를 자동으로 표시합니다.',true);watcher.start(ids[0]);
            await loadProducts();
        } else location.href='/product-refresh?run='+encodeURIComponent(r.id);
    }
    function fields(prefix) {return {brandId:selectedBrand(prefix+'-brand'),productCode:$(prefix+'-code').value,searchQuery:$(prefix+'-query').value};}
    $('edit-form').addEventListener('input',()=>dirty=true);
    $('edit-form').addEventListener('submit',ev=>{ev.preventDefault();action(ev.submitter,async()=>{await apiPost('/api/products/'+detail.product.id,{...fields('edit'),revision:editRevision});await loadDetail(detail.product.id);await loadProducts();setError('detail-success','기본 정보를 저장했습니다.');},'detail-error');});
    $('product-dialog').addEventListener('click',ev=>{
        const b=ev.target.closest('button');if(!b||busy)return;
        const id=b.dataset.pick||b.dataset.assign||b.dataset.unassign,clear=b.hasAttribute('data-clear-selection');
        if(!id&&!clear)return;
        action(b,async()=>{
            const p=detail.product,c=detail.comparison,all=[...(c?.groups||[]).flatMap(g=>g.listings),...(c?.recommendations||[]).flatMap(g=>g.listings),...(c?.selected?[c.selected]:[])],l=all.find(v=>v.id===id);
            if(b.dataset.assign){await SupplierEditor.open({mall:l.mall,listing:l,productId:p.id,canOpen:()=>$('product-dialog').open&&detail?.product.id===p.id,admin:document.body.dataset.productAdmin==='true',onError:err=>setError('detail-error','저장은 완료됐지만 화면 조회에 실패했습니다. '+err.message),onSaved:async()=>{await loadDetail(p.id,!dirty);await loadProducts();setError('detail-success','매장 지정을 저장했습니다.');}});return;}
            if(b.dataset.unassign)await apiPost(`/api/products/${p.id}/suppliers/${id}/store`,{revision:l.revision,storeId:null});
            else await apiPost(`/api/products/${p.id}/selection`,{revision:p.revision,supplierId:clear?null:id});
            await loadDetail(p.id,!dirty);await loadProducts();setError('detail-success',clear?'선정을 해제했습니다.':b.dataset.pick?'매입처를 선정했습니다.':'매장 지정을 저장했습니다.');
        },'detail-error');
    });
    $('detail-refresh').addEventListener('click',ev=>action(ev.currentTarget,()=>start([detail.product.id],true),'detail-error'));
    $('detail-reload').addEventListener('click',ev=>action(ev.currentTarget,()=>loadDetail(detail.product.id),'detail-error'));
    $('merge-selected').addEventListener('click',ev=>action(ev.currentTarget,async()=>{const p=detail.product;const checked=[...document.querySelectorAll('[data-duplicate]:checked')].map(c=>duplicates.find(d=>d.id===c.dataset.duplicate));if(!checked.length)throw Error('통합할 중복 상품을 선택하세요.');await apiPost(`/api/products/${p.id}/merge`,{products:[p,...checked].map(v=>({id:v.id,revision:v.revision})),brandId:p.brandId||'',productCode:p.productCode,searchQuery:p.searchQuery,managed:p.managed,selectedSupplierId:$('merge-selection').value||null});await loadDetail(p.id);await loadProducts();},'detail-error'));
    function openDelete(rows){deleteTargets=rows.map(p=>({id:p.id,revision:p.revision}));$('product-delete-count').textContent=`${rows.length}개 상품을 삭제하시겠습니까?`;setError('product-delete-error',null);$('product-delete-dialog').showModal();}
    $('product-delete-form').addEventListener('submit',ev=>{ev.preventDefault();if(!$('product-delete-dialog').open||!deleteTargets.length)return;action(ev.submitter,async()=>{await apiPost('/api/products/delete',{products:deleteTargets});selected.clear();deleteTargets=[];$('product-delete-dialog').close();await loadProducts();},'product-delete-error');});
    $('create-form').addEventListener('submit',ev=>{ev.preventDefault();action(ev.submitter,async()=>{const p=await apiPost('/api/products',fields('create'));$('create-dialog').close();await loadProducts();await loadDetail(p.id);},'create-error');});
    $('brand-form').addEventListener('submit',ev=>{ev.preventDefault();action(ev.submitter,async()=>{await apiPost('/api/products/bulk',{products:[...selected.values()].map(p=>({id:p.id,revision:p.revision})),brandId:selectedBrand('bulk-brand-name')});selected.clear();$('brand-dialog').close();await loadProducts();},'brand-error');});
    document.querySelectorAll('[data-close]:not([data-inventory-dialogs] [data-close])').forEach(b=>b.addEventListener('click',()=>{if(!busy){if(b.dataset.close==='product-dialog'){detailSequence++;watcher.stop();progress('');}$(b.dataset.close).close();}}));
    document.querySelectorAll('dialog:not([data-inventory-dialogs] dialog)').forEach(d=>d.addEventListener('cancel',ev=>{if(busy)ev.preventDefault();else if(d.id==='product-dialog'){detailSequence++;watcher.stop();progress('');}}));
    $('product-dialog').addEventListener('close',()=>{detailSequence++;watcher.stop();progress('');});
    const imageDialog=$('product-image-dialog'),fullImage=$('product-image-full'),imageStatus=$('product-image-status');
    function openImage(button){
        const url=safe(button.dataset.image);if(!url.startsWith('https:')||imageDialog.open)return;
        const name=button.dataset.imageLabel||'상품 이미지';imageDialog.setAttribute('aria-label',name);fullImage.alt=name;
        fullImage.hidden=true;imageStatus.hidden=false;imageStatus.textContent='이미지를 불러오는 중…';
        fullImage.src=url;imageDialog.showModal();
    }
    fullImage.addEventListener('load',()=>{fullImage.hidden=false;imageStatus.hidden=true;});
    fullImage.addEventListener('error',()=>{fullImage.hidden=true;imageStatus.hidden=false;imageStatus.textContent='이미지를 불러오지 못했습니다.';});
    imageDialog.addEventListener('click',ev=>{if(ev.target!==imageDialog)return;const r=imageDialog.getBoundingClientRect();if(ev.clientX<r.left||ev.clientX>r.right||ev.clientY<r.top||ev.clientY>r.bottom)imageDialog.close();});
    imageDialog.addEventListener('close',()=>{fullImage.removeAttribute('src');fullImage.hidden=true;});
    document.addEventListener('click',ev=>{const button=ev.target.closest('[data-image]');if(button)openImage(button);});
    if(catalog) {
        $('product-filter').addEventListener('submit',ev=>{ev.preventDefault();selected.clear();currentPage=0;loadProducts().catch(error);});
        $('product-page-size').addEventListener('change',()=>{selected.clear();currentPage=0;loadProducts().catch(error);});
        $('management-tabs').addEventListener('click',ev=>{const b=ev.target.closest('[data-mode]');if(!b)return;mode=b.dataset.mode;selected.clear();currentPage=0;document.querySelectorAll('[data-mode]').forEach(t=>t.classList.toggle('active',t===b));loadProducts().catch(error);});
        $('product-rows').addEventListener('change',ev=>{const id=ev.target.dataset.select;if(!id)return;ev.target.checked?selected.set(id,visible.find(p=>p.id===id)):selected.delete(id);selection();});
        $('product-select-page').addEventListener('change',ev=>{visible.forEach(p=>ev.target.checked?selected.set(p.id,p):selected.delete(p.id));document.querySelectorAll('[data-select]').forEach(c=>c.checked=selected.has(c.dataset.select));selection();});
        $('product-rows').addEventListener('click',ev=>{const b=ev.target.closest('button');if(!b||busy)return;if(b.dataset.detail)action(b,()=>loadDetail(b.dataset.detail));else if(b.dataset.refresh)action(b,()=>start([b.dataset.refresh]));else if(b.dataset.delete)openDelete([visible.find(p=>p.id===b.dataset.delete)]);else if(b.dataset.managed)action(b,async()=>{const p=visible.find(p=>p.id===b.dataset.managed);await apiPost('/api/products/bulk',{products:[{id:p.id,revision:p.revision}],managed:!p.managed});selected.delete(p.id);await loadProducts();});});
        $('product-delete-selected').addEventListener('click',()=>openDelete([...selected.values()]));
        $('refresh-selected').addEventListener('click',ev=>action(ev.currentTarget,()=>start([...selected.keys()])));
        for(const [id,managed] of [['bulk-on',true],['bulk-off',false]])$(id).addEventListener('click',ev=>action(ev.currentTarget,async()=>{await apiPost('/api/products/bulk',{products:[...selected.values()].map(p=>({id:p.id,revision:p.revision})),managed});selected.clear();await loadProducts();}));
        $('bulk-brand').addEventListener('click',()=>{$('bulk-brand-name').value='';setError('brand-error',null);$('brand-dialog').showModal();});
        $('bulk-brand-infer').addEventListener('click',ev=>action(ev.currentTarget,async()=>{const r=await apiPost('/api/products/infer-brands',{products:[...selected.values()].map(p=>({id:p.id,revision:p.revision}))});setError('page-success',`브랜드 지정 ${r.assigned}개 · 기존 유지 ${r.preserved}개 · 확인 필요 ${r.unresolved}개`);selected.clear();await loadProducts();}));
        const registrationMenu=$('product-register-options');
        function closeRegistrationMenu(restoreFocus=false){registrationMenu.hidden=true;$('product-register').setAttribute('aria-expanded','false');if(restoreFocus)$('product-register').focus();}
        $('product-register').addEventListener('click',()=>{if(busy)return;registrationMenu.hidden=!registrationMenu.hidden;$('product-register').setAttribute('aria-expanded',String(!registrationMenu.hidden));});
        document.addEventListener('click',ev=>{if(!$('product-registration').contains(ev.target))closeRegistrationMenu();});
        $('product-registration').addEventListener('keydown',ev=>{if(ev.key==='Escape'&&!registrationMenu.hidden){ev.preventDefault();closeRegistrationMenu(true);}});
        $('product-registration').addEventListener('focusout',ev=>{if(!ev.currentTarget.contains(ev.relatedTarget))closeRegistrationMenu();});
        $('product-create').addEventListener('click',()=>{if(busy)return;closeRegistrationMenu();$('create-form').reset();setError('create-error',null);$('create-dialog').showModal();});
        function openImport(){if(busy||$('import-panel').open)return;closeRegistrationMenu();setError('import-error',null);$('import-panel').showModal();}
        $('product-import').addEventListener('click',openImport);
        for(const id of ['create-dialog','import-panel'])$(id).addEventListener('close',()=>{$('product-register').focus();});
        $('import-file').addEventListener('change',()=>{setError('import-error',null);$('import-result').textContent='';$('import-warnings').innerHTML='';});
        $('import-form').addEventListener('submit',ev=>{ev.preventDefault();action(ev.submitter,async()=>{
            const f=$('import-file').files[0];if(!f)throw Error('엑셀 파일을 선택해 주세요.');
            setError('import-error',null);$('import-result').textContent='등록 중…';$('import-warnings').innerHTML='';
            $('import-file').disabled=true;$('import-submit').textContent='등록 중…';$('import-form').setAttribute('aria-busy','true');
            try{
                const form=new FormData();form.append('file',f);
                const r=await apiRequest('/api/products/imports',{method:'POST',body:form});
                $('import-result').textContent=`전체 ${r.totalRows}행 · 신규 ${r.created} · 기존 ${r.existing} · 중복 ${r.duplicates} · 제외 ${r.excluded} · 브랜드 지정 ${r.brandsAssigned}`;
                $('import-warnings').innerHTML=r.warnings.map(w=>`<li>${e(w)}</li>`).join('');
                $('import-form').reset();window.AppNotifications?.cleanForm($('import-form'));
                selected.clear();currentPage=0;await loadProducts();
            }catch(err){if($('import-result').textContent==='등록 중…')$('import-result').textContent='';throw err;}
            finally{$('import-file').disabled=false;$('import-submit').textContent='등록';$('import-form').setAttribute('aria-busy','false');}
        },'import-error');});
        // 기존 엑셀 등록 알림 주소도 일괄등록 모달로 연결한다.
        if(location.hash==='#import-panel')openImport();
        window.addEventListener('hashchange',()=>{if(location.hash==='#import-panel')openImport();});
        // 목록 조회는 설정 조회의 성공 여부와 분리한다.
        resetFilters();loadProducts().catch(error);
        const linkedProduct=new URLSearchParams(location.search).get('product');
        if(linkedProduct&&/^[0-9]+$/.test(linkedProduct))loadDetail(linkedProduct).catch(error);
        window.addEventListener('pageshow',event=>{if(event.persisted){resetFilters();loadProducts().catch(error);}});
    }
    function resetFilters(){mode='ALL';currentPage=0;selected.clear();$('product-q').value='';$('product-status').value='';$('product-page-size').value='100';document.querySelectorAll('[data-mode]').forEach(b=>{b.classList.toggle('active',b.dataset.mode==='ALL');b.setAttribute('aria-pressed',String(b.dataset.mode==='ALL'));});selection();}
    // 최신화 작업 이력: 조회 조건은 제출 시점 값으로 고정해(폴링이 입력 중인 값을 쓰지 않도록) 쓰고, URL에는 ?run=만 남긴다.
    let runFilter=null,runListPage=0,runListSequence=0,runsHaveActive=false,runsLoadedAt=0;
    const searchReason = r => ({LOGIN_REQUIRED:'네이버 로그인 감지',API_LOGIN_REDIRECT:'네이버 검색 API 로그인 요구',SEARCH_NO_REQUEST:'네이버 검색 응답 지연',SEARCH_RESPONSE_TIMEOUT:'네이버 검색 응답 지연',SEARCH_NETWORK_ERROR:'네이버 검색 통신 오류',RESPONSE_MISMATCH:'네이버 검색 응답 검증 불일치',RESPONSE_SCHEMA_CHANGED:'네이버 검색 응답 형식 확인 필요',UI_ELEMENT_MISSING:'네이버 검색 화면 확인 필요',INTERRUPTED:'이전 검색 실행 중단',ACCESS_RESTRICTED:'네이버 검색 접속 제한'})[r.searchFailureCode||r.blockReason]||'네이버 로그인 감지';
    let gateState=null,attemptPage=0,attemptSequence=0;
    const gateWaiting=g=>g?.untilAt&&Date.parse(g.untilAt+'+09:00')>Date.now();
    function renderSearchGate(g){
        gateState=g;const el=$('search-gate');if(!el)return;
        el.hidden=!g?.manualResumeRequired&&!gateWaiting(g);
        renderOnce(el,el.hidden?'':`<span>${g.manualResumeRequired?'네이버 검색 자동 재개 중단':`네이버 검색 휴식 · ${e(stamp(g.untilAt))} 이후 실행 가능`}</span>${g.manualResumeRequired?` <button type="button" class="btn small" data-search-resume ${gateWaiting(g)?'disabled':''}>검색 재개</button>`:''}`);
    }
    async function loadAttempts(){
        const seq=++attemptSequence,requested=runId;if(!requested)return;
        const params=new URLSearchParams({page:attemptPage,code:$('search-attempt-code').value});
        if($('search-attempt-product').value)params.set('product',$('search-attempt-product').value);
        const d=await apiGet(`/api/product-refresh/${encodeURIComponent(requested)}/search-attempts?${params}`);
        if(seq!==attemptSequence||requested!==runId)return;
        const stages={HOME:'네이버 홈',SEARCH:'검색',SORT:'가격 정렬',PAGE_SIZE:'표시 개수',SMART_PRICE:'가격 기준',PAGING:'페이지 이동',BROWSER:'브라우저'};
        const states={SUCCEEDED:'검색 완료',RUNNING:'검색 중',RETRY_WAIT:'대기 예약',BLOCKED:'수동 확인',CANCELLED:'취소',STALE:'기준 변경',INTERRUPTED:'실행 중단'};
        renderOnce($('search-attempt-results'),d.items.map(a=>`<article class="lookup-history-item"><strong>${e(a.productCode||a.productId)}</strong> · ${e(a.attemptNo)}번째 검색 · ${e(states[a.status]||a.status)}<p>${e(stamp(a.startedAt))} · ${e(stages[a.stage]||'검색')} ${e(a.message||'')}${a.httpStatus?' · HTTP '+e(a.httpStatus):''}</p>${a.retryAt?`<p>재개 예정 ${e(stamp(a.retryAt))}</p>`:''}${a.diagnostics?`<p class="field-hint">요청 ${e(a.diagnostics.requests??'—')} · 응답 ${e(a.diagnostics.responses??'—')}${a.diagnostics.mismatches?.length?' · 검증 불일치 '+e(a.diagnostics.mismatches.map(k=>({QUERY:'검색어',SORT:'정렬',PAGE:'페이지',PAGE_SIZE:'표시 개수',SMART_PRICE:'가격 기준',SCHEMA:'응답 구조',JSON_FORMAT:'응답 형식'})[k]||k).join(', ')):''}</p>`:''}</article>`).join('')||'<p class="field-hint">기록된 검색 시도가 없습니다. 과거 작업에는 시도별 기록이 없을 수 있습니다.</p>');
        pager('search-attempt-pager',d,p=>{attemptPage=p;loadAttempts().catch(error);},{numbered:true});
    }
    const RUN_ACTIVE=['RUNNING','PAUSED','BLOCKED','RETRY_WAIT'];
    // 5초 폴링이 같은 내용을 다시 그려 버튼 포커스·클릭을 빼앗지 않도록 바뀐 경우에만 교체한다.
    const rendered=new WeakMap(),renderOnce=(el,html)=>{if(rendered.get(el)!==html){rendered.set(el,html);el.innerHTML=html;}};
    function kstDay(daysAgo=0){
        const parts=new Intl.DateTimeFormat('en',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(Date.now()-daysAgo*86400000));
        const v=type=>parts.find(p=>p.type===type).value;return `${v('year')}-${v('month')}-${v('day')}`;
    }
    const runFilterValues=()=>({from:$('run-from').value,to:$('run-to').value,status:$('run-status').value,failed:$('run-failed').checked,size:$('run-page-size').value});
    function resetRunFilter(){$('run-from').value=kstDay(6);$('run-to').value=kstDay(0);$('run-status').value='';$('run-failed').checked=false;$('run-page-size').value='20';runListPage=0;runFilter=runFilterValues();}
    function runNote(r){return r.status==='RETRY_WAIT'?`${searchReason(r)} · ${stamp(r.nextRetryAt)} 재개 예정`:r.status==='PAUSED'&&r.nextRetryAt?`자동 재개 중단 · ${stamp(r.nextRetryAt)} 이후 재개 가능`:r.nextSearchAt?`다음 검색 ${stamp(r.nextSearchAt)} 예정`:r.message||'';}
    function runRow(r){
        const recommendationNote=Number(r.recommendationFailed||0)||Number(r.recommendationSkipped||0)?`<p class="field-hint">추천 판매글: 조회 실패 ${Number(r.recommendationFailed||0)}건 · 생략 ${Number(r.recommendationSkipped||0)}건</p>`:'';
        const note=runNote(r),retryable=Number(r.retryable||0),actions=RUN_ACTIVE.includes(r.status)?(['RUNNING','RETRY_WAIT'].includes(r.status)?['pause','cancel']:['resume','cancel']):['retry'];
        const breakdown=retryable?`<p class="field-hint">조회 실패 ${Number(r.failedCount||0)}개 · 재고 확인 필요 ${Number(r.partialRetryCount??r.partial??0)}개${Number(r.otherRetryCount)?` · 기타 ${Number(r.otherRetryCount)}개`:""}</p>`:"";
        const failures=!RUN_ACTIVE.includes(r.status)&&retryable?`${note?'<br>':''}<span class="product-warning">재조회 대상 ${retryable.toLocaleString('ko-KR')}건</span>${breakdown}`:'';
        return `<tr${r.id===runId?' class="run-selected" aria-current="true"':''}><td>${e(stamp(r.createdAt))}</td><td>${badge(r.status)} ${r.done}/${r.total}</td><td>${e(note)}${failures}${recommendationNote}</td><td><div class="product-row-actions"><button type="button" class="btn small" data-run="${e(r.id)}">결과</button>${actions.map(a=>{
            const disabled=a==='resume'&&r.nextRetryAt&&Date.parse(r.nextRetryAt+'+09:00')>Date.now()?'disabled':a==='retry'&&!retryable?'disabled title="재조회할 상품이 없습니다."':'';
            return `<button type="button" class="btn small" data-run="${e(r.id)}" data-action="${a}" ${disabled}>${a==='pause'&&r.status==='RETRY_WAIT'?'자동 재개 중단':{pause:'중단',cancel:'취소',resume:'재개',retry:'대상 재조회'}[a]}</button>`;}).join('')}</div></td></tr>`;
    }
    async function loadRuns() {
        const seq=++runListSequence,f=runFilter;
        const params=new URLSearchParams({from:f.from,to:f.to,status:f.status,page:runListPage,size:f.size});
        if(f.failed)params.set('failed','true');if(runId)params.set('run',runId);
        const data=await apiGet('/api/product-refresh?'+params);if(seq!==runListSequence)return;
        if(runListPage>0&&!data.items.length){runListPage=Math.max(0,data.totalPages-1);return loadRuns();}
        runsLoadedAt=Date.now();runsHaveActive=data.active.length>0||gateWaiting(data.searchGate);renderStockBlock(data.stockLookupBlock);renderSearchGate(data.searchGate);
        // 진행 중 작업은 조회 조건·페이지에 가려도 중단·취소·재개할 수 있도록 목록에 없을 때만 위에 고정한다.
        const shown=new Set(data.items.map(r=>r.id)),pinned=data.active.filter(r=>!shown.has(r.id));
        $('run-active').hidden=!pinned.length;renderOnce($('run-active-rows'),pinned.map(runRow).join(''));
        $('run-count').textContent=`조회 ${data.totalElements.toLocaleString('ko-KR')}건`;
        renderOnce($('run-rows'),data.items.map(runRow).join('')||'<tr><td colspan="4" class="empty">조건에 맞는 최신화 작업이 없습니다.</td></tr>');
        pager('run-pager',data,p=>{runListPage=p;loadRuns().catch(error);},{numbered:true,label:'최신화 작업 페이지 이동'});
        const s=data.selected;$('run-detail-meta').textContent=s?`${stamp(s.createdAt)} 시작 · ${label(s.status)} · ${s.done}/${s.total}`:'';
        if(runId)await loadRun();
        if($('search-attempts')?.open)await loadAttempts();
    }
    async function loadRun() {
        const seq=++runSequence;const d=await apiGet(`/api/product-refresh/${encodeURIComponent(runId)}?page=${runPage}`);if(seq!==runSequence)return;$('run-detail').hidden=false;
        $('run-item-rows').innerHTML=d.items.map(p=>`<tr><td>${e(p.productCode)}</td><td>${badge(p.status)}</td><td>${selectedSummary(p.selectedSupplier,p.id||p.productId)}</td><td>${e(resultMessage(p.result?.message))}${diagnosticRows(p.recommendationDiagnostics||p.result?.recommendationDiagnostics)}</td><td>${p.deleted?'삭제·통합된 상품':`<button type="button" class="btn small" data-detail="${e(p.productId)}">매입처 비교</button>`}</td></tr>`).join('')||'<tr><td colspan="5">상품별 결과가 없습니다. 이전 구조의 조회 이력은 상품 상세에서 확인할 수 있습니다.</td></tr>';
        const filter=$('search-attempt-product');if(filter){const old=filter.value;filter.innerHTML='<option value="">전체 상품</option>'+d.items.map(p=>`<option value="${e(p.productId)}">${e(p.productCode)}</option>`).join('');filter.value=old;}
        pager('run-item-pager',d,p=>{runPage=p;loadRun().catch(error);},{numbered:true,label:'상품별 조회 결과 페이지 이동'});
    }
    if(!catalog){
        resetRunFilter();
        $('search-gate')?.addEventListener('click',ev=>{const b=ev.target.closest('[data-search-resume]');if(b)action(b,async()=>{await apiPost('/api/product-refresh/search-gate/resume',{version:gateState.version});await loadRuns();});});
        $('search-attempts')?.addEventListener('toggle',()=>{if($('search-attempts').open){attemptPage=0;loadAttempts().catch(error);}});
        $('search-attempt-filter')?.addEventListener('submit',ev=>{ev.preventDefault();attemptPage=0;loadAttempts().catch(error);});
        $('refresh-all').addEventListener('click',ev=>action(ev.currentTarget,()=>start([])));$('runs-reload').addEventListener('click',ev=>action(ev.currentTarget,loadRuns));
        $('run-filter').addEventListener('submit',ev=>{
            ev.preventDefault();const f=runFilterValues();
            if(f.from&&f.to&&f.from>f.to){setError('page-error','조회 시작일이 종료일보다 늦습니다.');return;}
            action($('run-query'),()=>{runFilter=f;runListPage=0;return loadRuns();});
        });
        // 제어 요청이 실패해도(다른 곳에서 이미 상태가 바뀐 경우 등) 목록은 최신 상태로 다시 그린다.
        const runClick=ev=>{const b=ev.target.closest('[data-run]');if(!b)return;action(b,async()=>{runId=b.dataset.run;runPage=0;let failure=null;
            if(b.dataset.action){try{const r=await apiPost(`/api/product-refresh/${runId}/${b.dataset.action}`,{});if(r?.id)runId=r.id;}catch(err){failure=err;}}
            history.replaceState(null,'','/product-refresh?run='+encodeURIComponent(runId));await loadRuns().catch(err=>{if(!failure)throw err;});if(failure)throw failure;});};
        $('run-rows').addEventListener('click',runClick);$('run-active-rows').addEventListener('click',runClick);
        window.addEventListener('pageshow',event=>{if(event.persisted){resetRunFilter();loadRuns().catch(error);}});
        $('run-item-rows').addEventListener('click',ev=>{const b=ev.target.closest('[data-detail]');if(b)action(b,()=>loadDetail(b.dataset.detail));});
        apiGet('/api/product-refresh/settings').then(s=>$('schedule-summary').textContent=s.scheduleEnabled?`매일 ${s.scheduleTime} (한국 시간) · 자동관리 상품 실행`:'자동 최신화 꺼짐').catch(error);loadRuns().catch(error);
    }
    loadBrands().catch(error);
    let runsLoading=false;
    // 진행 중 작업이 있으면 5초, 없으면 30초(예약 실행 시작 감지용)마다 현재 조건·페이지 그대로 갱신한다.
    setInterval(async()=>{if(document.hidden||busy||catalog||runsLoading||!runsHaveActive&&Date.now()-runsLoadedAt<30000)return;runsLoading=true;try{await loadRuns();}catch(err){error(err);}finally{runsLoading=false;}},5000);
})();
