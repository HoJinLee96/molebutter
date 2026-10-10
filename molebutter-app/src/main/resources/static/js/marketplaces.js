(() => {
    'use strict';
    const {$,escape:e}=AppUI;
    // 헤더와 데이터가 같은 열 정의를 사용해 이전 템플릿이 남아 있어도 순서가 맞는다.
    const columns=[['sellerProductId','등록상품 ID'],['productId','노출상품 ID'],['brand','브랜드'],['sellerProductName','등록상품명'],['statusName','등록상태'],['createdAt','등록일시'],['edit','작업']];
    if(typeof document!=='undefined'){
        const header=document.querySelector('.marketplace-table thead tr');
        if(header)header.innerHTML=columns.map(([,label])=>`<th scope="col">${e(label)}</th>`).join('');
    }
    let detailSequence=0,detailId=null,detailBusy=false,detailRead=null,queryRead=null,importRead=null,importSequence=0;
    const startRead=(url,options)=>window.CoupangRead?window.CoupangRead.start(url,options):{promise:options?.method?apiPost(url,{}):apiGet(url),cancel(){}};
    const filterKeys=['sellerProductId','sellerProductName','status','createdAt'];
    const inputs=()=>Object.fromEntries(filterKeys.map(key=>[key,$('filter-'+key).value.trim()]).filter(([,v])=>v));
    let submitted={};
    let tokens=[''],index=0,result=null,busy=false,sequence=0;
    const controls=()=>[...$('marketplace-query').querySelectorAll('input,select,button'),$('marketplace-first'),$('marketplace-prev'),$('marketplace-next')];
    function buttons(){
        controls().forEach(el=>el.disabled=busy);
        $('marketplace-first').disabled=busy||!result||index===0;
        $('marketplace-prev').disabled=busy||!result||index===0;
        $('marketplace-next').disabled=busy||!result?.hasNext;
    }
    async function load(target,reset=false){
        if(busy)return;
        const seq=++sequence,history=reset?['']:tokens.slice(),size=$('marketplace-size').value;
        if(reset){tokens=[''];index=0;result=null;}
        busy=true;buttons();$('marketplace-error').hidden=true;$('marketplace-query').setAttribute('aria-busy','true');
        try {
            const query=new URLSearchParams({maxPerPage:size});Object.entries(submitted).forEach(([key,v])=>Array.isArray(v)?v.forEach(item=>query.append(key,item)):query.set(key,v));if(history[target])query.set('nextToken',history[target]);
            queryRead=startRead('/api/marketplaces/coupang/products?'+query);
            const data=await queryRead.promise;
            if(seq!==sequence)return;
            if(!Array.isArray(data.items)||typeof data.nextToken!=='string'||data.hasNext!==Boolean(data.nextToken))throw Error('쿠팡 조회 응답을 확인할 수 없습니다.');
            tokens=history;index=target;result=data;tokens.length=target+1;if(data.hasNext)tokens.push(data.nextToken);
            $('marketplace-rows').innerHTML=data.items.map(p=>`<tr>${columns.map(([key])=>`<td>${key==='edit'?`<a class="btn small primary" href="/marketplaces/coupang/products/${encodeURIComponent(p.sellerProductId)}\/edit" data-marketplace-editor-link>수정</a> <button type="button" class="btn small" data-import-coupang="${e(p.sellerProductId)}">가져오기</button>`:key==='sellerProductName'?`<button type="button" class="marketplace-product-link" data-marketplace-product="${e(p.sellerProductId)}">${e(p[key]??'—')}</button>`:e((key==='createdAt'?p[key]?.replace('T',' '):key==='statusName'?(codes[p[key]]||p[key]):p[key])??'—')}</td>`).join('')}</tr>`).join('')||'<tr><td colspan="7" class="empty">등록상품이 없습니다.</td></tr>';
            $('marketplace-summary').textContent=`이번 페이지 ${data.items.length}개 조회`;
            $('marketplace-page').textContent=`${index+1}페이지`;
        }catch(err){if(seq===sequence){$('marketplace-error').textContent=err.message;$('marketplace-error').hidden=false;}}
        finally{if(seq===sequence){queryRead=null;busy=false;$('marketplace-query').setAttribute('aria-busy','false');buttons();}}
    }
    const value=v=>e(v??'—');
    const labels={deliveryMethod:'배송 방식',deliveryCompanyCode:'택배사 코드',deliveryChargeType:'배송비 유형',deliveryCharge:'배송비',freeShipOverAmount:'무료배송 기준',deliveryChargeOnReturn:'반품 배송비',remoteAreaDeliverable:'도서산간 배송',unionDeliveryType:'묶음 배송',outboundShippingPlaceCode:'출고지 코드',returnCenterCode:'반품지 코드',returnChargeName:'반품지명',companyContactNumber:'반품 연락처',returnZipCode:'반품 우편번호',returnAddress:'반품 주소',returnAddressDetail:'상세 주소',returnCharge:'반품 비용',saleStartedAt:'판매 시작일시',saleEndedAt:'판매 종료일시',manufacture:'제조사',requested:'승인 요청','autoPricingInfoView.minSalePrice':'자동 가격 최소 판매가','autoPricingInfoView.active':'자동 가격 사용','bundleInfo.bundleType':'묶음 상품 유형',extraInfoMessage:'추가 설정',offerCondition:'상품 상태',offerDescription:'상태 설명',externalVendorSku:'판매자 상품코드',originalPrice:'등록 정상가',salePrice:'등록 판매가',maximumBuyCount:'등록 최대 구매 수량',maximumBuyForPerson:'인당 구매 제한',maximumBuyForPersonPeriod:'구매 제한 기간',outboundShippingTimeDay:'출고 소요일',unitCount:'판매 단위',adultOnly:'성인 상품',taxType:'과세 유형',parallelImported:'병행 수입',overseasPurchased:'해외 구매',pccNeeded:'개인통관부호',bestPriceGuaranteed3P:'최저가 보장',barcode:'바코드',emptyBarcode:'바코드 없음',emptyBarcodeReason:'바코드 미등록 사유',modelNo:'모델명',searchTags:'검색어'};
    const codes={IN_REVIEW:'심사중',SAVED:'임시저장',APPROVING:'승인대기중',APPROVED:'승인완료',PARTIAL_APPROVED:'부분승인완료',DENIED:'승인반려',DELETED:'상품삭제',SEQUENTIAL:'일반배송',COLD_FRESH:'신선·냉동',MAKE_ORDER:'주문제작',AGENT_BUY:'구매대행',VENDOR_DIRECT:'판매자 직접 전달·설치',FREE:'무료',NOT_FREE:'유료',CHARGE_RECEIVED:'착불',CONDITIONAL_FREE:'조건부 무료',UNION_DELIVERY:'묶음 배송',NOT_UNION_DELIVERY:'개별 배송',TAX:'과세',EVERYONE:'전체 이용',ADULT_ONLY:'성인 전용',PARALLEL_IMPORTED:'병행 수입',NOT_PARALLEL_IMPORTED:'해당 없음',OVERSEAS_PURCHASED:'해외 구매',NOT_OVERSEAS_PURCHASED:'해당 없음',Y:'가능',N:'불가',true:'예',false:'아니요',EXPOSED:'구매 속성',NONE:'검색 속성',REPRESENTATION:'대표',DETAIL:'추가',USED_PRODUCT:'중고'};
    const fields=rows=>`<dl class="marketplace-detail-fields">${rows.map(([name,v])=>`<div><dt>${e(name)}</dt><dd>${value(v)}</dd></div>`).join('')}</dl>`;
    const settings=rows=>fields((rows||[]).map(f=>[labels[f.name]||f.name,f.name==='taxType'&&f.value==='FREE'?'면세':codes[f.value]||f.value]));
    const safeUrl=url=>{try{const u=new URL(url);return u.protocol==='https:'&&!u.username&&!u.password?u.href:null;}catch{return null;}};
    const image=(url,alt)=>safeUrl(url)?`<img src="${e(safeUrl(url))}" alt="${e(alt)}" loading="lazy" referrerpolicy="no-referrer"><span class="marketplace-image-fallback" hidden>이미지 없음</span>`:'<span class="marketplace-image-fallback">이미지 없음</span>';
    // Older Coupang descriptions still contain HTTP CDN URLs. Upgrade only the known CDN;
    // vendor URLs must already be HTTPS, and the iframe CSP remains restrictive.
    const descriptionUrl=url=>{
        let source=String(url??'').trim();
        if(/^(?:\/?image\/)?vendor_inventory\//.test(source))source='https://img1a.coupangcdn.com/image/'+source.replace(/^\/?image\//,'');
        if(source.startsWith('//'))source='https:'+source;
        try{const u=new URL(source);if(u.protocol==='http:'&&u.hostname.endsWith('.coupangcdn.com'))u.protocol='https:';return safeUrl(u.href);}catch{return null;}
    };
    const descriptionHtml=html=>(html||'').replace(/http:\/\/[a-z0-9.-]+\.coupangcdn\.com(?=[:/])/gi,url=>url.replace(/^http:/i,'https:'));
    function contents(rows){return (rows||[]).map(c=>{
        if(c.type==='HTML'){
            const csp="default-src 'none'; img-src https:; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'";
            const doc=`<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="${csp}"><meta name="referrer" content="no-referrer"><style>body{margin:16px;overflow-wrap:anywhere}img{max-width:100%;height:auto}</style></head><body>${descriptionHtml(c.content)}</body></html>`;
            return `<iframe class="marketplace-html" sandbox="" referrerpolicy="no-referrer" title="상품 설명 미리보기" srcdoc="${e(doc)}"></iframe>`;
        }
        return c.detailType==='IMAGE'?`<div class="marketplace-content-image">${image(descriptionUrl(c.content),'상품 설명 이미지')}</div>`:`<p class="marketplace-content-text">${value(c.content)}</p>`;
    }).join('')||'<p class="empty">등록된 설명이 없습니다.</p>';}
    const tabs=[['basic','기본 정보·옵션 판매'],['images','이미지·설명·고시'],['delivery','배송·반품'],['settings','기타 설정']];
    let detailData=null,activeTab='basic',selectedOption=0;
    function renderDetail(){
        const d=detailData,p=d.product,item=d.items[selectedOption];
        const basic=fields([['등록상품명',p.sellerProductName],['노출상품명',d.displayProductName],['브랜드',p.brand],['제품명',d.generalProductName],['상품군',d.productGroup],['노출 분류 코드',d.displayCategoryCode],['분류 ID',d.categoryId],['등록상품 ID',p.sellerProductId],['노출상품 ID',p.productId],['등록일시',p.createdAt?.replace('T',' ')]]);
        const optionTable=`<div class="table-wrap"><table class="data marketplace-options"><thead><tr><th>업체상품옵션 ID</th><th>옵션 ID</th><th>등록 옵션명</th><th>판매자 상품코드</th><th>구매 속성</th><th>현재 가격</th><th>재고</th><th>판매 상태</th><th>조회 상태</th></tr></thead><tbody>${d.items.map(i=>`<tr><td>${value(i.sellerProductItemId)}</td><td>${value(i.vendorItemId)}</td><td class="wrap">${value(i.itemName)}</td><td>${value(i.settings?.find(f=>f.name==='externalVendorSku')?.value)}</td><td class="wrap">${(i.attributes||[]).filter(a=>a.exposed==='EXPOSED').map(a=>`${value(a.name)}: ${value(a.value)}`).join('<br>')||'—'}</td><td>${i.current?value(i.current.salePrice.toLocaleString('ko-KR')+'원'):'미확인'}</td><td>${i.current?value(i.current.amountInStock.toLocaleString('ko-KR')+'개'):'미확인'}</td><td>${i.current?(i.current.onSale?'판매 중':'판매 중지'):'미확인'}</td><td class="wrap">${value(i.currentError)}</td></tr>`).join('')||'<tr><td colspan="9" class="empty">등록 옵션이 없습니다.</td></tr>'}</tbody></table></div>`;
        const images=(item?.images||[]).map(i=>`<figure>${image(i.url,item.itemName||'상품 이미지')}<figcaption>${value(codes[i.type]||i.type)} · ${value(i.order)}</figcaption></figure>`).join('')||'<p class="empty">등록된 이미지가 없습니다.</p>';
        const notices=(item?.notices||[]).map(n=>[`${n.category||''} · ${n.name||''}`,n.content]);
        const section={basic:`<h3>기본 정보</h3>${basic}<h3>옵션·판매</h3>${optionTable}`,images:`<h3>이미지</h3><div class="marketplace-images">${images}</div><h3>상품 설명</h3>${contents(item?.contents)}<h3>상품고시</h3>${notices.length?fields(notices):'<p class="empty">등록된 고시가 없습니다.</p>'}`,delivery:settings(d.delivery),settings:settings(d.settings)+`<h3>검색 속성</h3>${fields((item?.attributes||[]).filter(a=>a.exposed==='NONE').map(a=>[a.name,a.value]))}<h3>옵션 등록 설정</h3>${settings(item?.settings)}<h3>인증</h3>${(item?.certifications||[]).map(c=>fields([['인증 유형',c.type],['인증 번호',c.code]])+`<div class="marketplace-images">${(c.attachments||[]).map(i=>image(i.url,'인증 자료')).join('')}</div>`).join('')||'<p class="empty">등록된 인증이 없습니다.</p>'}`};
        $('marketplace-detail-import').hidden=false;$('marketplace-detail-edit').hidden=false;$('marketplace-detail-edit').href='/marketplaces/coupang/products/'+encodeURIComponent(p.sellerProductId)+'/edit';
        $('marketplace-detail-title').textContent=p.sellerProductName||'쿠팡 등록상품 상세';
        $('marketplace-detail-meta').textContent=`${p.sellerProductId} · ${codes[p.statusName]||p.statusName||'미확인'}`;
        $('marketplace-detail-content').innerHTML=`<div class="marketplace-tabs" role="tablist">${tabs.map(([key,label])=>`<button type="button" role="tab" id="marketplace-tab-${key}" aria-controls="marketplace-tab-panel" aria-selected="${key===activeTab}" tabindex="${key===activeTab?0:-1}" data-detail-tab="${key}">${e(label)}</button>`).join('')}</div>${['images','settings'].includes(activeTab)&&d.items.length?`<label class="field marketplace-option-select"><span>옵션</span><select id="marketplace-option">${d.items.map((i,n)=>`<option value="${n}" ${n===selectedOption?'selected':''}>${value(i.itemName||i.sellerProductItemId)}</option>`).join('')}</select></label>`:''}<section id="marketplace-tab-panel" role="tabpanel" aria-labelledby="marketplace-tab-${activeTab}">${section[activeTab]}</section>`;
        const selector=$('marketplace-option');if(['images','settings'].includes(activeTab)&&d.items.length)selector.addEventListener('change',()=>{selectedOption=Number(selector.value);renderDetail();$('marketplace-option').focus();});
    }
    async function detail(id){
        if(detailBusy&&id===detailId)return;
        importRead?.cancel();importRead=null;importSequence++;detailRead?.cancel();const seq=++detailSequence;const listed=result?.items.find(p=>p.sellerProductId===id);detailId=id;detailBusy=true;
        const dialog=$('marketplace-detail');if(!dialog.open)dialog.showModal();
        $('marketplace-detail-import').hidden=true;$('marketplace-detail-edit').hidden=true;detailData=null;$('marketplace-detail-title').textContent='쿠팡 등록상품 상세';$('marketplace-detail-meta').textContent='';$('marketplace-detail-content').innerHTML='';$('marketplace-detail-error').hidden=true;
        $('marketplace-detail-retry').hidden=true;$('marketplace-detail-loading').hidden=false;
        try {
            detailRead=startRead('/api/marketplaces/coupang/products/'+encodeURIComponent(id));
            const data=await detailRead.promise;
            if(seq!==detailSequence)return;
            if(!data.product||data.product.sellerProductId!==id||!Array.isArray(data.items))throw Error('쿠팡 상품 상세 응답을 확인할 수 없습니다.');
            detailData={...data,product:{...data.product,createdAt:data.product.createdAt??listed?.createdAt}};activeTab='basic';selectedOption=0;renderDetail();
        }catch(err){if(seq===detailSequence){$('marketplace-detail-error').textContent=err.message;$('marketplace-detail-error').hidden=false;$('marketplace-detail-retry').hidden=false;}}
        finally{if(seq===detailSequence){detailRead=null;detailBusy=false;$('marketplace-detail-loading').hidden=true;}}
    }
    $('marketplace-rows').addEventListener('click',ev=>{const button=ev.target.closest('[data-marketplace-product]');if(button){detail(button.dataset.marketplaceProduct);return;}const imported=ev.target.closest('[data-import-coupang]');if(imported){importCommon(imported.dataset.importCoupang);return;}if(ev.target.closest('[data-marketplace-editor-link]'))saveNavigation();});
    $('marketplace-detail-close').addEventListener('click',()=>$('marketplace-detail').close());
    $('marketplace-detail').addEventListener('close',()=>{detailSequence++;importSequence++;importRead?.cancel();importRead=null;detailRead?.cancel();detailRead=null;detailBusy=false;detailId=null;});
    $('marketplace-detail-import').addEventListener('click',()=>{if(detailId)importCommon(detailId);});
    async function importCommon(id){
        if(importRead)return;const seq=++importSequence;$('marketplace-detail-import').disabled=true;
        try{importRead=startRead('/api/marketplaces/drafts/import/coupang/'+encodeURIComponent(id),{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const saved=await importRead.promise;if(seq!==importSequence)return;if(!saved?.id)throw Error('가져온 초안을 확인해 주세요.');saveNavigation();location.assign('/marketplaces/products/'+encodeURIComponent(saved.id)+'/edit');}
        catch(err){if(seq===importSequence){const box=$('marketplace-detail').open?$('marketplace-detail-error'):$('marketplace-error');box.textContent=err.message;box.hidden=false;}}
        finally{if(seq===importSequence){importRead=null;$('marketplace-detail-import').disabled=false;}}
    }
    $('marketplace-detail-retry').addEventListener('click',()=>{if(detailId)detail(detailId);});
    $('marketplace-query').addEventListener('submit',ev=>{ev.preventDefault();if(busy)return;submitted=inputs();load(0,true);});
    $('marketplace-size').addEventListener('change',()=>{sequence++;queryRead?.cancel();queryRead=null;busy=false;$('marketplace-query').setAttribute('aria-busy','false');submitted=inputs();tokens=[''];index=0;result=null;$('marketplace-rows').innerHTML='<tr><td colspan="7" class="empty">조회 버튼을 눌러 상품을 불러오세요.</td></tr>';$('marketplace-page').textContent='';$('marketplace-summary').textContent='조회 버튼을 눌러 상품을 확인하세요.';buttons();});
    $('marketplace-first').addEventListener('click',()=>load(0,true));
    $('marketplace-prev').addEventListener('click',()=>load(index-1));
    $('marketplace-next').addEventListener('click',()=>load(index+1));
    $('marketplace-reset').addEventListener('click',()=>{if(busy)return;filterKeys.forEach(key=>$('filter-'+key).value='');submitted={};load(0,true);});
    $('marketplace-detail-content').addEventListener('click',ev=>{const tab=ev.target.closest('[data-detail-tab]');if(tab&&detailData){activeTab=tab.dataset.detailTab;renderDetail();$('marketplace-tab-'+activeTab).focus();}});
    $('marketplace-detail-content').addEventListener('keydown',ev=>{const tab=ev.target.closest('[data-detail-tab]');if(!tab||!['ArrowLeft','ArrowRight','Home','End'].includes(ev.key))return;ev.preventDefault();const i=tabs.findIndex(([key])=>key===activeTab);activeTab=tabs[ev.key==='Home'?0:ev.key==='End'?tabs.length-1:(i+(ev.key==='ArrowRight'?1:tabs.length-1))%tabs.length][0];renderDetail();$('marketplace-tab-'+activeTab).focus();});
    $('marketplace-detail-content').addEventListener('error',ev=>{if(ev.target.tagName==='IMG'){ev.target.hidden=true;if(ev.target.nextElementSibling)ev.target.nextElementSibling.hidden=false;}},true);
    // Only navigation conditions and opaque page tokens are retained, never product responses.
    const navigationKey='molebutter.coupang.navigation';
    function saveNavigation(){
        try{sessionStorage.setItem(navigationKey,JSON.stringify({submitted,inputs:inputs(),tokens,index,size:$('marketplace-size').value,scroll:window.scrollY}));}catch{}
    }
    if(document.querySelectorAll)document.querySelectorAll('[data-marketplace-editor-link]').forEach(link=>link.addEventListener('click',saveNavigation));
    async function restore(){
        let saved;try{saved=JSON.parse(sessionStorage.getItem(navigationKey));sessionStorage.removeItem(navigationKey);}catch{return;}
        if(!saved||!Array.isArray(saved.tokens)||!Number.isInteger(saved.index)||saved.index<0||saved.index>=saved.tokens.length||!['10','50','100'].includes(saved.size)||saved.tokens.some(t=>typeof t!=='string'||t.length>512))return;
        submitted=Object.fromEntries(filterKeys.filter(k=>typeof saved.submitted?.[k]==='string').map(k=>[k,saved.submitted[k]]));
        filterKeys.forEach(k=>$('filter-'+k).value=typeof saved.inputs?.[k]==='string'?saved.inputs[k]:submitted[k]||'');
        $('marketplace-size').value=saved.size;tokens=saved.tokens;await load(saved.index);window.scrollTo?.(0,Number.isFinite(saved.scroll)?saved.scroll:0);
    }
    restore();
    window.addEventListener('pagehide',()=>{sequence++;detailSequence++;importSequence++;queryRead?.cancel();detailRead?.cancel();importRead?.cancel();});
})();
