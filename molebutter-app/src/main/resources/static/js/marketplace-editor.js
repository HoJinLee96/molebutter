(() => {
    'use strict';
    const {$,escape:e}=AppUI,M=window.CoupangEditorModel;
    const editMatch=/^\/marketplaces\/coupang\/products\/(\d{1,30})\/edit$/.exec(location.pathname),mode=editMatch?'edit':'new';
    let draft=null,rules=null,selected=0,dirty=false,loading=false,ruleLoading=false,loadSequence=0,ruleSequence=0;
    let saveBusy=false;
    const save=mode==='edit'?window.CoupangEditorSave.create({productId:editMatch[1],value:()=>draft,busy:value=>{saveBusy=value;updateBusy();},saved:async submitted=>{const observation=await apiGet('/api/marketplaces/coupang/products/'+editMatch[1]+'/edit-observation'),fresh=M.fromDocument(observation.document);draft=M.rebase(submitted,draft,fresh);try{dirty=M.changes(fresh,draft).length>0;}catch{dirty=true;}render();return {observation,draft:fresh};}}):window.CoupangProductRegistration.create({value:()=>draft,busy:value=>{saveBusy=value;updateBusy();},saved:()=>{dirty=false;}});
    const localUrls=new Set();let editRead=null,categoryRead=null,importRead=null;
    const startRead=(url,options)=>window.CoupangRead.start(url,options);
    const codeNames={SINGLE:'단일 상품 구성',AB:'혼합 구성',SEQUENCIAL:'일반배송',COLD_FRESH:'신선·냉동',MAKE_ORDER:'주문제작',AGENT_BUY:'구매대행',VENDOR_DIRECT:'직접 전달·설치',FREE:'무료',NOT_FREE:'유료',CHARGE_RECEIVED:'착불',CONDITIONAL_FREE:'조건부 무료',Y:'가능',N:'불가',UNION_DELIVERY:'묶음 배송',NOT_UNION_DELIVERY:'개별 배송',EVERYONE:'전체 이용',ADULT_ONLY:'성인 전용',TAX:'과세',PARALLEL_IMPORTED:'병행 수입',NOT_PARALLEL_IMPORTED:'해당 없음',OVERSEAS_PURCHASED:'해외 구매',NOT_OVERSEAS_PURCHASED:'해당 없음',true:'예',false:'아니요',NEW:'새 상품',REFURBISHED:'리퍼',USED_BEST:'중고 최상',USED_GOOD:'중고 상',USED_NORMAL:'중고 중'};
    // Registration keys contain dots; field paths use the key as a single segment.
    const split=path=>path.includes('.registration.')? [...path.split('.registration.')[0].split('.'),'registration',path.split('.registration.')[1]]:path.startsWith('settings.')?['settings',path.slice(9)]:path.split('.');
    const get=path=>split(path).reduce((v,k)=>v?.[k],draft);
    function set(path,v){const keys=split(path),last=keys.pop();keys.reduce((o,k)=>o[k],draft)[last]=v;}
    const id=path=>'field-'+path.replaceAll('.','-');
    const returnKeys=['deliveryChargeOnReturn','returnCenterCode','returnChargeName','companyContactNumber','returnZipCode','returnAddress','returnAddressDetail','returnCharge'];
    const saleKeys=['externalVendorSku','originalPrice','salePrice','maximumBuyCount'];
    const majorKeys=['unitCount','parallelImported','overseasPurchased','pccNeeded','adultOnly','maximumBuyForPerson','maximumBuyForPersonPeriod','taxType'];
    const courierNames={HYUNDAI:'롯데택배',KGB:'로젠택배',EPOST:'우체국',HANJIN:'한진택배',CJGLS:'CJ대한통운',KOREX:'대한통운[합병]',KDEXP:'경동택배',DIRECT:'업체직송',ILYANG:'일양택배',CHUNIL:'천일특송',AJOU:'아주택배'};
    function field(path,label,type='text',value='',required=false,readonly=false,choices=[],max=null,compact=false){
        const attrs=`id="${e(id(path))}" data-field="${e(path)}" aria-label="${e(label)}" ${required?'aria-required="true"':''} ${max?`maxlength="${max}"`:''} ${readonly?'disabled':''}`;
        const text=String(value??'');
        const plainNumber=type==='number'&&/^options\.\d+\.(registration\.(originalPrice|salePrice|maximumBuyCount)|currentChanges\.(salePrice|amountInStock))$/.test(path);
        const labelFor=c=>path.endsWith('.taxType')&&c==='FREE'?'면세':path==='delivery.deliveryCompanyCode'?(courierNames[c]||c):(codeNames[c]||c);
        const control=type==='textarea'?`<textarea ${attrs} rows="4">${e(text)}</textarea>`:type==='select'?`<select ${attrs}><option value="">선택</option>${[...new Set([...choices,...(text?[text]:[])])].map(c=>`<option value="${e(c)}" ${c===text?'selected':''}>${e(labelFor(c))}</option>`).join('')}</select>`:`<input ${attrs} type="${type==='id'||plainNumber?'text':type}" ${['id','number'].includes(type)?'inputmode="numeric"':''} ${plainNumber?'data-plain-number="true"':type==='number'?'min="0" step="1"':''} ${type==='datetime-local'?'step="1"':''} value="${e(text)}">`;
        const error=`<small class="editor-field-error" data-error-for="${e(path)}" hidden></small>`;
        return compact?`<span class="coupang-table-field">${control}${error}</span>`:`<label class="field coupang-form-row ${type==='textarea'?'editor-wide':''}"><span class="coupang-row-label">${e(label)}${required?' <span class="editor-required" aria-label="필수">*</span>':''}</span><span class="coupang-row-body">${control}${error}</span></label>`;
    }
    const grid=body=>`<div class="coupang-field-grid">${body}</div>`;
    const row=(label,body)=>`<div class="coupang-form-row"><div class="coupang-row-label">${e(label)}</div><div class="coupang-row-body">${body}</div></div>`;
    let purchaseLimitMode=null,salePeriodMode=null,certificationSelection=null;
    const certificationBackups=new WeakMap();
    let certificationEntryType="",certificationEntryCode="";
    function choices(path,label,pairs,value,disabled=false,action=null){
        const values=path?.startsWith('options.')?M.sharedValues(draft,path):[value];
        const mixed=values.some(v=>String(v??'')!==String(values[0]??''));
        const current=mixed?'':String(value??'');
        const all=[...pairs];if(current&&!all.some(([v])=>v===current))all.push([current,current]);
        return `<div class="coupang-choice-row" role="group" aria-label="${e(label)}"><div class="coupang-row-label">${e(label)}</div><div class="coupang-row-body"><div class="coupang-radio-options">${all.map(([v,text,unavailable])=>`<label><input type="radio" name="${e(path||action)}" id="${e(id(path||action)+'-'+v)}" ${path?`data-field="${e(path)}"`:`data-settings-action="${e(action)}"`} value="${e(v)}" ${v===current?'checked':''} ${disabled||unavailable?'disabled':''}><span>${e(text)}</span></label>`).join('')}</div>${mixed?'<small class="product-meta">옵션별 값 다름 · 선택하면 모든 옵션에 적용됩니다.</small>':''}${path?`<small class="editor-field-error" data-error-for="${e(path)}" hidden></small>`:''}</div></div>`;
    }
    const certificationMode=o=>o.certifications.length===0?'':o.certifications.every(c=>c.type==='NOT_REQUIRED')?'NOT_REQUIRED':o.certifications.every(c=>c.type==='PRESENTED_IN_DETAIL_PAGE')?'PRESENTED_IN_DETAIL_PAGE':'TARGET';
    function settingAction(action,value){
        if(action==='purchase-limit'){
            purchaseLimitMode=value;
            if(value==='OFF')for(const o of draft.options){o.registration.maximumBuyForPerson='0';o.registration.maximumBuyForPersonPeriod='1';}
        }else if(action==='sale-period'){
            salePeriodMode=value;
            if(value==='OFF'){
                if(!draft.settings.saleStartedAt){const now=new Date();draft.settings.saleStartedAt=new Date(now.getTime()-now.getTimezoneOffset()*60000).toISOString().slice(0,19);}
                draft.settings.saleEndedAt='2099-12-31T23:59:59';
            }
        }else if(action==='certification'){
            if(value!=='TARGET'&&(!rules?.certifications.some(c=>c.type===value)||rules.certifications.some(c=>c.required==='MANDATORY')))return;
            certificationSelection=value;
            for(const o of draft.options){
                if(certificationMode(o)==='TARGET')certificationBackups.set(o,structuredClone(o.certifications));
                o.certifications=value==='TARGET'?structuredClone(certificationBackups.get(o)||[]):[{type:value,code:'',attachments:[]}];
            }
        }else return;
        changed();renderSettings();markSharedFields();
    }
    function configured(prefix,keys,values,readonly=false){return keys.map(k=>{const [baseLabel,baseType,max,required,baseChoices]=M.spec[k]||[k,'text'];const label=k==='deliveryCompanyCode'?'택배사':k==='deliveryMethod'?'배송방법':k==='deliveryChargeType'?'배송비 종류':k==='modelNo'?'모델번호':k==='unitCount'?'구성 단위수량':k==='extraInfoMessage'?'주문제작 입력 안내':baseLabel;const type=k==='deliveryCompanyCode'?'select':baseType,choices=k==='deliveryCompanyCode'?Object.keys(courierNames):baseChoices;return field(prefix+'.'+k,label,type,values[k],required,typeof readonly==='function'?readonly(k):readonly,choices,max);}).join('');}
    function attributeField(o,n,k,compact=false){
        const a=o.attributes[k],r=rules?.attributes.find(v=>v.name===a.name),locked=!['NONE','EXPOSED'].includes(a.exposed)||(mode==='edit'&&a.exposed==='EXPOSED');
        return field(`options.${n}.attributes.${k}.value`,a.name==='Manufacturer Part Number'?'품번 (MPN)':a.name==='Global Trade Item Number'?'바코드 (GTIN)':a.name||'속성','text',a.value,r?.required==='MANDATORY'&&(!r.groupNumber||r.groupNumber==='NONE'),locked,[],30,compact);
    }
    function attrs(o,n,exposed){
        return grid(o.attributes.map((a,k)=>a.exposed===exposed&&!M.productIdentity(a.name)?attributeField(o,n,k):'').join('')||'<p class="empty">등록된 속성이 없습니다.</p>');
    }
    function syncOptionPickers(){
        $('editor-option-tabs').innerHTML=draft.options.map((o,n)=>`<button type="button" role="tab" id="editor-option-tab-${n}" aria-controls="editor-option-panel" aria-selected="${n===selected}" tabindex="${n===selected?0:-1}" data-option-tab="${n}">${e(o.itemName||'옵션 '+(n+1))}</button>`).join('');
        $('editor-option-panel').setAttribute('aria-labelledby','editor-option-tab-'+selected);
    }
    function selectOption(n){
        clearImageDrag();selected=Math.max(0,Math.min(Number(n),draft.options.length-1));syncOptionPickers();
        renderMedia();
    }
    function render(){
        const b=draft.basic;
        $('editor-mode').textContent=mode==='edit'?`상품 수정 · ${b.sellerProductId} · ${b.statusName||'미확인'}`:'상품 등록';
        $('editor-title').textContent=mode==='edit'?(b.sellerProductName||'쿠팡 상품 수정'):'쿠팡 상품 등록';
        const basic=['brand','displayProductName','sellerProductName','displayCategoryCode'];
        const categoryControls=$('editor-category-load').parentElement;categoryControls.remove();
        $('editor-basic-fields').innerHTML=grid(basic.map(k=>{const [label,type,max,required]=M.spec[k];return field('basic.'+k,label,type,b[k],required,mode==='edit'&&k==='displayCategoryCode',[],max);}).join(''))+`<details class="coupang-additional"><summary>추가 상품 정보</summary>${grid(['generalProductName','productGroup'].map(k=>field('basic.'+k,M.spec[k][0],'text',b[k])).join('')+(mode==='edit'?field('basic.sellerProductId','등록상품 ID','text',b.sellerProductId,false,true)+field('basic.productId','노출상품 ID','text',b.productId,false,true):''))}</details>`;
        window.CoupangBrandPicker.mount($('field-basic-brand'),{name:b.brand,id:draft.settings.brandId,locked:mode==='edit',onSelect:brand=>{draft.basic.brand=brand.brandName;draft.settings.brandId=brand.brandId;changed();render();}});
        $('editor-basic-fields').firstElementChild.classList.add('coupang-basic-grid');
        const categoryLabel=$('field-basic-displayCategoryCode').closest('label'),categoryRow=document.createElement('div');categoryRow.className=categoryLabel.className;categoryRow.append(...categoryLabel.childNodes);categoryLabel.replaceWith(categoryRow);
        const categoryCaption=document.createElement('label');categoryCaption.className='coupang-row-label';categoryCaption.htmlFor='field-basic-displayCategoryCode';categoryCaption.innerHTML=categoryRow.firstElementChild.innerHTML;categoryRow.firstElementChild.replaceWith(categoryCaption);
        $('field-basic-displayCategoryCode').parentElement.classList.add('coupang-category-inline');
        $('field-basic-displayCategoryCode').parentElement.append(categoryControls);
        categoryControls.hidden=mode==='edit';
        renderOptionTable();
        $('editor-option-add').hidden=mode==='edit';
        renderDelivery();
        syncOptionPickers();renderMedia();renderSettings();renderNotices();markSharedFields();$('marketplace-editor-form').hidden=false;updateBusy();
    }
    function renderOptionTable(){
        const identities=(o,n)=>o.attributes.map((a,k)=>M.productIdentity(a.name)?attributeField(o,n,k,true):'').join('')||'<span class="product-meta">미확인</span>';
        const tableInput=(o,n,k,label)=>{const [defaultLabel,type,max,required,choices]=M.spec[k];return field(`options.${n}.registration.${k}`,label||defaultLabel,type,o.registration[k],required,false,choices,max,true);};
        const liveInput=(o,n,key,label)=>o.separateCurrentChanges?field(`options.${n}.currentChanges.${key}`,label,'number',o.currentChanges[key]??o.current?.[key],false,o.current?.[key]==null,[],undefined,true)+(o.current?.[key]==null?'<small class="form-error">미확인</small>':''):tableInput(o,n,key==='amountInStock'?'maximumBuyCount':key,label);
        $('editor-option-list').innerHTML=`<div class="coupang-table-scroll" tabindex="0" aria-label="옵션 목록"><table class="coupang-option-table"><thead><tr><th scope="col">옵션 아이디</th><th scope="col">등록 옵션명</th><th scope="col">품번 (MPN)</th><th scope="col">정상가</th><th scope="col">판매가</th><th scope="col">재고</th><th scope="col">판매자상품코드</th>${mode==='new'?'<th scope="col">작업</th>':''}</tr></thead><tbody>${draft.options.map((o,n)=>`<tr class="editor-option-card"><td>${e(o.vendorItemId||(mode==='new'?'등록 후 발급':'미확인'))}${o.currentError?`<small class="form-error">${e(o.currentError)}</small>`:''}</td><td>${field(`options.${n}.itemName`,'등록 옵션명','text',o.itemName,true,mode==='new',[],150,true)}${mode==='new'?attrs(o,n,'EXPOSED'):''}</td><td>${identities(o,n)}</td><td>${tableInput(o,n,'originalPrice','등록 정상가')}</td><td>${liveInput(o,n,'salePrice','판매가')}</td><td>${liveInput(o,n,'amountInStock','재고')}</td><td>${tableInput(o,n,'externalVendorSku')}</td>${mode==='new'?`<td><button class="btn small" type="button" data-remove-option="${n}" ${draft.options.length===1?'disabled':''}>삭제</button></td>`:''}</tr>`).join('')}</tbody></table></div>`;

    }
    function imageFigure(i,n,total){
        return `<figure data-image-index="${n}" draggable="true" tabindex="0" aria-label="${e(i.type==='REPRESENTATION'?'대표 이미지':'이미지')} ${n+1}" aria-description="끌어서 순서 또는 대표·추가 영역을 변경합니다. 키보드 좌우 방향키로 순서 변경, R로 대표, D로 추가로 이동합니다."><div class="editor-image-preview">${i.url?`<img draggable="false" src="${e(i.local?i.url:i.assetId&&i.url==='/api/marketplaces/assets/'+i.assetId?i.url:M.url(i.url)||'')}" alt="옵션 이미지 ${n+1}" referrerpolicy="no-referrer">`:'<span>이미지 없음</span>'}</div><figcaption>${e(i.type==='REPRESENTATION'?'대표':i.type==='DETAIL'?'추가':i.type==='USED_PRODUCT'?'중고 상태':i.type||'이미지')} · ${n+1}${i.pending?'<span role="status">업로드 중…</span>':''}${i.error?`<span class="form-error">${e(i.error)}</span><button type="button" class="btn small" data-image-retry="${n}">업로드 재시도</button>`:''}</figcaption><div class="editor-image-actions"><button type="button" class="btn small" data-image-action="remove" data-index="${n}">삭제</button></div></figure>`;
    }
    let outboundPlace=null,outboundRead=null,outboundStatus='',shippingTimeMode=null,addressRead=null,addressSequence=0,addressKind='outbound',addressPage=1,addressResult=null;
    const addressText=a=>[a?.zipCode,a?.address,a?.detail].filter(Boolean).join(' ');
    function addressSummary(returns){
        const d=draft.delivery,code=returns?d.returnCenterCode:d.outboundShippingPlaceCode;
        const title=returns?(d.returnChargeName||'반품/교환지'):(outboundPlace&&outboundPlace.code===code?outboundPlace.name:'상품출고지');
        const text=returns?[d.returnZipCode,d.returnAddress,d.returnAddressDetail].filter(Boolean).join(' '):outboundPlace&&outboundPlace.code===code?addressText(outboundPlace.addresses[0]):(outboundStatus||'주소록에서 출고지를 선택해 주세요.');
        return row(returns?'반품/교환지 *':'출고지 *',`<div class="coupang-address-summary"><div><strong>${e(title)}</strong><p>${e(text)}</p><small class="product-meta">${e(code?'코드 '+code:'주소록에서 선택해 주세요.')}${returns&&d.companyContactNumber?' · '+e(d.companyContactNumber):''}</small></div><button type="button" class="btn" data-address-book="${returns?'return':'outbound'}">판매자 주소록</button></div>`);
    }
    function shippingTimes(){
        const vals=draft.options.map(o=>String(o.registration.outboundShippingTimeDay??'')),same=vals.every(v=>v===vals[0]);
        if(mode==='new')return field('options.0.registration.outboundShippingTimeDay','출고 소요일 (일)','number',vals[0],true);
        const selectedMode=shippingTimeMode||(same?'COMMON':'OPTIONS');
        return choices(null,'출고 소요일',[['COMMON','기본 입력'],['OPTIONS','구매 옵션별로 입력']],selectedMode,false,'shipping-time')+`<div class="coupang-shipping-times">${selectedMode==='COMMON'?field('options.0.registration.outboundShippingTimeDay','출고 소요일 (일)','number',same?vals[0]:'',true)+(!same?'<small class="product-meta">옵션별 값이 다릅니다. 입력하면 모든 옵션에 적용됩니다.</small>':''):draft.options.map((o,n)=>field(`options.${n}.registration.outboundShippingTimeDay`,`${o.itemName||'옵션 '+(n+1)} · 출고 소요일 (일)`,'number',vals[n],true)).join('')}</div>`;
    }
    function returnTotal(){
        const d=draft.delivery,initial=d.deliveryChargeType==='FREE'?d.deliveryChargeOnReturn:d.deliveryCharge;
        const valid=[initial,d.returnCharge].every(v=>String(v??'').trim()!==''&&/^\d+$/.test(String(v)));
        const total=valid?Number(initial)+Number(d.returnCharge):null;
        $('editor-return-total').textContent=total!==null&&Number.isSafeInteger(total)?`고객 사유로 반품 시 왕복 배송비는 ${d.deliveryChargeType==='FREE'?'초도배송비':'기본배송비'} + 반품배송비의 합계인 ${total.toLocaleString('ko-KR')}원입니다.`:'배송비를 입력하면 왕복 반품 배송비 합계가 표시됩니다.';
    }
    function renderDelivery(){
        const d=draft.delivery;
        $('editor-delivery-fields').innerHTML=addressSummary(false)+choices('delivery.remoteAreaDeliverable','제주/도서산간 배송여부 *',[['Y','가능'],['N','불가능']],d.remoteAreaDeliverable)+configured('delivery',['deliveryCompanyCode','deliveryMethod'],d)+choices('delivery.unionDeliveryType','묶음배송 *',[['UNION_DELIVERY','가능'],['NOT_UNION_DELIVERY','불가능']],d.unionDeliveryType)+configured('delivery',['deliveryChargeType'],d)+`<div ${d.deliveryChargeType==='FREE'&&String(d.deliveryCharge)==='0'?'hidden':''}>${configured('delivery',['deliveryCharge'],d)}</div><div ${d.deliveryChargeType==='CONDITIONAL_FREE'||!d.deliveryChargeType||Number(d.freeShipOverAmount)!==0?'':'hidden'}>${configured('delivery',['freeShipOverAmount'],d)}</div>`+shippingTimes();
        $('editor-return-fields').innerHTML=addressSummary(true)+field('delivery.deliveryChargeOnReturn','초도배송비(편도, 원)','number',d.deliveryChargeOnReturn,true)+field('delivery.returnCharge','반품배송비(편도, 원)','number',d.returnCharge,true)+'<p id="editor-return-total" class="coupang-return-total" aria-live="polite"></p>';
        returnTotal();
    }
    async function loadOutboundSummary(seq){
        const code=draft.delivery.outboundShippingPlaceCode;if(!code||outboundPlace?.code===code)return;
        outboundStatus='출고지 주소 조회 중…';renderDelivery();
        try{
            const read=startRead('/api/marketplaces/coupang/shipping-places/outbound/'+encodeURIComponent(code));outboundRead=read;
            const result=await read.promise;if(seq!==loadSequence||draft.delivery.outboundShippingPlaceCode!==code||outboundPlace?.code===code)return;
            const place=result?.items?.find(p=>p.code===code);
            if(!place?.addresses?.length)throw Error('현재 출고지 주소를 찾지 못했습니다. 주소록에서 확인해 주세요.');
            outboundPlace=place;outboundStatus='';renderDelivery();
        }catch(err){if(seq===loadSequence&&draft.delivery.outboundShippingPlaceCode===code&&!outboundPlace){outboundStatus=err.message;renderDelivery();}}
        finally{outboundRead=null;}
    }
    async function loadAddressPage(page){
        addressRead?.cancel();const seq=++addressSequence;addressPage=page;addressResult=null;
        $('editor-address-list').innerHTML='<p role="status">주소록 조회 중…</p>';$('editor-address-prev').disabled=true;$('editor-address-next').disabled=true;
        try{
            const read=startRead('/api/marketplaces/coupang/shipping-places/'+addressKind+'?page='+page);addressRead=read;
            const result=await read.promise;if(seq!==addressSequence)return;
            if(!Array.isArray(result?.items))throw Error('주소록 응답을 확인해 주세요.');addressResult=result;
            $('editor-address-list').innerHTML=result.items.flatMap((place,n)=>place.addresses.map((a,k)=>`<button type="button" class="coupang-address-choice" data-address-place="${n}" data-address-index="${k}" ${!place.usable?'disabled':''}><strong>${e(place.name)}</strong><span>${e(addressText(a))}</span><small>${e(place.code)} · ${e(a.addressType)}${a.phone?' · '+e(a.phone):''}${!place.usable?' · 사용 불가':''}</small></button>`)).join('')||'<p>등록된 주소가 없습니다.</p>';
            $('editor-address-prev').disabled=page<=1;$('editor-address-next').disabled=!result.hasNext;$('editor-address-page').textContent=`${page} 페이지 · 총 ${result.totalCount}곳`;
        }catch(err){if(seq===addressSequence)$('editor-address-list').textContent=err.message;}
        finally{if(seq===addressSequence)addressRead=null;}
    }
    function closeAddressBook(){addressSequence++;addressRead?.cancel();addressRead=null;$('editor-address-dialog').close();}
    $('editor-address-dialog').addEventListener('cancel',ev=>{ev.preventDefault();closeAddressBook();});
    $('editor-address-dialog').addEventListener('click',ev=>{
        if(ev.target.closest('[data-address-close]')){closeAddressBook();return;}
        if(ev.target.closest('#editor-address-prev')){loadAddressPage(addressPage-1);return;}
        if(ev.target.closest('#editor-address-next')){loadAddressPage(addressPage+1);return;}
        if(ev.target.closest('#editor-address-retry')){loadAddressPage(addressPage);return;}
        const button=ev.target.closest('[data-address-place]');if(!button||button.disabled)return;
        const place=addressResult?.items[Number(button.dataset.addressPlace)],a=place?.addresses[Number(button.dataset.addressIndex)];if(!a||!place.usable)return;
        if(addressKind==='outbound'){outboundPlace={...place,addresses:[a]};draft.delivery.outboundShippingPlaceCode=place.code;}
        else Object.assign(draft.delivery,{returnCenterCode:place.code,returnChargeName:place.name,companyContactNumber:a.phone,returnZipCode:a.zipCode,returnAddress:a.address,returnAddressDetail:a.detail});
        changed();renderDelivery();closeAddressBook();
    });
    function renderMedia(){
        const o=draft.options[selected],p='options.'+selected;
        const images=type=>o.images.map((i,n)=>i.type===type?imageFigure(i,n,o.images.length):'').join('');
        $('editor-media-fields').innerHTML=`<div class="editor-image-controls"><label class="field"><span>이미지 주소</span><input id="editor-image-url" type="url" placeholder="https://"></label><button class="btn" type="button" data-add-image>주소 추가</button><label class="btn editor-file-label">파일 선택<input id="editor-image-file" type="file" accept="image/jpeg,image/png" multiple></label></div><p class="product-meta">파일은 서버 업로드 후 저장 요청에 포함됩니다.</p><p id="editor-image-error" class="form-error" role="alert" hidden></p>${row('대표 이미지',`<div class="coupang-image-group" data-image-group="representative" data-image-type="REPRESENTATION"><div class="editor-image-grid">${images('REPRESENTATION')||'<p class="empty">대표 이미지를 추가해 주세요.</p>'}</div></div>`)}${row('추가 이미지 ('+o.images.filter(i=>i.type==='DETAIL').length+'/9)',`<div class="coupang-image-group" data-image-group="additional" data-image-type="DETAIL"><div class="editor-image-grid">${images('DETAIL')||'<p class="empty">추가 이미지가 없습니다.</p>'}</div></div>`)}${o.images.some(i=>i.type==='USED_PRODUCT')?row('중고 상태 이미지',`<div class="coupang-image-group" data-image-group="used" data-image-type="USED_PRODUCT"><div class="editor-image-grid">${images('USED_PRODUCT')}</div></div>`):''}<div data-error-for="${p}.images" class="editor-field-error" hidden></div>`;
        $('editor-description-fields').innerHTML=o.contents.map((c,n)=>`<div class="editor-content"><h3>${e(c.type==='HTML'?'HTML 설명':c.detailType==='IMAGE'?'설명 이미지':c.type||c.detailType||'콘텐츠')} ${n+1}</h3>${field(`${p}.contents.${n}.content`,c.detailType==='IMAGE'?'설명 이미지 주소':'설명 원문',c.detailType==='IMAGE'?'text':'textarea',c.content)}<div class="editor-preview" data-preview="${n}"></div><button class="btn small" type="button" data-remove-content="${n}">설명 삭제</button></div>`).join('')+`<div data-error-for="${p}.contents" class="editor-field-error" hidden></div><div class="toolbar"><button type="button" class="btn" data-add-content="HTML">HTML 설명 추가</button><button type="button" class="btn" data-add-content="IMAGE">설명 이미지 추가</button></div>`;

        previews();updateBusy();
    }
    const noticeReference='상품 상세페이지 참조',noticeBackups=new WeakMap();
    function noticeReferenceChecked(){const notices=draft.options.flatMap(o=>o.notices.filter(v=>v.category===o.noticeCategory));return notices.length>0&&notices.every(v=>String(v.content??'').trim()===noticeReference);}
    function updateNoticeReference(){$('editor-notice-reference').checked=noticeReferenceChecked();}
    function renderNotices(){
        const o=draft.options[0],p='options.0';
        $('editor-notice-fields').innerHTML=`<label class="coupang-notice-reference"><input type="checkbox" id="editor-notice-reference" ${noticeReferenceChecked()?'checked':''}>전체 상품 상세페이지 참조</label>`+(rules?.notices.length?field(p+'.noticeCategory','고시 유형','select',o.noticeCategory,true,false,rules.notices.map(n=>n.name)):'')+grid(o.notices.map((v,n)=>{if(v.category!==o.noticeCategory)return '';const r=rules?.notices.find(c=>c.name===v.category)?.fields.find(f=>f.name===v.name);return field(`${p}.notices.${n}.content`,`${v.category||''} · ${v.name||''}`,'textarea',v.content,r?.required==='MANDATORY');}).join('')||'<p class="empty">카테고리 규격을 조회해 주세요.</p>');
    }
    function previews(){
        const o=draft.options[selected];
        document.querySelectorAll('[data-preview]').forEach(el=>{
            const c=o.contents[Number(el.dataset.preview)];if(!c)return;
            el.innerHTML=c.type==='HTML'?`<iframe class="marketplace-html" sandbox="" referrerpolicy="no-referrer" title="상품 설명 미리보기" srcdoc="${e(M.preview(c.content))}"></iframe>`:c.detailType==='IMAGE'&&M.descriptionUrl(c.content)?`<img src="${e(M.descriptionUrl(c.content))}" alt="상품 설명 이미지" referrerpolicy="no-referrer">`:`<p class="marketplace-content-text">${e(c.content||'')}</p>`;
        });
    }
    function renderTags(){
        const o=draft.options[0],tags=String(o.registration.searchTags||'').split(',').map(t=>t.trim()).filter(Boolean);
        $('editor-tag-list').innerHTML=tags.map((t,n)=>`<span class="coupang-tag">${e(t)}<button type="button" data-remove-tag="${n}" aria-label="${e(t)} 검색어 삭제">×</button></span>`).join('');
        $('editor-tag-count').textContent=tags.length+' / 20개 · 검색어당 최대 20자';
        const invalid=tags.length>20||tags.some(t=>Array.from(t).length>20);
        $('editor-tag-limit').hidden=!invalid;$('editor-tag-limit').textContent=invalid?'검색어는 최대 20개, 각 20자 이하로 입력해 주세요.':'';
        $('field-options-0-registration-searchTags').setAttribute('aria-invalid',String(invalid));

    }
    function renderSettings(){
        const extraOpen=document.getElementById('editor-extra-settings')?.open;
        const o=draft.options[0],p='options.0';
        const extras=Object.keys(o.registration).filter(k=>!M.optionKeys.includes(k)),additional=M.optionKeys.filter(k=>!saleKeys.includes(k)&&!majorKeys.includes(k)&&k!=='searchTags'&&k!=='outboundShippingTimeDay');
        const bundle=choices('settings.bundleInfo.bundleType','상품 구성',[['SINGLE','동일한 상품으로 구성됨'],['AB','다양한 상품이 혼합되어 구성됨',mode==='new']],draft.settings['bundleInfo.bundleType'],mode==='edit');
        const certMode=certificationSelection??certificationMode(o),certMixed=certificationSelection===null&&draft.options.some(v=>certificationMode(v)!==certMode);
        const types=rules?.certifications||[],hasMandatory=types.some(c=>c.required==='MANDATORY');
        const certChoices=choices(null,'인증정보',[['TARGET','인증·신고 대상',!types.some(c=>!['NOT_REQUIRED','PRESENTED_IN_DETAIL_PAGE'].includes(c.type))],['PRESENTED_IN_DETAIL_PAGE','상세페이지 별도표기',hasMandatory||!types.some(c=>c.type==='PRESENTED_IN_DETAIL_PAGE')],['NOT_REQUIRED','인증·신고 대상 아님',hasMandatory||!types.some(c=>c.type==='NOT_REQUIRED')]],certMixed?'':certMode,false,'certification');
        const certificationFields=o.certifications.filter(c=>!['NOT_REQUIRED','PRESENTED_IN_DETAIL_PAGE'].includes(c.type)).map(c=>{const n=o.certifications.indexOf(c),r=types.find(v=>v.type===c.type);return `<div class="coupang-certification-entry"><div class="product-meta">${e(r?.name||c.type||'인증 유형')}${r?.required==='MANDATORY'?' · 필수':''}</div>${field(`${p}.certifications.${n}.code`,'인증번호','text',c.code,r?.required==='MANDATORY'&&r.dataType==='CODE')}<button class="btn small" type="button" data-remove-cert="${e(c.type)}" ${r?.required==='MANDATORY'?'disabled':''}>삭제</button><small class="product-meta">${e(c.type||'')}${c.attachments?.length?' · 첨부 '+c.attachments.length+'개':''}</small></div>`;}).join('');
        const certificationControls=`<div class="coupang-certification-add"><label class="field"><span>인증 유형</span><select id="editor-cert-type" data-cert-entry="type" aria-label="추가할 인증 유형"><option value="">선택</option>${types.filter(c=>!['NOT_REQUIRED','PRESENTED_IN_DETAIL_PAGE'].includes(c.type)).map(c=>`<option value="${e(c.type)}" ${c.type===certificationEntryType?'selected':''}>${e(c.name)}</option>`).join('')}</select></label><label class="field"><span>인증번호</span><input id="editor-cert-code" data-cert-entry="code" aria-label="추가할 인증번호" value="${e(certificationEntryCode)}" placeholder="인증기관에서 발급받은 번호"></label><button type="button" class="btn" data-add-cert>추가</button></div><p id="editor-cert-error" class="form-error" role="alert" hidden></p><p class="product-meta">유형과 번호를 입력해 하나씩 추가하세요. 번호의 진위를 확인하는 기능은 제공하지 않습니다.</p>`;
        const documentControls=rules?.documents.length?`<div class="coupang-document-controls"><label for="editor-document-type">서류 유형</label><select id="editor-document-type" aria-label="서류 유형"><option value="">서류 유형 선택</option>${rules.documents.map(d=>`<option value="${e(d.name)}">${e(d.name)}</option>`).join('')}</select><button type="button" class="btn" data-add-document>서류 추가</button></div>`:'<p class="product-meta">추가 가능한 서류 유형은 카테고리 규격 조회 후 표시됩니다.</p>';
        $('editor-document-fields').innerHTML=documentControls+`<div class="coupang-document-list">${draft.documents.map((d,n)=>`<div class="coupang-document-entry">${field(`documents.${n}.path`,d.templateName||'서류 주소','text',d.path||d.vendorPath,rules?.documents.some(r=>r.name===d.templateName&&M.requiredDocument(r,draft)))}<button type="button" class="btn small" data-view-document="${n}" ${M.url(d.path||d.vendorPath)?'':'disabled'}>보기</button><button type="button" class="btn small" data-remove-document="${n}" aria-label="${e(d.templateName||'서류')} 삭제">×</button></div>`).join('')||'<p class="empty">추가한 구비서류가 없습니다.</p>'}</div>`;
        const limit=purchaseLimitMode??(draft.options.some(v=>String(v.registration.maximumBuyForPerson??'')!==String(o.registration.maximumBuyForPerson??''))?'':o.registration.maximumBuyForPerson==null?'':Number(o.registration.maximumBuyForPerson)===0?'OFF':'ON');
        const period=salePeriodMode??(!draft.settings.saleEndedAt?'':draft.settings.saleEndedAt.startsWith('2099-')?'OFF':'ON');
        const registrationChoice=(k,label,pairs)=>choices(p+'.registration.'+k,label,pairs,o.registration[k]);
        $('editor-common-settings-fields').innerHTML='';
        $('editor-settings-fields').innerHTML=configured('settings',['manufacture'],draft.settings)+bundle+certChoices+(certMixed?'<p class="product-meta">옵션별 인증정보가 다릅니다. 선택하면 모든 옵션에 적용됩니다.</p>':'')+(certMode==='TARGET'?row('인증 상세',certificationControls+certificationFields):certificationFields?row('인증 상세',grid(certificationFields)):'')
            +registrationChoice('parallelImported','병행수입',[['PARALLEL_IMPORTED','병행수입'],['NOT_PARALLEL_IMPORTED','병행수입 아님']])
            +registrationChoice('adultOnly','구매 연령',[['EVERYONE','전체 연령'],['ADULT_ONLY','성인 전용 (19세 이상)']])
            +choices(null,'인당 최대구매수량',[['ON','설정함'],['OFF','설정안함']],limit,false,'purchase-limit')
            +(limit==='OFF'?'':row('구매 제한 설정',grid(configured(p+'.registration',['maximumBuyForPerson','maximumBuyForPersonPeriod'],o.registration))))
            +choices(null,'판매기간',[['ON','설정함'],['OFF','설정안함']],period,false,'sale-period')
            +(period==='OFF'?'<p class="product-meta">기간 제한 없음 · API 판매 종료일은 2099년으로 설정합니다.</p>':row('판매 일시',grid(configured('settings',['saleStartedAt','saleEndedAt'],draft.settings))))
            +registrationChoice('taxType','부가세',[['TAX','과세'],['FREE','면세']])
            +`<details id="editor-extra-settings" class="coupang-additional"><summary>추가 등록 설정</summary>${grid(configured('settings',['extraInfoMessage'],draft.settings)+configured(p+'.registration',['unitCount','overseasPurchased','pccNeeded',...additional],o.registration)+extras.map(k=>field(p+'.registration.'+k,k,'text',o.registration[k],false,true)).join('')+Object.keys(draft.settings).filter(k=>!M.settingsKeys.includes(k)&&k!=='bundleInfo.bundleType'&&k!=='brandId').map(k=>field('settings.'+k,k,'text',draft.settings[k],false,true)).join(''))}</details>`;
        if(extraOpen)$('editor-extra-settings').open=true;
        $('editor-search-tags-fields').innerHTML=field(p+'.registration.searchTags','검색어','text',o.registration.searchTags)+`<small id="editor-tag-count" class="product-meta"></small><p id="editor-tag-limit" class="form-error" role="alert" hidden></p><div id="editor-tag-list" class="coupang-tag-list" aria-label="입력한 검색어"></div>`;renderTags();
        $('editor-search-filter-fields').innerHTML=attrs(o,0,'NONE')+o.attributes.map((a,n)=>!['NONE','EXPOSED'].includes(a.exposed)&&!M.productIdentity(a.name)?field(`${p}.attributes.${n}.value`,a.name||'미분류 속성','text',a.value,false,true):'').join('');
    }
    const sharedSections='#editor-settings [data-field],#editor-search-tags [data-field],#editor-search-filters [data-field],#editor-notices [data-field]';
    function markSharedFields(){
        document.querySelectorAll(sharedSections).forEach(input=>{
            const path=input.dataset.field;if(input.type==='radio'||!path.startsWith('options.'))return;
            const values=M.sharedValues(draft,path),mixed=values.some(v=>String(v??'')!==String(values[0]??''));
            if(mixed){input.value='';input.dataset.mixed='true';input.setAttribute('placeholder','옵션별 값 다름');if(path.endsWith('.registration.searchTags')){$('editor-tag-list').innerHTML='';$('editor-tag-count').textContent='옵션별 값 다름';$('editor-tag-limit').hidden=true;}if(input.tagName==='SELECT')input.options[0].textContent='옵션별 값 다름';}
        });
    }
    function updateField(input,path){
        if(path.endsWith('.registration.outboundShippingTimeDay')&&(shippingTimeMode||(draft.options.every(o=>String(o.registration.outboundShippingTimeDay??'')===String(draft.options[0].registration.outboundShippingTimeDay??''))?'COMMON':'OPTIONS'))==='COMMON'){
            for(const o of draft.options)o.registration.outboundShippingTimeDay=input.value;return;
        }
        if(input.closest('#editor-settings,#editor-search-tags,#editor-search-filters,#editor-notices')&&path.startsWith('options.'))M.setSharedValue(draft,path,input.value);
        else set(path,input.value);
        delete input.dataset.mixed;
    }
    function changed(){save?.changed();dirty=true;$('editor-validation').hidden=true;}
    function updateBusy(){
        const pending=!!draft?.options.some(o=>o.images.some(i=>i.pending||i.error||i.local));
        for(const control of $('marketplace-editor-form').querySelectorAll('input,select,textarea,button')){
            if(saveBusy){if(!control.dataset.registrationDisabled)control.dataset.registrationDisabled=control.disabled?'yes':'no';control.disabled=true;}
            else if(control.dataset.registrationDisabled){control.disabled=control.dataset.registrationDisabled==='yes';delete control.dataset.registrationDisabled;}
        }
        $('editor-category-load').disabled=ruleLoading||loading||saveBusy;$('editor-validate').disabled=ruleLoading||loading||saveBusy||pending;
        $('editor-registration-temporary').disabled=ruleLoading||loading||saveBusy||pending;
    }
    let appliedCategory='';
    function syncRegistrationNames(){if(mode!=='new')return;for(const o of draft.options)o.itemName=M.registrationOptionName(o);for(const control of document.querySelectorAll('[data-field$=".itemName"]'))control.value=get(control.dataset.field);syncOptionPickers();}

    async function category(){
        if(ruleLoading||!draft)return;
        const code=String(draft.basic.displayCategoryCode||'').trim();
        if(!/^\d{1,15}$/.test(code)){$('editor-category-status').textContent='유효한 카테고리 코드를 입력해 주세요.';return;}
        const seq=++ruleSequence;ruleLoading=true;updateBusy();if(mode!=='edit')$('editor-category-status').textContent='카테고리 규격 조회 중…';
        try{
            categoryRead=startRead('/api/marketplaces/coupang/categories/'+encodeURIComponent(code)+'/rules');
            const data=await categoryRead.promise;
            if(seq!==ruleSequence||draft.basic.displayCategoryCode!==code)return;
            if(data.categoryCode!==code||!Array.isArray(data.attributes)||!Array.isArray(data.notices)||!Array.isArray(data.certifications)||!Array.isArray(data.documents))throw Error('카테고리 규격을 확인할 수 없습니다.');
            if(mode==='new'){const removed=M.categoryRemovals(draft,data);if(removed.length&&!window.confirm('새 카테고리에서 제외되는 입력: '+removed.join(', ')+'\n이 항목을 제외하고 카테고리를 변경할까요?')){draft.basic.displayCategoryCode=appliedCategory;render();$('editor-category-status').textContent='카테고리 변경을 취소했습니다.';return;}M.replaceRules(draft,data);appliedCategory=code;changed();}else M.applyRules(draft,data);rules=data;render();if(mode!=='edit')$('editor-category-status').textContent='카테고리 규격 조회 완료';
        }catch(err){if(seq===ruleSequence){if(mode==='edit'){$('editor-load-error').textContent=err.message;$('editor-load-error').hidden=false;}else $('editor-category-status').textContent=err.message;}}
        finally{if(seq===ruleSequence){categoryRead=null;ruleLoading=false;updateBusy();}}
    }
    async function initialize(){
        if(loading)return;const seq=++loadSequence;loading=true;$('editor-loading').hidden=false;$('editor-load-error').hidden=true;$('editor-retry').hidden=true;
        try{
            if(mode==='edit'){
                editRead=startRead('/api/marketplaces/coupang/products/'+encodeURIComponent(editMatch[1])+'/edit-observation');
                const observation=await editRead.promise,data=observation.document;
                if(seq!==loadSequence)return;
                if(data.basic?.sellerProductId!==editMatch[1]||!data.limits||!Array.isArray(data.options)||!data.options.length)throw Error('편집 데이터를 확인할 수 없습니다.');
                draft=M.fromDocument(data);save.observe(observation,draft);
            }else {
                draft=await save.load()||M.fresh();
                if(!new URLSearchParams(location.search).get('draftId')){
                    Object.assign(draft.delivery,{deliveryMethod:'SEQUENCIAL',deliveryCompanyCode:'CJGLS',deliveryChargeType:'FREE',deliveryCharge:'0',freeShipOverAmount:'0',deliveryChargeOnReturn:'0',returnCharge:'0',remoteAreaDeliverable:'N',unionDeliveryType:'NOT_UNION_DELIVERY'});
                    const now=new Date();Object.assign(draft.settings,{saleStartedAt:new Date(now.getTime()-now.getTimezoneOffset()*60000).toISOString().slice(0,19),saleEndedAt:'2099-12-31T23:59:59','bundleInfo.bundleType':'SINGLE'});
                    Object.assign(draft.options[0].registration,{maximumBuyForPerson:'0',maximumBuyForPersonPeriod:'1',unitCount:'1',outboundShippingTimeDay:'1',parallelImported:'NOT_PARALLEL_IMPORTED',overseasPurchased:'NOT_OVERSEAS_PURCHASED',pccNeeded:'false',adultOnly:'EVERYONE',taxType:'TAX'});
                }
                appliedCategory=draft.basic.displayCategoryCode;
                $('editor-registration-temporary').hidden=false;$('editor-registration-approval').hidden=false;$('editor-save-help').textContent='신규 상품 등록 요청을 전송합니다. 승인 요청 여부를 확인해 주세요.';$('editor-save-title').textContent='신규 등록 내용 확인';
            }
            render();dirty=false;if(mode==='edit'){$('editor-import-common').hidden=false;$('editor-validate').textContent='저장';}
        }catch(err){if(seq===loadSequence){$('editor-load-error').textContent=err.message;$('editor-load-error').hidden=false;$('editor-retry').hidden=false;}}
        finally{if(seq===loadSequence){editRead=null;loading=false;$('editor-loading').hidden=true;if(draft)updateBusy();}}
        if(draft&&seq===loadSequence&&(mode==='edit'||draft.basic.displayCategoryCode)){await new Promise(resolve=>setTimeout(resolve,1100));if(seq===loadSequence){await category();if(seq===loadSequence)await loadOutboundSummary(seq);}}
    }
    $('marketplace-editor-form').addEventListener('input',ev=>{
        if(ev.target.dataset.certEntry){if(ev.target.dataset.certEntry==='type')certificationEntryType=ev.target.value;else certificationEntryCode=ev.target.value;return;}
        const path=ev.target.dataset.field;if(!path||ev.target.disabled||(ev.target.type==='radio'&&!ev.target.checked))return;updateField(ev.target,path);changed();
        if(mode==='new'&&path.includes('.attributes.'))syncRegistrationNames();
        if(path==='basic.displayCategoryCode'){ruleSequence++;categoryRead?.cancel();categoryRead=null;ruleLoading=false;rules=null;updateBusy();$('editor-category-status').textContent='카테고리 규격을 조회해 주세요.';}
        if(path==='basic.sellerProductName'&&mode==='edit')$('editor-title').textContent=ev.target.value||'쿠팡 상품 수정';
        if(path.endsWith('.registration.searchTags'))renderTags();
        if(path.endsWith('.itemName'))syncOptionPickers();
        if(path.includes('.notices.'))updateNoticeReference();
        if(['delivery.deliveryCharge','delivery.deliveryChargeOnReturn','delivery.returnCharge'].includes(path))returnTotal();
        if(path.startsWith('documents.')){const n=Number(path.split('.')[1]);const button=document.querySelector(`[data-view-document="${n}"]`);if(button)button.disabled=!M.url(ev.target.value);}

    });
    $('marketplace-editor-form').addEventListener('change',ev=>{
        if(ev.target.disabled)return;
        if(ev.target.id==='editor-notice-reference'){
            for(const o of draft.options)for(const notice of o.notices.filter(v=>v.category===o.noticeCategory)){
                if(ev.target.checked){if(notice.content!==noticeReference)noticeBackups.set(notice,notice.content);notice.content=noticeReference;}
                else {const previous=noticeBackups.get(notice);if(notice.content===noticeReference)notice.content=previous&&previous!==noticeReference?previous:'';noticeBackups.delete(notice);}
            }
            changed();renderNotices();markSharedFields();return;
        }

        if(ev.target.dataset.settingsAction==='shipping-time'&&ev.target.checked){shippingTimeMode=ev.target.value;renderDelivery();return;}
        if(ev.target.dataset.settingsAction&&ev.target.checked){settingAction(ev.target.dataset.settingsAction,ev.target.value);return;}
        if(ev.target.dataset.certEntry){if(ev.target.dataset.certEntry==='type')certificationEntryType=ev.target.value;else certificationEntryCode=ev.target.value;return;}
        const path=ev.target.dataset.field;if(path&&(ev.target.type!=='radio'||ev.target.checked)){updateField(ev.target,path);changed();if(path.endsWith('.noticeCategory')){for(const option of draft.options)M.addNotices(option,rules);renderNotices();markSharedFields();}}
        if(mode==='new'&&path?.includes('.attributes.'))syncRegistrationNames();
        if(path==='delivery.deliveryChargeType'){if(draft.delivery.deliveryChargeType==='FREE'){draft.delivery.deliveryCharge='0';draft.delivery.freeShipOverAmount='0';}else if(draft.delivery.deliveryChargeType!=='CONDITIONAL_FREE')draft.delivery.freeShipOverAmount='0';renderDelivery();}
        if(path&&rules&&(path.endsWith('.parallelImported')||path.endsWith('.overseasPurchased'))){M.applyRules(draft,rules);renderSettings();markSharedFields();}
        if(ev.target.id==='editor-image-file')files(ev.target.files);
    });
    $('editor-option-tabs').addEventListener('click',ev=>{
        const button=ev.target.closest('[data-option-tab]');if(button)selectOption(Number(button.dataset.optionTab));
    });
    $('editor-option-tabs').addEventListener('keydown',ev=>{
        const button=ev.target.closest('[data-option-tab]');if(!button||!['ArrowLeft','ArrowRight','Home','End'].includes(ev.key))return;
        ev.preventDefault();const n=Number(button.dataset.optionTab),last=draft.options.length-1;
        selectOption(ev.key==='Home'?0:ev.key==='End'?last:(n+(ev.key==='ArrowRight'?1:-1)+draft.options.length)%draft.options.length);
        $('editor-option-tab-'+selected).focus();
    });
    $('editor-import-common').addEventListener('click',async()=>{
        if(mode!=='edit'||loading||importRead)return;if(dirty&&!window.confirm('저장하지 않은 입력 대신 쿠팡의 현재 조회값으로 공통 초안을 가져올까요?'))return;
        const button=$('editor-import-common');button.disabled=true;$('editor-load-error').hidden=true;
        try{importRead=startRead('/api/marketplaces/drafts/import/coupang/'+encodeURIComponent(editMatch[1]),{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const saved=await importRead.promise;if(!saved?.id)throw Error('가져온 초안을 확인해 주세요.');dirty=false;location.assign('/marketplaces/products/'+encodeURIComponent(saved.id)+'/edit');}
        catch(err){$('editor-load-error').textContent=err.message;$('editor-load-error').hidden=false;}finally{importRead=null;button.disabled=false;}
    });
    $('editor-category-load').addEventListener('click',category);$('editor-retry').addEventListener('click',initialize);
    $('editor-option-add').addEventListener('click',()=>{if(mode!=='new')return;draft.options.push(M.inheritShared(draft,M.blankOption()));if(rules)M.applyRules(draft,rules);syncRegistrationNames();changed();render();});
    $('marketplace-editor-form').addEventListener('click',ev=>{
        if(ev.target.closest('.editor-file-label')&&!M.nextImageType(draft.options[selected].images)){ev.preventDefault();imageError('이미지가 최대 개수입니다. 대표 1개 · 추가 9개까지 등록할 수 있습니다. 기존 이미지를 삭제한 뒤 추가해 주세요.');return;}
        const target=ev.target.closest('button');if(!target)return;const data=target.dataset,o=draft.options[selected];
        if(data.addressBook){outboundRead?.cancel();addressKind=data.addressBook;$('editor-address-title').textContent=addressKind==='return'?'반품/교환지 선택':'출고지 선택';$('editor-address-dialog').showModal();loadAddressPage(1);return;}
        if(data.removeOption!==undefined&&mode==='new'&&draft.options.length>1){const n=Number(data.removeOption);draft.options[n].images.filter(i=>i.local).forEach(i=>release(i.url));draft.options.splice(n,1);selected=Math.min(selected,draft.options.length-1);changed();render();}
        if(data.addImage!==undefined){if(!M.nextImageType(o.images)){imageError('이미지가 최대 개수입니다. 대표 1개 · 추가 9개까지 등록할 수 있습니다. 기존 이미지를 삭제한 뒤 추가해 주세요.');return;}const url=M.url($('editor-image-url').value);if(!url){$('editor-image-error').textContent='유효한 HTTPS 이미지 주소를 입력해 주세요.';$('editor-image-error').hidden=false;return;}const type=M.nextImageType(o.images);if(!type){imageError('추가 이미지는 최대 9개입니다.');return;}o.images.push({url,type,order:o.images.length});changed();renderMedia();}
        if(data.imageRetry!==undefined){uploadImage(o,o.images[Number(data.imageRetry)]);return;}
        if(data.imageAction==='remove'){const n=Number(data.index),i=o.images[n];if(i.local)release(i.url);o.images.splice(n,1);o.images.forEach((v,k)=>v.order=k);changed();renderMedia();}
        if(data.addContent){o.contents.push({type:data.addContent,detailType:data.addContent==='IMAGE'?'IMAGE':'TEXT',content:''});changed();renderMedia();}
        if(data.removeContent!==undefined){o.contents.splice(Number(data.removeContent),1);changed();renderMedia();}
        if(data.addCert!==undefined){
            const type=$('editor-cert-type').value,code=$('editor-cert-code').value.trim(),rule=rules?.certifications.find(c=>c.type===type);
            const error=message=>{$('editor-cert-error').textContent=message;$('editor-cert-error').hidden=false;};
            if(!rule||['NOT_REQUIRED','PRESENTED_IN_DETAIL_PAGE'].includes(type)){error('인증 유형을 선택해 주세요.');return;}
            if(rule.dataType==='CODE'&&!code){error('인증번호를 입력해 주세요.');return;}
            if(draft.options[0].certifications.some(c=>c.type===type)){error('이미 추가된 인증 유형입니다. 아래 인증번호 입력칸에서 수정해 주세요.');return;}
            for(const option of draft.options){const existing=option.certifications.find(c=>c.type===type);if(existing)existing.code=code;else option.certifications.push({type,code,attachments:[]});}
            certificationEntryType='';certificationEntryCode='';changed();renderSettings();markSharedFields();
        }
        if(data.removeCert!==undefined){
            if(rules?.certifications.some(c=>c.type===data.removeCert&&c.required==='MANDATORY'))return;
            for(const option of draft.options)option.certifications=option.certifications.filter(c=>c.type!==data.removeCert);
            changed();renderSettings();markSharedFields();
        }
        if(data.removeTag!==undefined){const tags=String(draft.options[0].registration.searchTags||'').split(',').map(t=>t.trim()).filter(Boolean);tags.splice(Number(data.removeTag),1);M.setSharedValue(draft,'options.0.registration.searchTags',tags.join(', '));$('field-options-0-registration-searchTags').value=tags.join(', ');changed();renderTags();}
        if(data.viewDocument!==undefined){const d=draft.documents[Number(data.viewDocument)],url=M.url(d?.path||d?.vendorPath);if(url)window.open(url,'_blank','noopener,noreferrer');}
        if(data.removeDocument!==undefined){draft.documents.splice(Number(data.removeDocument),1);changed();renderSettings();markSharedFields();}
        if(data.addDocument!==undefined){const name=$('editor-document-type').value;if(name&&!draft.documents.some(d=>d.templateName===name)){draft.documents.push({templateName:name,path:''});changed();renderSettings();markSharedFields();}}
    });
    function imageError(message){$('editor-image-error').textContent=message;$('editor-image-error').hidden=false;}
    const imageLimitMessage=images=>'이미지 개수 제한을 확인해 주세요. 대표 1개 · 추가 9개'+(images.some(i=>i.type==='USED_PRODUCT')?' · 중고 상태 4개':'');
    let imageDrag=null,touchDrag=null;
    const imageArea=$('editor-media-fields');
    function clearDropPreview(){
        document.querySelectorAll('.image-drop-marker').forEach(el=>el.remove());
        document.querySelectorAll('.image-drop-target').forEach(el=>el.classList.remove('image-drop-target'));
    }
    function clearImageDrag(){
        clearDropPreview();
        document.querySelectorAll('.image-dragging,.image-drop-target').forEach(el=>el.classList.remove('image-dragging','image-drop-target'));
        touchDrag?.ghost?.remove();imageDrag=null;touchDrag=null;
    }
    function imageDestination(target,x,y){
        const group=target?.closest('[data-image-type]');if(!group||!imageArea.contains(group))return null;
        const cards=[...group.querySelectorAll('[data-image-index]')];
        // Gaps and wrapped rows resolve to the nearest card, using the same destination for preview and drop.
        const distance=card=>{const b=card.getBoundingClientRect();return Math.max(b.left-x,0,x-b.right)**2+Math.max(b.top-y,0,y-b.bottom)**2;};
        const card=target.closest('[data-image-index]')||cards.reduce((nearest,c)=>!nearest||distance(c)<distance(nearest)?c:nearest,null);let before=null;
        if(card){const box=card.getBoundingClientRect();before=x<box.x+box.width/2?Number(card.dataset.imageIndex):(cards[cards.indexOf(card)+1]?.dataset.imageIndex??null);if(before!==null)before=Number(before);}
        return {group,type:group.dataset.imageType,before};
    }
    function showDropPreview(dest){
        clearDropPreview();if(!dest)return;dest.group.classList.add('image-drop-target');
        const cards=[...dest.group.querySelectorAll('[data-image-index]')];
        const anchor=dest.before===null?cards.at(-1):cards.find(c=>Number(c.dataset.imageIndex)===dest.before);
        const marker=document.createElement('span');marker.className='image-drop-marker';marker.setAttribute('aria-hidden','true');
        marker.dataset.label=dest.type==='REPRESENTATION'?'대표 이미지로 변경':'여기에 놓기';
        const bounds=dest.group.getBoundingClientRect();
        if(anchor){const box=anchor.getBoundingClientRect();marker.style.left=(dest.before===null?box.right:box.left)-bounds.left-2+'px';marker.style.top=box.top-bounds.top+'px';marker.style.height=box.height+'px';}
        else{marker.classList.add('image-drop-marker-empty');}
        dest.group.append(marker);
    }
    function dropImage(destination){
        const drag=imageDrag;if(!drag||!destination||draft.options[selected]!==drag.option){clearImageDrag();return;}
        const from=drag.option.images.indexOf(drag.image),ok=M.moveImage(drag.option.images,from,destination.type,destination.before);
        clearImageDrag();if(ok){changed();renderMedia();}else if(from>=0&&destination.before!==from)imageError(imageLimitMessage(drag.option.images));
    }
    imageArea.addEventListener('dragstart',ev=>{
        const card=ev.target.closest('[data-image-index]');if(!card||ev.target.closest('button')){ev.preventDefault();return;}
        const option=draft.options[selected];imageDrag={option,image:option.images[Number(card.dataset.imageIndex)]};
        ev.dataTransfer.effectAllowed='move';ev.dataTransfer.setData('text/plain',card.dataset.imageIndex);card.classList.add('image-dragging');
    });
    imageArea.addEventListener('dragover',ev=>{
        if(!imageDrag)return;const dest=imageDestination(ev.target,ev.clientX,ev.clientY);showDropPreview(dest);if(!dest)return;
        ev.preventDefault();ev.dataTransfer.dropEffect='move';
    });
    imageArea.addEventListener('dragleave',ev=>{if(!imageArea.contains(ev.relatedTarget))clearDropPreview();});
    imageArea.addEventListener('drop',ev=>{if(!imageDrag)return;ev.preventDefault();dropImage(imageDestination(ev.target,ev.clientX,ev.clientY));});
    imageArea.addEventListener('dragend',clearImageDrag);
    imageArea.addEventListener('pointerdown',ev=>{
        if(ev.pointerType==='mouse'||ev.target.closest('button'))return;const card=ev.target.closest('[data-image-index]');if(!card)return;
        const option=draft.options[selected];touchDrag={pointer:ev.pointerId,x:ev.clientX,y:ev.clientY,card,option,image:option.images[Number(card.dataset.imageIndex)]};imageArea.setPointerCapture(ev.pointerId);
    });
    imageArea.addEventListener('pointermove',ev=>{
        if(!touchDrag||ev.pointerId!==touchDrag.pointer)return;
        if(!imageDrag&&Math.hypot(ev.clientX-touchDrag.x,ev.clientY-touchDrag.y)<8)return;
        ev.preventDefault();if(!imageDrag){imageDrag={option:touchDrag.option,image:touchDrag.image};touchDrag.card.classList.add('image-dragging');const ghost=touchDrag.card.cloneNode(true);ghost.classList.add('image-touch-ghost');ghost.removeAttribute('id');ghost.setAttribute('aria-hidden','true');document.body.append(ghost);touchDrag.ghost=ghost;}
        touchDrag.ghost.style.left=ev.clientX+12+'px';touchDrag.ghost.style.top=ev.clientY+12+'px';
        const dest=imageDestination(document.elementFromPoint(ev.clientX,ev.clientY),ev.clientX,ev.clientY);showDropPreview(dest);
        if(ev.clientY<60)window.scrollBy(0,-18);else if(ev.clientY>window.innerHeight-60)window.scrollBy(0,18);
    });
    imageArea.addEventListener('pointerup',ev=>{
        if(!touchDrag||ev.pointerId!==touchDrag.pointer)return;const dest=imageDestination(document.elementFromPoint(ev.clientX,ev.clientY),ev.clientX,ev.clientY);if(imageArea.hasPointerCapture(ev.pointerId))imageArea.releasePointerCapture(ev.pointerId);if(imageDrag)dropImage(dest);else clearImageDrag();
    });
    imageArea.addEventListener('pointercancel',()=>{if(touchDrag)clearImageDrag();});
    imageArea.addEventListener('keydown',ev=>{
        const card=ev.target.closest('[data-image-index]');if(!card||ev.target.closest('button')||!['ArrowLeft','ArrowRight','r','R','d','D'].includes(ev.key))return;
        ev.preventDefault();const o=draft.options[selected],from=Number(card.dataset.imageIndex),image=o.images[from];let type=image.type,before=null;
        if(ev.key.toLowerCase()==='r')type='REPRESENTATION';else if(ev.key.toLowerCase()==='d')type='DETAIL';else{const group=o.images.map((i,k)=>({i,k})).filter(v=>v.i.type===type),position=group.findIndex(v=>v.k===from);if(ev.key==='ArrowLeft'){if(position===0)return;before=group[position-1].k;}else{if(position===group.length-1)return;before=group[position+2]?.k??null;}}
        if(M.moveImage(o.images,from,type,before)){changed();renderMedia();document.querySelector(`[data-image-index="${o.images.indexOf(image)}"]`)?.focus();}else imageError(imageLimitMessage(o.images));
    });
    let previewTimer;
    $('editor-description-fields').addEventListener('input',ev=>{if(ev.target.dataset.field?.includes('.contents.')){clearTimeout(previewTimer);previewTimer=setTimeout(previews,300);}});
    function release(url){URL.revokeObjectURL(url);localUrls.delete(url);}
    async function uploadImage(option,entry){
        if(!entry?.file)return;entry.pending=true;delete entry.error;if(draft.options[selected]===option||draft.options[selected]?.sellerProductItemId&&draft.options[selected].sellerProductItemId===option.sellerProductItemId)renderMedia();
        try{
            const body=new FormData();body.append('file',entry.file);const asset=await apiRequest('/api/marketplaces/assets',{method:'POST',body});
            if(!asset?.id||!asset.url)throw Error('이미지 업로드 응답을 확인해 주세요.');
            if(!option.images.includes(entry))return;
            release(entry.url);entry.url=asset.url;entry.assetId=asset.id;entry.local=false;delete entry.file;
        }catch(err){if(option.images.includes(entry))entry.error=err.message;}
        finally{entry.pending=false;updateBusy();if(draft.options[selected]===option||draft.options[selected]?.sellerProductItemId&&draft.options[selected].sellerProductItemId===option.sellerProductItemId)renderMedia();}
    }
    function files(list){
        const o=draft.options[selected];let rejected=false,full=false;
        for(const file of Array.from(list||[])){
            if(!['image/jpeg','image/png'].includes(file.type)||file.size>3*1024*1024){rejected=true;continue;}
            const type=M.nextImageType(o.images);if(!type){full=true;break;}
            const url=URL.createObjectURL(file);localUrls.add(url);const entry={url,local:true,pending:true,type,order:o.images.length,file};o.images.push(entry);
            const img=new Image();img.onload=()=>{if(img.naturalWidth!==img.naturalHeight||img.naturalWidth<500||img.naturalWidth>5000){entry.pending=false;entry.error='이미지는 500~5000px 정사각형이어야 합니다.';if(draft.options[selected]===o)renderMedia();}else uploadImage(o,entry)};img.onerror=()=>{entry.pending=false;entry.error='이미지 파일을 읽을 수 없습니다.';if(draft.options[selected]===o)renderMedia();};img.src=url;
        }
        changed();renderMedia();if(full)imageError('추가 이미지는 최대 9개입니다.');if(rejected){$('editor-image-error').textContent='3MiB 이하 JPG·PNG 파일을 선택해 주세요.';$('editor-image-error').hidden=false;}
    }
    $('marketplace-editor-form').addEventListener('submit',ev=>{
        ev.preventDefault();if(loading||ruleLoading||saveBusy||!draft)return;
        const errors=M.validate(draft,rules,mode),box=$('editor-validation');
        document.querySelectorAll('[aria-invalid]').forEach(el=>el.removeAttribute('aria-invalid'));document.querySelectorAll('[data-error-for]').forEach(el=>el.hidden=true);
        box.hidden=false;box.className=errors.length?'editor-validation form-error':'editor-validation editor-validation-success';
        box.textContent=errors.length?`${errors.length}개 항목을 확인해 주세요. ${errors[0].message}`:'입력 검증 완료';
        if(!errors.length){if(save)save.prepare();else box.focus();return;}
        const first=errors[0],match=/^options\.(\d+)/.exec(first.path);
        if(match)selectOption(Number(match[1]));
        for(const error of errors){const input=document.querySelector(`[data-field="${CSS.escape(error.path)}"]`);if(input)input.setAttribute('aria-invalid','true');const hint=document.querySelector(`[data-error-for="${CSS.escape(error.path)}"]`);if(hint){hint.textContent=error.message;hint.hidden=false;}}
        const input=document.querySelector(`[data-field="${CSS.escape(first.path)}"]`);
        const section=input?.closest('.coupang-form-section')?.id||sectionFor(first.path);
        if(input)for(let parent=input.parentElement;parent;parent=parent.parentElement)if(parent.tagName==='DETAILS')parent.open=true;
        $(section).scrollIntoView({behavior:'smooth',block:'start'});if(input&&!input.disabled)input.focus({preventScroll:true});else box.focus({preventScroll:true});
    });
    function sectionFor(path){
        if(path.startsWith('delivery.'))return returnKeys.includes(path.slice(9))?'editor-returns':'editor-delivery';
        if(['delivery.deliveryCharge','delivery.deliveryChargeOnReturn','delivery.returnCharge'].includes(path))returnTotal();
        if(path.startsWith('documents.'))return 'editor-documents';
        if(path.startsWith('settings.')||path.includes('.certifications.'))return 'editor-settings';
        if(path.includes('.images'))return 'editor-media';if(path.includes('.contents'))return 'editor-description';if(path.includes('.notices')||path.endsWith('.noticeCategory'))return 'editor-notices';
        if(path.endsWith('.registration.searchTags'))return 'editor-search-tags';
        if(path.includes('.attributes.'))return get(path.replace(/\.value$/,'.exposed'))==='EXPOSED'?'editor-option-table-section':'editor-search-filters';
        if(path.includes('.registration.'))return saleKeys.some(k=>path.endsWith('.'+k))?'editor-option-table-section':'editor-settings';
        if(path.startsWith('options'))return 'editor-option-table-section';return 'editor-basic';
    }
    document.querySelectorAll('[data-editor-exit]').forEach(el=>el.addEventListener('click',ev=>{ev.preventDefault();if(dirty&&!window.confirm('입력한 내용을 버리고 목록으로 돌아갈까요?'))return;dirty=false;location.assign('/marketplaces');}));
    window.addEventListener('beforeunload',ev=>{if(dirty){ev.preventDefault();ev.returnValue='';}});
    window.addEventListener('pagehide',()=>{clearImageDrag();addressSequence++;addressRead?.cancel();outboundRead?.cancel();loadSequence++;ruleSequence++;editRead?.cancel();categoryRead?.cancel();importRead?.cancel();clearTimeout(previewTimer);for(const url of localUrls)URL.revokeObjectURL(url);localUrls.clear();});
    $('marketplace-editor-form').addEventListener('error',ev=>{if(ev.target.tagName==='IMG'){ev.target.hidden=true;const fallback=document.createElement('span');fallback.textContent='이미지 없음';ev.target.parentElement.append(fallback);}},true);
    initialize();
})();
