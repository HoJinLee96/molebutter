"use strict";
(() => {
  const {$,escape:e}=AppUI;
  const filterKeys=['id','brand','name','sku','stock','stockComparison','saleStatus','shippingDays','shippingComparison'];
  let submitted={},index=0,result=null,busy=false,refreshPending=false,sequence=0,deleted=false,group='SELLING',autoStock=null,submittedAutoStock=null,reason='ALL',successfulReason='ALL';
  let detailId=null,detailSequence=0,registry=null,detailBusy=false;
  let selected=new Set(),job=null,jobTimer=null,collectionBusy=false,execution=null,executionTimer=null,historyPage=0;
  let jobSequence=0,jobReadBusy=false,jobError='';
  const dismissedJobKey='molebutter.coupang.products.dismissedCollection.ui1';
  let dismissedJob='';try{dismissedJob=sessionStorage.getItem(dismissedJobKey)||'';}catch{}
  const jobNoticeKey=()=>job?JSON.stringify([String(job.id),job.status,job.updatedAt||'']):'';
  const canDismissJob=()=>!!job&&['SUCCEEDED','PARTIAL','FAILED','CANCELLED','PAUSED','INTERRUPTED'].includes(job.status);
  function clearDismissedJob(){dismissedJob='';try{sessionStorage.removeItem(dismissedJobKey);}catch{}}
  const inputs=()=>Object.fromEntries(filterKeys.map(k=>[k,$('filter-'+k).value.trim()]).filter(([,v])=>v!==''));
  const error=err=>{$('coupang-error').textContent=err.message||'요청을 완료하지 못했습니다.';$('coupang-error').hidden=false;};
  const detailError=err=>{$('coupang-detail-error').textContent=err.message||'요청을 완료하지 못했습니다.';$('coupang-detail-error').hidden=false;};
  const time=v=>v?new Date(v).toLocaleString('ko-KR'):'미확인';
  const pretty=v=>v==null?'없음':typeof v==='string'?v:JSON.stringify(v);
  const statuses={QUEUED:'대기',RUNNING:'수집 중',SUCCEEDED:'완료',PARTIAL:'일부 실패',FAILED:'실패',CANCELLED:'취소',INTERRUPTED:'중단',PAUSED:'일시 중단',UNKNOWN:'결과 미확인',ACCEPTED:'반영 확인 중'};
  function selectionButton(){const active=job&&['QUEUED','RUNNING'].includes(job.status);$('coupang-collect-selected').disabled=!!active||collectionBusy||busy||!selected.size;}
  function buttons(){
    for(const el of $('coupang-query').querySelectorAll('input,select,button'))el.disabled=busy;
    for(const id of ['coupang-groups','coupang-recommendation-filters'])for(const el of $(id).querySelectorAll('button'))el.disabled=busy;
    $('coupang-first').disabled=busy||index===0;$('coupang-prev').disabled=busy||index===0;
    $('coupang-next').disabled=busy||!result||index+1>=result.totalPages;pageSelection?.sync();for(const id of ['coupang-bulk-price','coupang-bulk-stock'])$(id).disabled=busy||!(result?.items||[]).some(r=>selected.has(r.product.sellerProductId)&&!r.deleted&&r.option?.vendorItemId);
    for(const node of $('coupang-rows').querySelectorAll('[data-product-select]'))node.disabled=busy;selectionButton();
  }
  async function brands(){try{const list=await apiGet('/api/coupang/products/brands'),prior=$('filter-brand').value;
    $('filter-brand').innerHTML='<option value="">전체</option>'+list.map(b=>`<option value="${e(b)}">${e(b)}</option>`).join('');$('filter-brand').value=prior;
  }catch(err){error(err);}}
  function renderCards(){
    for(const button of $('coupang-groups').querySelectorAll('button'))button.setAttribute('aria-pressed',String(button.dataset.productGroup===(deleted?'DELETED':group)));
    $('coupang-recommendation-filters').hidden=deleted||group!=='RECOMMENDED';
    $('coupang-coupang-panel').dataset.recommended=String(!deleted&&group==='RECOMMENDED');
    for(const button of $('coupang-recommendation-filters').querySelectorAll('button'))button.setAttribute('aria-pressed',String(button.dataset.recommendationReason===reason));
    const counts=result?.counts,types=counts?.recommendationReasons;
    for(const [key,count]of Object.entries({ALL:counts?.recommended,PRICE_HIGH:types?.priceHigh,NEGATIVE_MARGIN:types?.negativeMargin,RESTOCK:types?.restock,MARK_SOLD_OUT:types?.markSoldOut,OWNED_STOCK_MISMATCH:types?.ownedStockMismatch}))$('coupang-reason-count-'+key).textContent=count?.toLocaleString('ko-KR')??'—';
    for(const key of ['all','recommended','selling','soldOut','stopped','deleted'])$('coupang-count-'+key).textContent=result?.counts?.[key]?.toLocaleString('ko-KR')??'—';
  }
  function photo(option,name){
    const images=[...(option?.images||[])].sort((a,b)=>(a.order??0)-(b.order??0));
    const candidate=images.find(i=>i.type==='REPRESENTATION'&&safeUrl(i.url))||images.find(i=>safeUrl(i.url));
    return candidate?`<button type="button" class="product-photo-button" aria-haspopup="dialog" data-coupang-image="${e(safeUrl(candidate.url))}" data-image-label="${e(name)}" aria-label="${e(name)} 이미지 확대"><span class="product-photo"><img src="${e(safeUrl(candidate.url))}" alt="${e(name)}" loading="lazy" referrerpolicy="no-referrer"></span></button>`:'<span class="product-photo"><span class="product-photo-empty">사진 없음</span></span>';
  }
  let pageSelection=null;
  const expansions=new Map();let expansionSequence=0;
  const skuOf=r=>r.option?.settings?.find(f=>f.name==='externalVendorSku')?.value?.trim()||'';
  const rowKey=r=>JSON.stringify([r.product.sellerProductId,r.option?.sellerProductItemId||r.option?.vendorItemId||null]);
  const expansionId=key=>'coupang-supplier-'+encodeURIComponent(key);
  const supplierSummary=window.ProductSupplierSummary.create({escape:e,stamp:AppUI.stamp,malls:MallTag.names,mallHtml:MallTag.html,
    safe:url=>safeUrl(url),label:v=>({AVAILABLE:'구매 가능',SOLD_OUT:'품절',UNAVAILABLE:'구매 불가',STOCK_UNKNOWN:'재고 미확인',UNCONFIRMED:'확인 필요',FAILED:'조회 실패',CODE_REVIEW:'이전 결과 · 재조회 필요'})[v]||v||'미확인'});
  let supplierFocus=null;
  function restoreSupplierFocus(){if(!supplierFocus)return;const f=supplierFocus;const candidates=$('coupang-rows').querySelectorAll(f.kind==='DETAIL'?'[data-detail]':'[data-quick-edit]');const button=[...candidates].find(b=>(b.dataset.detail||b.dataset.internalProduct)===f.internal&&(b.dataset.rowKey||b.closest('.coupang-supplier-product')?.dataset.supplierRowKey)===f.key&&(f.kind==='DETAIL'||b.dataset.quickEdit===f.kind));(button||$('coupang-rows').querySelector('[data-supplier-toggle]')||$('coupang-groups').querySelector('[aria-pressed=true]'))?.focus({preventScroll:true});}
  for(const id of ['product-dialog','coupang-quick-edit'])$(id)?.addEventListener('close',restoreSupplierFocus);
  const productModal=window.ProductDetailModal?.mount({embedded:true,onChanged:async id=>{await refreshSuppliers(id);await refreshListing();}});
  const quickEdit=window.CoupangQuickEdit?.create({onApplied:refreshListing});
  const bulkEdit=window.CoupangBulkEdit?.create({onApplied:async item=>{if(item?.product)selected.delete(item.product);rows();await refreshListing();}});
  function checkedRows(){const snapshot=window.TableSelection.snapshot(selected,result?.items||[],r=>r.product.sellerProductId,
    [...$('coupang-rows').querySelectorAll('[data-product-select]')].filter(node=>node.checked&&!node.disabled).map(node=>node.dataset.productSelect));rows();return snapshot;}
  async function refreshSuppliers(id){
    for(const [key,state]of expansions){if(!state.items.some(p=>p.id===String(id)))continue;const pages=Math.max(0,state.page);state.sequence=++expansionSequence;state.busy=false;state.items=[];for(let page=0;page<=pages&&expansions.get(key)===state;page++)await loadSuppliers(key,page);}
  }
  async function fetchListing(target,seq){
    const query=page=>'/api/coupang/products?'+new URLSearchParams({...submitted,page:String(page),size:$('coupang-size').value,sort:$('coupang-sort').value,deleted:String(deleted),group,...(group==='RECOMMENDED'?{reason}:{})});
    let data=await apiGet(query(target));
    if(!Array.isArray(data.items)||!Number.isInteger(data.totalPages))throw Error('저장 상품 응답을 확인할 수 없습니다.');
    if(seq===sequence&&target>Math.max(0,data.totalPages-1)){data=await apiGet(query(Math.max(0,data.totalPages-1)));if(!Array.isArray(data.items)||!Number.isInteger(data.totalPages))throw Error('저장 상품 응답을 확인할 수 없습니다.');}
    return data;
  }
  function showListing(data){if(group==='RECOMMENDED')successfulReason=reason;result=data;index=data.page;for(const key of expansions.keys())if(!data.items.some(r=>rowKey(r)===key))expansions.delete(key);rows();renderCards();$('coupang-page').textContent=`${index+1} / ${Math.max(1,data.totalPages)}페이지`;}
  async function refreshListing(){if(busy){refreshPending=true;return;}const seq=++sequence;try{const data=await fetchListing(index,seq);if(seq!==sequence)return;showListing(data);buttons();}catch(err){if(seq===sequence)error(err);}}
  function supplierProduct(p,key){
    const imageUrl=safeUrl(p.imageUrl),row=result?.items.find(r=>rowKey(r)===key),editable=row&&!row.deleted&&row.option?.vendorItemId,rec=row?.recommendation,missing=rec?.internalProductId===p.id&&rec.availabilityReason==='SELECTED_LISTING_MISSING',supplier=missing?{...p.selectedSupplier,selectedMissing:true,selectedSearchAt:rec.availabilityCheckedAt}:p.selectedSupplier,facts=supplierSummary.rowFacts(supplier,p.id);
    return `<tr class="coupang-supplier-product" data-supplier-row-key="${e(key)}"><td></td><td class="coupang-supplier-image">${imageUrl?`<button type="button" class="product-photo-button" aria-haspopup="dialog" data-coupang-image="${e(imageUrl)}" data-image-label="${e(p.productCode)}" aria-label="${e(p.productCode)} 매입처 이미지 확대"><span class="product-photo"><img src="${e(imageUrl)}" alt="${e(p.productCode)}" loading="lazy" referrerpolicy="no-referrer"></span></button>`:'<span class="product-photo"><span class="product-photo-empty">사진 없음</span></span>'}</td><td class="coupang-supplier-identity"><span class="product-meta">${e(p.brand||'브랜드 미지정')}</span><button type="button" class="product-name coupang-sku" data-detail="${e(p.id)}">${e(p.productCode)}</button></td><td colspan="4" class="coupang-supplier-source">${facts.source}</td><td></td><td class="coupang-supplier-price"><strong>${facts.price}</strong>${facts.delivery?`<small>${e(facts.delivery)}</small>`:''}</td><td class="coupang-supplier-stock"><div>${facts.stock}</div><div data-inventory-summary="${e(p.id)}" data-inventory-hand-only>보유 재고 조회 중…</div></td><td class="coupang-supplier-status"><span>${e(missing?'판매글 미발견':({SUCCESS:'조회 완료',PARTIAL:'일부 확인 필요',SOLD_OUT:'품절',FAILED:'조회 실패',NOT_CHECKED:'미조회',CHECKING:'조회 중',PENDING:'조회 대기'})[p.latestStatus]||p.latestStatus||'미확인')}</span>${facts.notices?`<small>${facts.notices}</small>`:''}</td><td colspan="2"><div class="coupang-supplier-actions">${['PRICE','STOCK'].map(kind=>`<button type="button" class="btn small" data-quick-edit="${kind}" data-row-key="${e(key)}" data-internal-product="${e(p.id)}" ${editable?'':'disabled'}>${kind==='PRICE'?'가격수정':'재고수정'}</button>`).join('')}</div></td></tr>`;
  }
  function supplierRow(key){const state=expansions.get(key);if(!state)return '';
    const columns=$('coupang-rows').closest('table').querySelector('colgroup').outerHTML;
    return `<tr class="coupang-supplier-row"><td colspan="13"><section id="${e(expansionId(key))}" class="coupang-supplier-panel" aria-label="선정 매입처" aria-busy="${state.busy}">${state.items.length?`<table class="data coupang-table coupang-supplier-table" aria-label="연결 상품과 선정 매입처">${columns}<tbody>${state.items.map(p=>supplierProduct(p,key)).join('')}</tbody></table>`:''}${state.loaded&&!state.items.length?'<p class="product-meta">일치하는 상품 없음</p>':''}${state.busy?'<p role="status">선정 매입처 조회 중…</p>':''}${state.error?`<p class="form-error" role="alert">${e(state.error)}</p><button type="button" class="btn small" data-supplier-retry="${e(key)}">다시 조회</button>`:''}${!state.error&&state.loaded&&state.page+1<state.totalPages?`<button type="button" class="btn small" data-supplier-more="${e(key)}" ${state.busy?'disabled':''}>더 보기</button>`:''}</section></td></tr>`;
  }
  async function loadSuppliers(key,page){const state=expansions.get(key),row=result?.items.find(r=>rowKey(r)===key);if(!state||state.busy||!row)return;
    const seq=++expansionSequence;state.sequence=seq;state.busy=true;state.error=null;rows();
    try{const data=await apiGet('/api/products/by-code?'+new URLSearchParams({code:skuOf(row),page:String(page),size:'20'}));
      if(expansions.get(key)!==state||state.sequence!==seq)return;
      if(!Array.isArray(data.items)||!Number.isInteger(data.totalPages))throw Error('상품코드 조회 응답을 확인해 주세요.');
      state.items=[...new Map([...state.items,...data.items].map(p=>[p.id,p])).values()];state.page=page;state.totalPages=data.totalPages;state.loaded=true;
    }catch(err){if(expansions.get(key)===state&&state.sequence===seq){state.error=err.message||'선정 매입처를 조회하지 못했습니다.';state.failedPage=page;}}
    finally{if(expansions.get(key)===state&&state.sequence===seq){state.busy=false;rows();}}
  }
  function toggleSupplier(key){if(expansions.has(key)){expansions.delete(key);rows();return;}expansions.set(key,{items:[],page:-1,totalPages:0,loaded:false,busy:false});loadSuppliers(key,0);}
  function cardPreset(choice){return choice==='SELLING'?{saleStatus:'ON_SALE',stock:'1',stockComparison:'GE'}:choice==='SOLD_OUT'?{saleStatus:'ON_SALE',stock:'0',stockComparison:'EQ'}:{saleStatus:choice==='STOPPED'?'STOPPED':choice==='RECOMMENDED'?'ON_SALE':'',stock:'',stockComparison:'EQ'};}
  function applyPreset(choice){const preset=cardPreset(choice);for(const [key,value]of Object.entries(preset))$('filter-'+key).value=value;autoStock={stock:preset.stock,comparison:preset.stockComparison};}
  function classify(){const f=inputs();if(f.saleStatus==='ON_SALE'&&f.stock==='1'&&f.stockComparison==='GE')return 'SELLING';if(f.saleStatus==='ON_SALE'&&f.stock==='0'&&f.stockComparison==='EQ')return 'SOLD_OUT';if(f.saleStatus==='STOPPED'&&!f.stock)return 'STOPPED';return 'ALL';}
  function search(){if(busy)return;submitted=inputs();submittedAutoStock=autoStock;group=deleted?'ALL':group==='RECOMMENDED'?'RECOMMENDED':classify();selected.clear();renderCards();load(0);}
  function rows(){
    window.TableSelection.reconcile(selected,result?.items||[],r=>r.product.sellerProductId);
    const focused=document.activeElement?.dataset;
    $('coupang-rows').innerHTML=(result?.items||[]).map(r=>{
      const p=r.product,o=r.option,c=o?.current,sku=skuOf(r),key=rowKey(r),rec=group==='RECOMMENDED'?r.recommendation:null;
      const priceReason=rec?.reasons.some(v=>['PRICE_HIGH','NEGATIVE_MARGIN'].includes(v)),stockReason=rec?.reasons.some(v=>['RESTOCK','MARK_SOLD_OUT','OWNED_STOCK_MISMATCH'].includes(v));
      const quick=kind=>rec&&o?.vendorItemId?`<button type="button" class="btn small" data-quick-edit="${kind}" data-row-key="${e(key)}" data-internal-product="${e(rec.internalProductId)}" aria-label="${e(sku||p.sellerProductName)} ${kind==='PRICE'?'가격수정':'재고수정'}">수정</button>`:'';
      const priceText=v=>v==null?'미확인':v.toLocaleString('ko-KR')+'원',stockText=v=>v==null?'미확인':v.toLocaleString('ko-KR')+'개';
      const compare=(before,after,kind,hint='')=>{
        const changed=after!=null&&after!==before,label=`${kind==='PRICE'?'가격':'재고'} 현재 ${before}${changed?' 변경 '+after:''}${hint?'. '+hint:''}`;
        return `<div class="coupang-recommendation-value" role="group" aria-label="${e(label)}" ${hint&&!changed?`title="${e(hint)}"`:''}><span class="coupang-recommendation-current">${e(before)}</span>${changed?`<span class="coupang-recommendation-arrow" aria-hidden="true">→</span><strong class="coupang-recommendation-target" ${hint?`title="${e(hint)}"`:''}>${e(after)}</strong>`:''}${quick(kind)}</div>`;
      };
      const hint=rec?`${rec.previousPrice?'이전 정상 확인 사입가 기준. ':''}${rec.priceCheckedAt?'사입가 확인 '+AppUI.stamp(rec.priceCheckedAt):'사입가 확인 시각 미기록'}`:'';
      const priceCell=priceReason?compare(priceText(c?.salePrice),rec.suggestedPrice==null?null:priceText(rec.suggestedPrice),'PRICE',hint):e(priceText(c?.salePrice));
      const stockCell=stockReason?compare(stockText(c?.amountInStock),rec.stockProposal?.selectionRequired?'옵션 선택':rec.stockProposal?.quantity!=null?stockText(rec.stockProposal.quantity):rec.stockOptionCount>1?'옵션 선택':rec.stocks.length===1?stockText(rec.stocks[0].quantity):null,'STOCK'):e(rec?stockText(c?.amountInStock):(c?.amountInStock==null?'미확인':c.amountInStock.toLocaleString('ko-KR')));
      const reasons=r.recommendation?.reasons.length?`<div class="coupang-recommendation-reasons">${r.recommendation.reasons.map(reason=>`<span>${e(({PRICE_HIGH:'가격 인하',NEGATIVE_MARGIN:'역마진',RESTOCK:'재고 보충',MARK_SOLD_OUT:'품절 전환',OWNED_STOCK_MISMATCH:'보유 재고 맞춤'})[reason]||reason)}${reason==='OWNED_STOCK_MISMATCH'&&r.recommendation.stockProposal?.selectionRequired?' · 옵션 확인 필요':''}</span>`).join('')}${r.recommendation.availabilityReason==='SELECTED_LISTING_MISSING'?`<small title="${e(r.recommendation.availabilityCheckedAt?AppUI.stamp(r.recommendation.availabilityCheckedAt):'확인 시각 미기록')}">선정 판매글 미발견</small>`:''}${r.recommendation.previousAvailability?`<small title="${e(r.recommendation.availabilityCheckedAt?AppUI.stamp(r.recommendation.availabilityCheckedAt):'확인 시각 미기록')}">이전 매입 상태</small>`:''}</div>`:'';
      const sale=c?.onSale==null?'미확인':c.onSale?'판매 중':'판매 중지';
      return `<tr class="coupang-option-row"><td><input type="checkbox" data-product-select="${e(p.sellerProductId)}" ${selected.has(p.sellerProductId)?'checked':''} aria-label="${e(p.sellerProductName)} 선택"></td><td>${photo(o,p.sellerProductName)}</td><td>${sku?`<button type="button" class="product-name coupang-sku" data-supplier-toggle="${e(key)}" aria-expanded="${expansions.has(key)}" aria-controls="${e(expansionId(key))}">${e(sku)}</button>`:'—'}</td><td>${e(p.productId||'—')}</td><td>${AppUI.coupangOptionLink(p.productId,o?.vendorItemId,'—')}</td><td class="wrap"><button type="button" class="coupang-product-link product-name" data-coupang-product="${e(p.sellerProductId)}">${e(p.sellerProductName)}</button>${r.deletionCandidate?'<strong>삭제 확인 필요</strong>':''}${r.error?`<small>조회 오류: ${e(r.error)} · 마지막 확인값 표시</small>`:''}</td><td class="wrap">${(o?.attributes||[]).filter(a=>a.exposed==='EXPOSED').map(a=>`${e(a.name)}: ${e(a.value)}`).join('<br>')||'—'}</td><td class="coupang-reasons-cell">${reasons||'—'}</td><td>${priceCell}${priceReason&&rec.suggestedLoss?'<small class="form-error">계산 설정 검토 필요</small>':''}</td><td>${stockCell}</td><td>${sale}${o?.currentError?`<small>조회 오류: ${e(o.currentError)}</small>`:''}</td><td>${r.outboundShippingDays==null?'미확인':e(r.outboundShippingDays+'일')}</td><td>${r.deleted?'—':`<a class="btn small" data-coupang-editor-link href="/coupang/products/${encodeURIComponent(p.sellerProductId)}/edit" aria-label="${e(p.sellerProductName)} 수정">수정</a>`}</td></tr>${supplierRow(key)}`;
    }).join('')||'<tr><td colspan="13" class="empty">저장된 상품이 없습니다.</td></tr>';
    $('coupang-rows').querySelectorAll('.coupang-option-row').forEach(row=>[...row.cells].forEach((cell,index)=>cell.dataset.label=['선택','이미지','판매자상품코드','노출상품 ID','옵션 ID','등록상품명','구매속성','추천 사유','현재가격','재고','판매상태','출고소요일','수정'][index]));
    if(focused)for(const attribute of ['supplierToggle','supplierMore','supplierRetry']){if(!focused[attribute])continue;const controls=$('coupang-rows').querySelectorAll('[data-supplier-toggle],[data-supplier-more],[data-supplier-retry]');[...controls].find(b=>b.dataset[attribute]===focused[attribute])?.focus({preventScroll:true});}
    pageSelection?.sync();
    for(const id of ['coupang-bulk-price','coupang-bulk-stock'])$(id).disabled=busy||!(result?.items||[]).some(r=>selected.has(r.product.sellerProductId)&&!r.deleted&&r.option?.vendorItemId);
    $('coupang-summary').textContent=`조회 ${(result?.totalElements||0).toLocaleString('ko-KR')}개 · 선택 ${selected.size}개 상품`;
    selectionButton();
  }
  async function load(target=0){if(busy)return;selected.clear();expansions.clear();rows();const seq=++sequence;busy=true;buttons();$('coupang-error').hidden=true;
    try{const data=await fetchListing(target,seq);if(seq!==sequence)return;showListing(data);
    }catch(err){if(seq===sequence){reason=successfulReason;renderCards();error(err);}}finally{if(seq===sequence){busy=false;buttons();if(refreshPending){refreshPending=false;refreshListing();}}}
  }
  const value = v => e(v ?? '—');
  const labels = {
    deliveryMethod: '배송 방식',
    deliveryCompanyCode: '택배사 코드',
    deliveryChargeType: '배송비 유형',
    deliveryCharge: '배송비',
    freeShipOverAmount: '무료배송 기준',
    deliveryChargeOnReturn: '반품 배송비',
    remoteAreaDeliverable: '도서산간 배송',
    unionDeliveryType: '묶음 배송',
    outboundShippingPlaceCode: '출고지 코드',
    returnCenterCode: '반품지 코드',
    returnChargeName: '반품지명',
    companyContactNumber: '반품 연락처',
    returnZipCode: '반품 우편번호',
    returnAddress: '반품 주소',
    returnAddressDetail: '상세 주소',
    returnCharge: '반품 비용',
    saleStartedAt: '판매 시작일시',
    saleEndedAt: '판매 종료일시',
    manufacture: '제조사',
    requested: '승인 요청',
    'autoPricingInfoView.minSalePrice': '자동 가격 최소 판매가',
    'autoPricingInfoView.active': '자동 가격 사용',
    'bundleInfo.bundleType': '묶음 상품 유형',
    extraInfoMessage: '추가 설정',
    offerCondition: '상품 상태',
    offerDescription: '상태 설명',
    externalVendorSku: '판매자 상품코드',
    originalPrice: '등록 정상가',
    salePrice: '등록 판매가',
    maximumBuyCount: '등록 최대 구매 수량',
    maximumBuyForPerson: '인당 구매 제한',
    maximumBuyForPersonPeriod: '구매 제한 기간',
    outboundShippingTimeDay: '출고 소요일',
    unitCount: '판매 단위',
    adultOnly: '성인 상품',
    taxType: '과세 유형',
    parallelImported: '병행 수입',
    overseasPurchased: '해외 구매',
    pccNeeded: '개인통관부호',
    bestPriceGuaranteed3P: '최저가 보장',
    barcode: '바코드',
    emptyBarcode: '바코드 없음',
    emptyBarcodeReason: '바코드 미등록 사유',
    modelNo: '모델명',
    searchTags: '검색어'
  };
  const codes = {
    IN_REVIEW: '심사중',
    SAVED: '임시저장',
    APPROVING: '승인대기중',
    APPROVED: '승인완료',
    PARTIAL_APPROVED: '부분승인완료',
    DENIED: '승인반려',
    DELETED: '상품삭제',
    SEQUENCIAL: '일반배송',
    COLD_FRESH: '신선·냉동',
    MAKE_ORDER: '주문제작',
    AGENT_BUY: '구매대행',
    VENDOR_DIRECT: '판매자 직접 전달·설치',
    FREE: '무료',
    NOT_FREE: '유료',
    CHARGE_RECEIVED: '착불',
    CONDITIONAL_FREE: '조건부 무료',
    UNION_DELIVERY: '묶음 배송',
    NOT_UNION_DELIVERY: '개별 배송',
    TAX: '과세',
    EVERYONE: '전체 이용',
    ADULT_ONLY: '성인 전용',
    PARALLEL_IMPORTED: '병행 수입',
    NOT_PARALLEL_IMPORTED: '해당 없음',
    OVERSEAS_PURCHASED: '해외 구매',
    NOT_OVERSEAS_PURCHASED: '해당 없음',
    Y: '가능',
    N: '불가',
    true: '예',
    false: '아니요',
    EXPOSED: '구매 속성',
    NONE: '검색 속성',
    REPRESENTATION: '대표',
    DETAIL: '추가',
    USED_PRODUCT: '중고'
  };
  const fields = rows => `<dl class="coupang-detail-fields">${rows.map(([name, v]) => `<div><dt>${e(name)}</dt><dd>${value(v)}</dd></div>`).join('')}</dl>`;
  const settings = rows => fields((rows || []).map(f => [labels[f.name] || f.name, f.name === 'taxType' && f.value === 'FREE' ? '면세' : codes[f.value] || f.value]));
  const safeUrl = url => {
    try {
      const u = new URL(url);
      return u.protocol === 'https:' && !u.username && !u.password ? u.href : null;
    } catch {
      return null;
    }
  };
  const image = (url, alt) => safeUrl(url) ? `<img src="${e(safeUrl(url))}" alt="${e(alt)}" loading="lazy" referrerpolicy="no-referrer"><span class="coupang-image-fallback" hidden>이미지 없음</span>` : '<span class="coupang-image-fallback">이미지 없음</span>';
  // Older Coupang descriptions still contain HTTP CDN URLs. Upgrade only the known CDN;
  // vendor URLs must already be HTTPS, and the iframe CSP remains restrictive.
  const descriptionUrl = url => {
    let source = String(url ?? '').trim();
    if (/^(?:\/?image\/)?vendor_inventory\//.test(source)) source = 'https://img1a.coupangcdn.com/image/' + source.replace(/^\/?image\//, '');
    if (source.startsWith('//')) source = 'https:' + source;
    try {
      const u = new URL(source);
      if (u.protocol === 'http:' && u.hostname.endsWith('.coupangcdn.com')) u.protocol = 'https:';
      return safeUrl(u.href);
    } catch {
      return null;
    }
  };
  const descriptionHtml = html => (html || '').replace(/http:\/\/[a-z0-9.-]+\.coupangcdn\.com(?=[:/])/gi, url => url.replace(/^http:/i, 'https:'));
  function contents(rows) {
    return (rows || []).map(c => {
      if (c.type === 'HTML') {
        const csp = "default-src 'none'; img-src https:; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'";
        const doc = `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="${csp}"><meta name="referrer" content="no-referrer"><style>body{margin:16px;overflow-wrap:anywhere}img{max-width:100%;height:auto}</style></head><body>${descriptionHtml(c.content)}</body></html>`;
        return `<iframe class="coupang-html" sandbox="" referrerpolicy="no-referrer" title="상품 설명 미리보기" srcdoc="${e(doc)}"></iframe>`;
      }
      return c.detailType === 'IMAGE' ? `<div class="coupang-content-image">${image(descriptionUrl(c.content), '상품 설명 이미지')}</div>` : `<p class="coupang-content-text">${value(c.content)}</p>`;
    }).join('') || '<p class="empty">등록된 설명이 없습니다.</p>';
  }
  const tabs = [['basic', '기본 정보·옵션 판매'], ['images', '이미지·설명·고시'], ['delivery', '배송·반품'], ['settings', '기타 설정']];
  let detailData = null,
    activeTab = 'basic',
    selectedOption = 0;
  function renderDetail() {
    const d = detailData,
      p = d.product,
      item = d.items[selectedOption];
    const basic = fields([['등록상품명', p.sellerProductName], ['노출상품명', d.displayProductName], ['브랜드', p.brand], ['제품명', d.generalProductName], ['상품군', d.productGroup], ['노출 분류 코드', d.displayCategoryCode], ['분류 ID', d.categoryId], ['등록상품 ID', p.sellerProductId], ['노출상품 ID', p.productId], ['등록일시', p.createdAt?.replace('T', ' ')]]);
    const optionTable = `<div class="table-wrap"><table class="data coupang-options"><thead><tr><th>업체상품옵션 ID</th><th>옵션 ID</th><th>등록 옵션명</th><th>판매자 상품코드</th><th>구매 속성</th><th>현재 가격</th><th>재고</th><th>판매 상태</th><th>조회 상태</th></tr></thead><tbody>${d.items.map(i => `<tr><td>${value(i.sellerProductItemId)}</td><td>${AppUI.coupangOptionLink(p.productId,i.vendorItemId,'—')}</td><td class="wrap">${value(i.itemName)}</td><td>${value(i.settings?.find(f => f.name === 'externalVendorSku')?.value)}</td><td class="wrap">${(i.attributes || []).filter(a => a.exposed === 'EXPOSED').map(a => `${value(a.name)}: ${value(a.value)}`).join('<br>') || '—'}</td><td>${i.current ? value(i.current.salePrice.toLocaleString('ko-KR') + '원') : '미확인'}</td><td>${i.current ? value(i.current.amountInStock.toLocaleString('ko-KR') + '개') : '미확인'}</td><td>${i.current ? i.current.onSale ? '판매 중' : '판매 중지' : '미확인'}</td><td class="wrap">${value(i.currentError)}</td></tr>`).join('') || '<tr><td colspan="9" class="empty">등록 옵션이 없습니다.</td></tr>'}</tbody></table></div>`;
    const images = (item?.images || []).map(i => `<figure>${image(i.url, item.itemName || '상품 이미지')}<figcaption>${value(codes[i.type] || i.type)} · ${value(i.order)}</figcaption></figure>`).join('') || '<p class="empty">등록된 이미지가 없습니다.</p>';
    const notices = (item?.notices || []).map(n => [`${n.category || ''} · ${n.name || ''}`, n.content]);
    const section = {
      basic: `<h3>기본 정보</h3>${basic}<h3>옵션·판매</h3>${optionTable}`,
      images: `<h3>이미지</h3><div class="coupang-images">${images}</div><h3>상품 설명</h3>${contents(item?.contents)}<h3>상품고시</h3>${notices.length ? fields(notices) : '<p class="empty">등록된 고시가 없습니다.</p>'}`,
      delivery: settings(d.delivery),
      settings: settings(d.settings) + `<h3>검색 속성</h3>${fields((item?.attributes || []).filter(a => a.exposed === 'NONE').map(a => [a.name, a.value]))}<h3>옵션 등록 설정</h3>${settings(item?.settings)}<h3>인증</h3>${(item?.certifications || []).map(c => fields([['인증 유형', c.type], ['인증 번호', c.code]]) + `<div class="coupang-images">${(c.attachments || []).map(i => image(i.url, '인증 자료')).join('')}</div>`).join('') || '<p class="empty">등록된 인증이 없습니다.</p>'}`
    };
    $('coupang-detail-edit').hidden = registry?.state.deleted || registry?.blocked;
    $('coupang-detail-edit').href = '/coupang/products/' + encodeURIComponent(p.sellerProductId) + '/edit';
    $('coupang-detail-title').textContent = p.sellerProductName || '쿠팡 등록상품 상세';
    $('coupang-detail-meta').textContent = `${p.sellerProductId} · ${codes[p.statusName] || p.statusName || '미확인'}`;
    $('coupang-detail-content').innerHTML = `<div class="coupang-tabs" role="tablist">${tabs.map(([key, label]) => `<button type="button" role="tab" id="coupang-tab-${key}" aria-controls="coupang-tab-panel" aria-selected="${key === activeTab}" tabindex="${key === activeTab ? 0 : -1}" data-detail-tab="${key}">${e(label)}</button>`).join('')}</div>${['images', 'settings'].includes(activeTab) && d.items.length ? `<label class="field coupang-option-select"><span>옵션</span><select id="coupang-option">${d.items.map((i, n) => `<option value="${n}" ${n === selectedOption ? 'selected' : ''}>${value(i.itemName || i.sellerProductItemId)}</option>`).join('')}</select></label>` : ''}<section id="coupang-tab-panel" role="tabpanel" aria-labelledby="coupang-tab-${activeTab}">${section[activeTab]}</section>`;
    const selector = $('coupang-option');
    if (['images', 'settings'].includes(activeTab) && d.items.length) selector.addEventListener('change', () => {
      selectedOption = Number(selector.value);
      renderDetail();
      $('coupang-option').focus();
    });
  }

  async function detail(id){if(detailBusy)return;const seq=++detailSequence;detailId=id;detailBusy=true;registry=null;detailData=null;execution=null;clearTimeout(executionTimer);
    if(!$('coupang-detail').open)$('coupang-detail').showModal();
    $('coupang-detail-error').hidden=true;$('coupang-detail-loading').hidden=false;$('coupang-detail-edit').hidden=true;
    for(const key of ['content','registry-tools','registry-preview','registry-execution','registry-history']){const el=$(key==='content'?'coupang-detail-content':'coupang-'+key);if(el)el.innerHTML='';}
    try{const data=await apiGet('/api/coupang/products/'+encodeURIComponent(id));if(seq!==detailSequence)return;
      if(!data.detail||data.detail.product.sellerProductId!==id)throw Error('저장된 상세를 확인할 수 없습니다.');
      registry=data;detailData=data.detail;activeTab='basic';selectedOption=0;historyPage=0;renderDetail();renderTools();await history(0);
      if(data.workingCopyId){const list=await apiGet('/api/coupang/submissions?'+new URLSearchParams({workingCopyId:data.workingCopyId,page:'0',size:'10'}));if(seq!==detailSequence)return;if(list.items?.length){execution=list.items[0];renderExecution();pollExecution();}}
    }catch(err){if(seq===detailSequence)detailError(err);}finally{if(seq===detailSequence){detailBusy=false;$('coupang-detail-loading').hidden=true;}}
  }
  function explicit(formId,buttonId,handler){const form=$(formId),button=$(buttonId);FormActions.bindExplicitSubmit(form,button,async()=>{try{await handler();}catch(err){form.closest?.('dialog')?detailError(err):error(err);}});}
  function renderTools(){if(!registry)return;const s=registry.state,conflicts=registry.conflicts||[];
    const stateLabels={PENDING:'미반영 수정',MATCHED:'쿠팡값과 일치',KEEP_LOCAL:'내 수정값 반영 선택',CONFLICT:'값 충돌',UNVERIFIED:'조회 재확인 필요',BASELINE_MISSING:'기준값 없음 · 사용할 값 재확인 필요'};
    $('coupang-registry-tools').innerHTML=`<h3>저장·반영 상태</h3><p>확인 ${e(time(s.lastSuccessAt))} · 최근 시도 ${e(time(s.lastAttemptAt))}${s.error?' · 조회 오류 '+e(s.error):''}${registry.blocked?' · 진행 중인 전송 결과를 먼저 확인하세요.':''}</p>
      <form id="registry-refresh-form"><button id="registry-refresh" type="button" class="btn">이 상품 갱신</button></form>
      ${s.deletionCandidate?'<p>정상 전체 수집과 개별 부재 조회에서 확인된 삭제 후보입니다. 쿠팡 Wing에서 삭제 여부를 확인한 뒤 선택하세요.</p><form id="registry-deletion-form"><button id="registry-delete-confirm" class="btn" type="button">삭제 목록으로 이동</button><button id="registry-delete-reject" class="btn" type="button">삭제 아님</button></form>':''}
      ${conflicts.length?'<h3>저장한 수정값</h3><div class="table-wrap"><table class="data"><thead><tr><th>항목</th><th>수정 당시</th><th>쿠팡 확인값</th><th>내 수정값</th><th>처리</th></tr></thead><tbody>'+conflicts.map((c,i)=>`<tr><td>${e(c.change.path)}<small>${e(stateLabels[c.state]||c.state)}</small></td><td>${e(pretty(c.baseline))}</td><td>${e(pretty(c.current))}</td><td>${e(pretty(c.desired))}</td><td><form id="registry-conflict-${i}"><button id="registry-coupang-${i}" type="button" class="btn small" ${registry.blocked||c.state==='UNVERIFIED'?'disabled':''}>쿠팡값 사용</button><button id="registry-local-${i}" type="button" class="btn small" ${registry.blocked||c.state==='UNVERIFIED'?'disabled':''}>내 수정값 반영</button></form></td></tr>`).join('')+'</tbody></table></div>':''}
      ${!s.deleted?`<h3>판매상태·삭제</h3><form id="registry-action-form" class="toolbar"><label class="field"><span>판매상태 변경 대상</span><select id="registry-option"><option value="">상품 전체 옵션</option>${detailData.items.filter(o=>o.vendorItemId).map(o=>`<option value="${e(o.vendorItemId)}">${e(o.itemName)} · ${e(o.vendorItemId)}</option>`).join('')}</select></label><button id="registry-stop" class="btn" type="button" ${registry.blocked?'disabled':''}>판매중지 미리보기</button><button id="registry-resume" class="btn" type="button" ${registry.blocked?'disabled':''}>판매재개 미리보기</button><button id="registry-delete" class="btn danger" type="button" ${registry.blocked?'disabled':''}>상품 삭제 미리보기</button></form>`:'<p>삭제 목록의 기록입니다. 데이터와 변경 이력은 보존됩니다.</p>'}`;
    explicit('registry-refresh-form','registry-refresh',()=>collect([detailId]));
    if(s.deletionCandidate)for(const [id,value]of [['registry-delete-confirm',true],['registry-delete-reject',false]])explicit('registry-deletion-form',id,async()=>{await apiPost('/api/coupang/products/'+detailId+'/deletion-confirmation',{observedVersion:s.version,deleted:value});await detailAgain();await load(index);});
    conflicts.forEach((c,i)=>{for(const [id,choice]of [['registry-coupang-', 'COUPANG'],['registry-local-','LOCAL']])explicit('registry-conflict-'+i,id+i,async()=>{await apiPost('/api/coupang/products/'+detailId+'/conflicts/resolve',{observedVersion:s.version,revision:registry.revision,key:c.key,choice});await detailAgain();});});
    if(!s.deleted)for(const [id,type]of [['registry-stop','SALE_STOP'],['registry-resume','SALE_RESUME'],['registry-delete','DELETE']])explicit('registry-action-form',id,()=>prepareAction(type));
  }
  async function detailAgain(){const id=detailId;detailBusy=false;await detail(id);}
  async function prepareAction(type){const preview=await apiPost('/api/coupang/products/'+detailId+'/actions/prepare',{type,vendorItemId:type==='DELETE'?null:$('registry-option').value||null,observedVersion:registry.state.version});
    $('coupang-registry-preview').innerHTML=`<h3>${type==='DELETE'?'상품 삭제 · 필요한 판매중지 후 삭제':'판매상태 변경'} 확인</h3><p>${type==='DELETE'?'쿠팡의 상품을 삭제합니다. DB 기록과 이력은 보존합니다.':'아래 옵션의 판매상태를 변경합니다.'}</p><ul>${preview.target.steps.map(s=>`<li>${e(s.label)} ${e(preview.target.optionNames?.[s.optionId]||'')}</li>`).join('')}</ul><ul>${preview.target.changes.map(c=>`<li>${e(c.path)}: ${e(c.before)} → ${e(c.after)}</li>`).join('')}</ul>${preview.target.issues.map(i=>`<p class="form-error">${e(i.message)}</p>`).join('')}<form id="registry-execute-form"><button id="registry-execute" class="btn primary" type="button" ${preview.executable?'':'disabled'}>확인한 내용을 쿠팡에 전송</button></form>`;
    explicit('registry-execute-form','registry-execute',async()=>{execution=await apiPost('/api/coupang/submissions/'+preview.id+'/execute',{idempotencyKey:AppUI.uuid()});$('registry-execute').disabled=true;renderExecution();pollExecution();});
  }
  function renderExecution(){if(!execution)return;const x=execution;
    $('coupang-registry-execution').innerHTML=`<h3>전송 결과: ${e(statuses[x.status]||x.status)}</h3><p>실행 ${e(x.id)} · ${e(time(x.updatedAt))}</p><ul>${(x.target?.steps||[]).map(s=>`<li>${e(s.label)} · ${e(statuses[s.status]||s.status)} · ${e(s.message||s.code||'')}</li>`).join('')}</ul><form id="registry-execution-form"><button id="registry-reconcile" type="button" class="btn">결과 확인</button>${['FAILED','PARTIAL'].includes(x.status)?'<button id="registry-retry" type="button" class="btn">실패 단계 재시도</button>':''}</form>`;
    explicit('registry-execution-form','registry-reconcile',async()=>{execution=await apiPost('/api/coupang/submissions/'+x.id+'/reconcile',{});renderExecution();pollExecution();});
    if(['FAILED','PARTIAL'].includes(x.status))explicit('registry-execution-form','registry-retry',async()=>{execution=await apiPost('/api/coupang/submissions/'+x.id+'/retry',{});renderExecution();pollExecution();});
  }
  function pollExecution(){clearTimeout(executionTimer);if(!execution||!['QUEUED','RUNNING'].includes(execution.status))return;const id=execution.id,seq=detailSequence;
    executionTimer=setTimeout(async()=>{try{const x=await apiGet('/api/coupang/submissions/'+id);if(seq!==detailSequence)return;execution=x;renderExecution();pollExecution();if(!['QUEUED','RUNNING'].includes(x.status)){await load(index);await detailAgain();}}catch(err){detailError(err);}},1500);
  }
  async function history(page){const id=detailId,seq=detailSequence;const data=await apiGet('/api/coupang/products/'+id+'/history?'+new URLSearchParams({page:String(page),size:'20'}));if(seq!==detailSequence)return;historyPage=page;
    $('coupang-registry-history').innerHTML=`<h3>변경 이력 (${data.totalElements})</h3>${data.items.map(r=>`<details><summary>${e(time(r.createdAt))} · ${e(r.kind)} · ${e(r.path)}</summary><p>사용자 ${e(r.actor??'시스템')} · 옵션/작업본 ${e(r.subject||'—')} · 수집 ${e(r.jobId||'—')} · 실행 ${e(r.executionId||'—')}</p><pre>이전: ${e(r.beforeJson??'없음')}
변경: ${e(r.afterJson??'없음')}</pre></details>`).join('')||'<p>기록이 없습니다.</p>'}<div class="toolbar"><button id="registry-history-prev" class="btn small" type="button" ${page===0?'disabled':''}>이전 이력</button><span>${page+1} / ${Math.max(1,data.totalPages)}</span><button id="registry-history-next" class="btn small" type="button" ${page+1>=data.totalPages?'disabled':''}>다음 이력</button></div>`;
    $('registry-history-prev').addEventListener('click',()=>history(historyPage-1).catch(detailError));$('registry-history-next').addEventListener('click',()=>history(historyPage+1).catch(detailError));
  }
  function renderJob(){const active=job&&['QUEUED','RUNNING'].includes(job.status);
    if(active&&dismissedJob)clearDismissedJob();
    $('coupang-collect-cancel').hidden=!active;$('coupang-collect-resume').hidden=!job||!['PARTIAL','FAILED','CANCELLED','PAUSED','INTERRUPTED'].includes(job.status);
    $('coupang-collect-recent').disabled=!!active||collectionBusy;
    $('coupang-collect-recent').textContent=active&&job.mode==='RECENT'?'최근 상품 가져오는 중…':'최근 등록 가져오기';
    $('coupang-collect-all').disabled=!!active||collectionBusy;selectionButton();
    $('coupang-collect-cancel').disabled=collectionBusy;$('coupang-collect-resume').disabled=collectionBusy;
    $('coupang-collect-all').textContent=active&&job.mode==='FULL'?'가져오는 중…':'전체 가져오기';
    $('coupang-collect-selected').textContent=active&&job.mode==='SELECTED'?'갱신 중…':'선택 상품 갱신';
    for(const id of ['coupang-collect-all','coupang-collect-selected','coupang-collect-recent'])$(id).setAttribute('aria-busy',String(collectionBusy||!!active));
    const panel=$('coupang-collection-state');panel.hidden=(!job&&!jobError&&!collectionBusy)||(canDismissJob()&&dismissedJob===jobNoticeKey()&&!jobError&&!collectionBusy);
    $('coupang-collection-close').hidden=!canDismissJob();$('coupang-collection-close').disabled=collectionBusy||jobReadBusy;
    $('coupang-collection-title').textContent=job?.mode==='RECENT'?'최근 등록 가져오기':job?.mode==='SELECTED'?'선택 상품 갱신':'전체 상품 가져오기';
    $('coupang-collection-status').textContent=job?statuses[job.status]||job.status:collectionBusy?'요청 중':'현황 확인 필요';
    panel.dataset.status=job?.status||'';
    const phases={LIST:'상품 목록 확인 중',DELETED:'삭제 상품 목록 확인 중',DETAIL:'상세·가격·재고 확인 중',MISSING:'목록에서 누락된 상품 확인 중',DONE:'수집 종료'};
    $('coupang-collection-phase').textContent=job?phases[job.phase]||'수집 준비 중':'수집 현황을 확인하고 있습니다.';
    const count=value=>Number.isFinite(Number(value))?Math.max(0,Number(value)):0;
    const total=count(job?.discovered),done=count(job?.completed),failed=count(job?.failed),processed=done+failed;
    const recent=job?.recent;
    const listPending=job?.mode==='RECENT'?!recent?.selectionComplete:job?.mode==='FULL'&&!job.listComplete;
    const progress=$('coupang-collection-progress');
    if(!job||listPending||!total)progress.removeAttribute('value');else progress.value=Math.min(100,Math.floor(processed/total*100));
    progress.hidden=!active;
    $('coupang-collection-summary').textContent=job?`발견 ${total.toLocaleString('ko-KR')}개 · 처리 ${processed.toLocaleString('ko-KR')}개 · 성공 ${done.toLocaleString('ko-KR')}개 · 실패 ${failed.toLocaleString('ko-KR')}개` : '';
    $('coupang-collection-note').textContent=!job?'':listPending?'목록 확인 중에는 전체 건수가 늘어날 수 있습니다.':active?`남은 상품 ${Math.max(0,total-processed).toLocaleString('ko-KR')}개 · 상품 단위 집계이며 누락 상품 확인 시 대상이 추가될 수 있습니다.`:'상품 단위 집계입니다. 실패·중단한 작업은 재개·재시도할 수 있습니다.';
    if(recent) {
      $('coupang-collection-summary').textContent=`${recent.createdDateFrom} ~ ${recent.createdDateTo} · 최대 ${recent.maxProducts}개 · 조회 날짜 ${recent.currentDate} · API ${recent.apiPages}페이지 · 발견 후보 ${recent.candidates}개 · 확정 대상 ${recent.selected}개 · 성공 ${done}개 · 실패 ${failed}개`;
      $('coupang-collection-note').textContent=listPending?'날짜별 마지막 페이지까지 확인하고 대상을 선정합니다. 목록 확인 건수와 가져오는 건수는 다를 수 있습니다.':'확정한 대상만 수집합니다. 재개·재시도 시 대상을 다시 선정하지 않습니다.';
    }
    $('coupang-collection-updated').textContent=job?.updatedAt?'마지막 확인 '+time(job.updatedAt):'';
    $('coupang-collection-error').hidden=!jobError&&!job?.error;
    $('coupang-collection-error-text').textContent=jobError||(job?.error?'수집 오류: '+job.error:'');
    $('coupang-collection-retry').hidden=!jobError;$('coupang-collection-retry').disabled=jobReadBusy||collectionBusy;
  }
  async function jobRequest(url,body){if(collectionBusy)return;clearDismissedJob();const seq=++jobSequence;clearTimeout(jobTimer);jobReadBusy=false;collectionBusy=true;jobError='';renderJob();try{const next=await apiPost(url,body);if(seq===jobSequence)job=next;}finally{if(seq===jobSequence){collectionBusy=false;renderJob();pollJob();}}}
  async function collect(ids){return jobRequest('/api/coupang/products/collections',{sellerProductIds:ids});}
  async function readJob(){if(jobReadBusy||collectionBusy)return;const seq=++jobSequence,id=job?.id;clearTimeout(jobTimer);jobReadBusy=true;renderJob();
    try{const next=await apiGet('/api/coupang/products/collections/'+(id||'latest'));if(seq!==jobSequence)return;job=next;jobError='';renderJob();
      if(id&&job&&!['QUEUED','RUNNING'].includes(job.status)){await brands();await refreshListing();if(detailId)await detailAgain();}
    }catch(err){if(seq===jobSequence)jobError='현황을 불러오지 못했습니다. 마지막 확인 결과를 유지합니다. '+(err.message||'다시 조회해주세요.');}
    finally{if(seq===jobSequence){jobReadBusy=false;renderJob();pollJob();}}
  }
  function pollJob(){clearTimeout(jobTimer);if(!job||!['QUEUED','RUNNING'].includes(job.status))return;
    jobTimer=setTimeout(readJob,jobError?5000:1500);
  }
  $('coupang-collection-retry').addEventListener('click',readJob);
  $('coupang-collection-close').addEventListener('click',()=>{if(!canDismissJob()||collectionBusy||jobReadBusy)return;dismissedJob=jobNoticeKey();jobError='';try{sessionStorage.setItem(dismissedJobKey,dismissedJob);}catch{}renderJob();$('coupang-collect-all').focus({preventScroll:true});});
  $('coupang-query').addEventListener('submit',ev=>{ev.preventDefault();search();});
  FormActions.bindEnterSubmit($('coupang-query'),$('coupang-query').querySelector('button[type=submit]'));
  $('coupang-reset').addEventListener('click',()=>{if(busy)return;filterKeys.forEach(k=>$('filter-'+k).value=k.endsWith('Comparison')?'EQ':'');applyPreset(deleted?'DELETED':group);search();});
  for(const key of ['stock','stockComparison'])$('filter-'+key).addEventListener('input',()=>{autoStock=null;});
  $('filter-stockComparison').addEventListener('change',()=>{autoStock=null;});
  $('filter-saleStatus').addEventListener('change',()=>{if(autoStock&&$('filter-stock').value===autoStock.stock&&$('filter-stockComparison').value===autoStock.comparison){const value=$('filter-saleStatus').value;applyPreset(group==='RECOMMENDED'?'RECOMMENDED':value==='ON_SALE'?'SELLING':value==='STOPPED'?'STOPPED':'ALL');$('filter-saleStatus').value=value;}});
  $('coupang-sort').addEventListener('change',()=>{if(busy)return;selected.clear();load(0);});
  $('coupang-size').addEventListener('change',()=>load(0));$('coupang-first').addEventListener('click',()=>load(0));$('coupang-prev').addEventListener('click',()=>load(index-1));$('coupang-next').addEventListener('click',()=>load(index+1));
  $('coupang-recommendation-filters').addEventListener('click',ev=>{const button=ev.target.closest('[data-recommendation-reason]');if(busy||group!=='RECOMMENDED'||!button)return;const choice=button.dataset.recommendationReason;if(!['ALL','PRICE_HIGH','NEGATIVE_MARGIN','RESTOCK','MARK_SOLD_OUT','OWNED_STOCK_MISMATCH'].includes(choice)||choice===reason)return;reason=choice;search();});
  $('coupang-groups').addEventListener('click',ev=>{const card=ev.target.closest('[data-product-group]');if(busy||!card)return;const choice=card.dataset.productGroup;if(!['ALL','RECOMMENDED','SELLING','SOLD_OUT','STOPPED','DELETED'].includes(choice))return;deleted=choice==='DELETED';group=deleted?'ALL':choice;applyPreset(choice);search();});
  $('coupang-rows').addEventListener('click',ev=>{const internal=ev.target.closest('[data-detail]');if(internal){supplierFocus={kind:'DETAIL',internal:internal.dataset.detail,key:internal.closest('.coupang-supplier-product').dataset.supplierRowKey};productModal?.open(internal.dataset.detail).catch(error);return;}const quick=ev.target.closest('[data-quick-edit]');if(quick){supplierFocus={kind:quick.dataset.quickEdit,internal:quick.dataset.internalProduct,key:quick.dataset.rowKey};const row=result?.items.find(r=>rowKey(r)===quick.dataset.rowKey);if(row&&!quick.disabled)quickEdit?.open(row.product.sellerProductId,row.option.vendorItemId,quick.dataset.internalProduct,quick.dataset.quickEdit);return;}for(const [attribute,action]of [['supplierToggle',key=>toggleSupplier(key)],['supplierMore',key=>loadSuppliers(key,expansions.get(key).page+1)],['supplierRetry',key=>loadSuppliers(key,expansions.get(key).failedPage)]]){const button=ev.target.closest('[data-'+attribute.replace(/[A-Z]/g,c=>'-'+c.toLowerCase())+']');if(button){if(!button.disabled)action(button.dataset[attribute]);return;}}const picture=ev.target.closest('[data-coupang-image]');if(picture){const url=safeUrl(picture.dataset.coupangImage);if(!url)return;$('coupang-image-full').src=url;$('coupang-image-full').alt=picture.dataset.imageLabel;$('coupang-image-full').hidden=false;$('coupang-image-status').hidden=true;$('coupang-image-dialog').showModal();return;}const button=ev.target.closest('[data-coupang-product]');if(button)detail(button.dataset.coupangProduct);});
  $('coupang-rows').addEventListener('error',ev=>{if(ev.target.tagName==='IMG'){const button=ev.target.closest('[data-coupang-image]');if(button){button.outerHTML='<span class="product-photo"><span class="product-photo-empty">사진 없음</span></span>';}else if(ev.target.closest('.coupang-supplier-panel')){ev.target.outerHTML='<span class="product-photo-empty">사진 없음</span>';}}},true);
  $('coupang-image-full').addEventListener('error',()=>{$('coupang-image-full').hidden=true;$('coupang-image-status').hidden=false;});
  $('coupang-image-close').addEventListener('click',()=>$('coupang-image-dialog').close());
  $('coupang-image-dialog').addEventListener('close',()=>$('coupang-image-full').removeAttribute('src'));
  for(const [id,kind]of [['coupang-bulk-price','PRICE'],['coupang-bulk-stock','STOCK']])$(id).addEventListener('click',()=>{if(busy)return;const items=checkedRows().filter(r=>!r.deleted&&r.option?.vendorItemId);if(!items.length)return error(Error('변경할 옵션을 현재 목록에서 선택해 주세요.'));bulkEdit?.open(items,kind);});
  pageSelection=window.TableSelection.create({controls:[$('coupang-select-page'),$('coupang-select-page-mobile')],checkboxes:()=>[...$('coupang-rows').querySelectorAll('[data-product-select]')],isBusy:()=>busy,onToggle:(checked,nodes)=>{for(const node of nodes)checked?selected.add(node.dataset.productSelect):selected.delete(node.dataset.productSelect);rows();}});
  $('coupang-rows').addEventListener('change',ev=>{const id=ev.target.dataset.productSelect;if(!id||busy||ev.target.disabled||!result?.items.some(r=>r.product.sellerProductId===id))return;ev.target.checked?selected.add(id):selected.delete(id);rows();});
  function koreanToday(){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());}
  function recentPreset(){const days=$('coupang-recent-preset').value;if(days==='custom')return;const end=koreanToday(),start=new Date(end+'T00:00:00Z');start.setUTCDate(start.getUTCDate()-Number(days)+1);$('coupang-recent-from').value=start.toISOString().slice(0,10);$('coupang-recent-to').value=end;}
  $('coupang-collect-recent').addEventListener('click',()=>{if(collectionBusy||job&&['QUEUED','RUNNING'].includes(job.status))return;if(!$('coupang-recent-from').value){$('coupang-recent-preset').value='7';$('coupang-recent-count').value='100';recentPreset();}$('coupang-recent-dialog').showModal();});
  $('coupang-recent-close').addEventListener('click',()=>{if(!collectionBusy)$('coupang-recent-dialog').close();});
  $('coupang-recent-dialog').addEventListener('cancel',ev=>{if(collectionBusy)ev.preventDefault();});
  $('coupang-recent-preset').addEventListener('change',recentPreset);
  for(const id of ['coupang-recent-from','coupang-recent-to'])$(id).addEventListener('change',()=>{$('coupang-recent-preset').value='custom';});
  FormActions.bindExplicitSubmit($('coupang-recent-form'),$('coupang-recent-start'),async()=>{
    if(collectionBusy||job&&['QUEUED','RUNNING'].includes(job.status))return;
    const from=$('coupang-recent-from').value,to=$('coupang-recent-to').value,n=Number($('coupang-recent-count').value),days=(Date.parse(to)-Date.parse(from))/86400000+1;
    $('coupang-recent-error').textContent='';
    if(!/^\d{4}-\d{2}-\d{2}$/.test(from)||!/^\d{4}-\d{2}-\d{2}$/.test(to)||!Number.isFinite(days)||days<1||days>90||to>koreanToday()||!Number.isInteger(n)||n<1||n>500){$('coupang-recent-error').textContent='오늘까지 최대 90일의 등록 기간과 1~500개를 지정해 주세요.';return;}
    $('coupang-recent-start').disabled=true;$('coupang-recent-close').disabled=true;
    try{await jobRequest('/api/coupang/products/collections',{mode:'RECENT',sellerProductIds:[],createdDateFrom:from,createdDateTo:to,maxProducts:n});$('coupang-recent-dialog').close();}
    catch(err){$('coupang-recent-error').textContent=err.message||'가져오기 요청에 실패했습니다.';}
    finally{$('coupang-recent-start').disabled=false;$('coupang-recent-close').disabled=false;}
  });
  explicit('coupang-collection-actions','coupang-collect-all',()=>collect([]));
  explicit('coupang-collection-actions','coupang-collect-selected',()=>{if(busy)throw Error('목록 조회가 끝난 뒤 선택해 주세요.');const ids=[...new Set(checkedRows().map(r=>r.product.sellerProductId))];if(!ids.length)throw Error('갱신할 상품을 현재 목록에서 선택해 주세요.');return collect(ids);});
  explicit('coupang-collection-actions','coupang-collect-cancel',()=>jobRequest('/api/coupang/products/collections/'+job.id+'/cancel',{}));
  explicit('coupang-collection-actions','coupang-collect-resume',()=>jobRequest('/api/coupang/products/collections/'+job.id+'/resume',{}));
  $('coupang-detail-close').addEventListener('click',()=>$('coupang-detail').close());
  $('coupang-detail').addEventListener('close',()=>{detailSequence++;detailBusy=false;detailId=null;clearTimeout(executionTimer);});
  $('coupang-detail-retry').addEventListener('click',()=>detailId&&detailAgain());
  $('coupang-detail-content').addEventListener('click',ev=>{const tab=ev.target.closest('[data-detail-tab]');if(tab&&detailData){activeTab=tab.dataset.detailTab;renderDetail();$('coupang-tab-'+activeTab).focus();}});
  $('coupang-detail-content').addEventListener('keydown',ev=>{const tab=ev.target.closest('[data-detail-tab]');if(!tab||!['ArrowLeft','ArrowRight','Home','End'].includes(ev.key))return;ev.preventDefault();const i=tabs.findIndex(([key])=>key===activeTab);activeTab=tabs[ev.key==='Home'?0:ev.key==='End'?tabs.length-1:(i+(ev.key==='ArrowRight'?1:tabs.length-1))%tabs.length][0];renderDetail();$('coupang-tab-'+activeTab).focus();});
  $('coupang-detail-content').addEventListener('error',ev=>{if(ev.target.tagName==='IMG'){ev.target.hidden=true;if(ev.target.nextElementSibling)ev.target.nextElementSibling.hidden=false;}},true);
  const navigationKey='molebutter.coupang.products.navigation.ui3';
  function saveNavigation(){try{sessionStorage.setItem(navigationKey,JSON.stringify({submitted,index,deleted,group,reason,autoStock:submittedAutoStock,size:$('coupang-size').value,sort:$('coupang-sort').value,scroll:window.ModalScroll?.position().y??window.scrollY,tableScroll:$('coupang-rows').closest?.('.table-wrap')?.scrollLeft||0}));}catch{}}
  document.addEventListener('click',ev=>{if(ev.target.closest('[data-coupang-editor-link]'))saveNavigation();});
  async function start(){let saved;try{saved=JSON.parse(sessionStorage.getItem(navigationKey));sessionStorage.removeItem(navigationKey);}catch{}
    await brands();if(saved&&Number.isInteger(saved.index)&&saved.index>=0&&['20','50','100'].includes(saved.size)){
      submitted=Object.fromEntries(filterKeys.filter(k=>typeof saved.submitted?.[k]==='string').map(k=>[k,saved.submitted[k]]));filterKeys.forEach(k=>{$('filter-'+k).value=submitted[k]||(k.endsWith('Comparison')?'EQ':'');});reason=['ALL','PRICE_HIGH','NEGATIVE_MARGIN','RESTOCK','MARK_SOLD_OUT','OWNED_STOCK_MISMATCH'].includes(saved.reason)?saved.reason:'ALL';successfulReason=reason;deleted=!!saved.deleted;group=!deleted&&['ALL','RECOMMENDED','SELLING','SOLD_OUT','STOPPED'].includes(saved.group)?saved.group:'ALL';$('coupang-size').value=saved.size;$('coupang-sort').value=['CREATED_DESC','CREATED_ASC'].includes(saved.sort)?saved.sort:'CREATED_DESC';autoStock=saved.autoStock&&saved.autoStock.stock===$('filter-stock').value&&saved.autoStock.comparison===$('filter-stockComparison').value?saved.autoStock:null;submittedAutoStock=autoStock;
    }else{applyPreset('SELLING');submitted=inputs();submittedAutoStock=autoStock;}renderCards();await load(saved?.index||0);if(saved){window.scrollTo?.(0,Number(saved.scroll)||0);const wrap=$('coupang-rows').closest?.('.table-wrap');if(wrap)wrap.scrollLeft=Number(saved.tableScroll)||0;}
    await readJob();
  }
  start();window.addEventListener('pagehide',()=>{sequence++;detailSequence++;jobSequence++;expansions.clear();clearTimeout(jobTimer);clearTimeout(executionTimer);});
})();
