(() => {
    'use strict';
    const {$,escape:e}=AppUI,M=window.MarketplaceEditorModel,C=window.CoupangEditorModel,N=window.CommonNaverEditorModel,NM=window.NaverEditorModel,F=window.CoupangProductForm;
    const edit=/^\/marketplaces\/products\/([^/]+)\/edit$/.exec(location.pathname);
    const entryData=$('main-content').dataset;
    let entry=null,sendMarkets=null,linkCandidate=null,linkMode=null,linkSequence=0,linkPage=0,linkPages=0;
    const unified=!!entryData.editorMode;
    const standalone=()=>!!entry?.originMarket;
    const allowed=market=>!entry||entry.activeTabs.includes(market||'');
    const nativeNew=()=>standalone()&&!entry.commonId&&activeTarget()?.mode==='CREATE';
    let draft=null,reference=null,editState=null,viewMarket=null,referenceDirty=false,dirty=false,busy=false,selectedMedia=null,selectedMarket='COUPANG',selectedMarketOption=null,sequence=0,categorySequence=0,categoryRead=null,validationRead=null,validationSequence=0,sessionRead=null,expiryTimer=null,loading=false;
    let naverForm=null,naverLast=null,naverMounted=null,naverState={blocked:false,uploading:false,categoryLoading:false,unsupportedNotice:false};
    const rules=new Map(),ruleErrors=new Map(),uploads=new Set(),localTargets=new Map();
    const viewMarkets=M.channels.map(c=>[c.id,c.label]);
    function syncNewTargets(){if(!reference)return;if(editState)M.syncCreateTargets(editState,reference);if(!reference.id){if(!reference.markets.COUPANG.coupang?.sellerProductId)localTargets.set('COUPANG',M.createTarget(reference,'COUPANG',localTargets.get('COUPANG')));localTargets.set('NAVER',M.createTarget(reference,'NAVER',localTargets.get('NAVER')));localTargets.set('ELEVENST',M.createTarget(reference,'ELEVENST',localTargets.get('ELEVENST')));localTargets.set('LOTTEON',M.createTarget(reference,'LOTTEON',localTargets.get('LOTTEON')));}}
    function hasChanges(){return referenceDirty||[...(editState?.targets.values()||[]),...localTargets.values()].some(t=>t.changes.size||t.createOverrides?.size);}
    const submissionMarket=()=>viewMarket||((reference?.selectedMarkets.length===1)?reference.selectedMarkets[0]:null);
    const submissionTarget=()=>{const market=submissionMarket();return editState?.targets.get(market)||localTargets.get(market);};
    const submissions=window.MarketplaceSubmissionUI.create({beforePrepare:()=>{if(viewMarket)return true;showSaveTargets();return false;},canAct:()=>!viewMarket?!busy&&!loading&&!categoryRead&&!uploads.size&&!naverState.uploading&&!naverState.categoryLoading&&!!reference&&saveTargetMarkets().length>0:!busy&&!loading&&!categoryRead&&!uploads.size&&!naverState.uploading&&!!reference&&['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(submissionMarket())&&(sendMarkets||new Set(reference.selectedMarkets)).has(submissionMarket())&&(!reference.id||!!editState&&!expired())&&submissionTarget()?.status==='READY'&&!submissionTarget()?.reviewRequired.size&&(submissionMarket()!=='NAVER'||!naverState.blocked&&!naverState.unsupportedNotice),save:()=>save(true),prepare:()=>{$('common-validation').hidden=true;return {sessionId:editState.session.id,targets:M.proposals(editState,[submissionMarket()])};},summary:()=>{const input=submissionTarget()?.working?.markets.NAVER.naver?.editorInput;return input?'<p class="product-meta">'+e(input.fields[NM.P+'name']||'이름 없는 상품')+' · 옵션 '+(input.optionMode==='COMBINATION'?input.options.length:0)+'개 · 기본가 '+e(input.fields[NM.P+'salePrice']??'미입력')+'원</p>':'';},requested:()=>submissionMarket()==='COUPANG',busy:value=>{busy=value;updateBusy();}});
    function saveTargetMarkets(){return [...(sendMarkets||new Set(reference?.selectedMarkets||[]))].filter(m=>['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(m)&&allowed(m));}
    function showSaveTargets(){
        const dialog=document.createElement('dialog');dialog.className='common-submission-dialog';dialog.setAttribute('aria-labelledby','common-save-target-title');
        dialog.innerHTML=`<div class="common-subheading"><h2 id="common-save-target-title">저장할 마켓 선택</h2><button type="button" class="btn" data-save-target-close>닫기</button></div><p>한 번에 한 마켓씩 저장합니다. 해당 탭에서 입력을 확인한 후 저장 버튼을 눌러 주세요.</p><div class="common-row">${saveTargetMarkets().map(m=>`<button type="button" class="btn" data-save-target="${m}">${e(marketName(m))} 탭에서 확인</button>`).join('')}</div>`;
        document.body.append(dialog);const close=()=>{dialog.close();dialog.remove();};dialog.querySelector('[data-save-target-close]').onclick=close;dialog.oncancel=event=>{event.preventDefault();close();};dialog.querySelectorAll('[data-save-target]').forEach(b=>b.onclick=()=>{close();chooseView(b.dataset.saveTarget);});dialog.showModal();
    }
    const marketName=key=>M.channels.find(c=>c.id===key)?.label||key;
    const S=window.MarketplaceCommonSettings;
    const commonSpec={productCode:['상품코드'],name:['상품명'],productName:['제품명'],brand:['브랜드'],manufacturer:['제조사'],origin:['제조국·원산지'],material:['소재'],model:['모델명'],afterService:['A/S 안내','textarea'],afterServiceManager:['A/S 책임자'],afterServiceTelephone:['A/S 연락처','tel'],saleStartedAt:['판매 시작일시','datetime-local'],saleEndedAt:['판매 종료일시','datetime-local'],taxType:['과세 정보','select',['TAX','FREE']],adultOnly:['성인 상품','select',['EVERYONE','ADULT_ONLY']]};
    const deliverySpec={method:['배송 방식','select',['SEQUENCIAL','COLD_FRESH','MAKE_ORDER','AGENT_BUY','VENDOR_DIRECT']],carrier:['택배사','select',Object.keys(S.carriers)],chargeType:['배송비 유형','select',['FREE','NOT_FREE','CONDITIONAL_FREE','CHARGE_RECEIVED']],charge:['배송비','number'],freeOver:['무료배송 기준','number'],returnCharge:['회수 배송비(편도, 원)','number'],initialReturnCharge:['초도 배송비(편도, 원)','number'],remoteArea:['도서산간 배송','select',['Y','N']],bundle:['묶음 배송','select',['UNION_DELIVERY','NOT_UNION_DELIVERY']],outboundCode:['출고지 코드'],returnCode:['반품지 코드'],returnName:['반품지명'],returnContact:['반품 연락처'],returnZip:['반품 우편번호'],returnAddress:['반품 주소'],returnAddressDetail:['반품 상세 주소'],shippingDays:['출고 소요일(일)','number'],jejuCharge:['제주 추가 배송비(원)','number'],remoteCharge:['제주 외 도서산간 추가 배송비(원)','number']};
    const names={...S.carriers,TAX:'과세',FREE:'무료·면세',EVERYONE:'전체 연령',ADULT_ONLY:'성인 전용',SINGLE:'동일 상품으로 구성',AB:'혼합 구성',SEQUENCIAL:'일반배송',COLD_FRESH:'신선·냉동',MAKE_ORDER:'주문제작',AGENT_BUY:'구매대행',VENDOR_DIRECT:'판매자 직접 배송',NOT_FREE:'유료',CONDITIONAL_FREE:'조건부 무료',CHARGE_RECEIVED:'착불',Y:'가능',N:'불가',UNION_DELIVERY:'묶음 배송',NOT_UNION_DELIVERY:'개별 배송',true:'예',false:'아니요',ON:'노출 중',SALE:'판매 중',SUSPENSION:'판매 중지',NEW:'새 상품',OLD:'중고 상품',PARALLEL_IMPORTED:'병행 수입',NOT_PARALLEL_IMPORTED:'해당 없음',OVERSEAS_PURCHASED:'해외 구매',NOT_OVERSEAS_PURCHASED:'해당 없음'};
    const fieldId=path=>'common-field-'+path.replace(/[^a-zA-Z0-9_-]/g,'-');
    const get=path=>path.split('.').reduce((o,key)=>o?.[key],draft);
    function set(path,value){const keys=path.split('.'),last=keys.pop();const parent=keys.reduce((o,key)=>o[key]??=(/^\d+$/.test(key)?[]:{}),draft);parent[last]=path==='markets.NAVER.naver.naverShoppingRegistration'?(value===''||value===null?null:value===true||value==='true'):value;}
    function field(path,label,type='text',value='',choices=[],readonly=false,required=false){
        const target=activeTarget(),optionMatch=/^options\.(\d+)\.(price|quantity)$/.exec(path);
        if(target?.mode==='UPDATE'&&['common.productCode','common.brand','common.origin','common.material','common.afterService','stockMode','productQuantity'].includes(path))readonly=true;
        if(target?.mode==='UPDATE'&&optionMatch&&(value===null||value===undefined||value===''))readonly=true;
        const attrs=`id="${e(fieldId(path))}" data-common-field="${e(path)}" ${readonly?'disabled':''} ${required?'aria-required="true"':''}`;
        const plainNumber=type==='number'&&(/^(?:options\.\d+\.(?:originalPrice|price|quantity)|productQuantity)$/.test(path)||/\.value-(?:originalPrice|salePrice|maximumBuyCount)$/.test(path));
        const choiceLabel=v=>(path==='common.taxType'||path.endsWith('value-taxType'))&&['FREE','DUTYFREE'].includes(v)?'면세':path==='delivery.carrier'?(C.courierNames[v]||v):(viewMarket==='COUPANG'?(C.choiceNames[v]||v):(names[v]||v));
        const control=type==='textarea'?`<textarea ${attrs} rows="4">${e(value??'')}</textarea>`:type==='select'?`<select ${attrs}><option value="">선택</option>${[...new Set([...choices,...(value!=null&&value!==''?[String(value)]:[])])].map(v=>`<option value="${e(v)}" ${String(value)===v?'selected':''}>${e(choiceLabel(v))}</option>`).join('')}</select>`:`<input ${attrs} type="${plainNumber?'text':type}" ${plainNumber?'inputmode="numeric" data-plain-number="true"':type==='number'?'min="0" step="1" inputmode="numeric"':type==='datetime-local'?'step="1"':''} value="${e(value??'')}">`;
        const radio=viewMarket==='COUPANG'&&type==='select'&&(/\.value-(?:parallelImported|adultOnly|taxType|bundleInfo\.bundleType)$/.test(path)||['delivery.remoteArea','delivery.bundle'].includes(path));
        if(radio)return F.choices({label,value,pairs:[...new Set([...choices,...(value?[String(value)]:[])])].map(v=>[v,choiceLabel(v)]),disabled:readonly,attrs:(v,n)=>`${attrs.replace('id="'+e(fieldId(path))+'"','id="'+e(fieldId(path))+'-'+n+'"')} name="${e(fieldId(path))}"`});
        const caption=`${e(label)}${required?' <span class="editor-required" aria-label="필수">*</span>':''}`;
        return viewMarket==='COUPANG'?F.field({caption,control,wide:type==='textarea'}):`<label class="field ${type==='textarea'?'editor-wide':''}"><span>${caption}</span>${control}</label>`;
    }
    const activeTarget=()=>viewMarket?editState?.targets.get(viewMarket)||localTargets.get(viewMarket)||(viewMarket!=='COUPANG'?{market:viewMarket,mode:'CREATE',status:'UNSUPPORTED',changes:new Map(),reviewRequired:new Map()}:null):null;
    const expired=()=>!!editState&&Date.parse(editState.session.expiresAt)<=Date.now();
    function configureCoupangLayout(){
        const coupang=viewMarket==='COUPANG',naver=viewMarket==='NAVER',form=$('common-editor-form');
        $('main-content').classList.toggle('coupang-product-form',coupang||naver);
        $('main-content').classList.toggle('naver-product-form',naver);
        $('common-media-option').closest('label').hidden=coupang;
        for(const key of ['common-basic','common-media','common-notice','common-markets','common-delivery'])$(key).hidden=naver||(key==='common-notice'&&coupang);
        if(coupang||naver)$('common-notice-fields').replaceChildren();
        $('common-naver-host').hidden=!naver;$('common-naver-controls').hidden=!naver;
        const sections=coupang?['common-basic','common-media','common-markets','common-delivery']:['common-basic','common-media','common-notice','common-delivery','common-markets'];
        for(const section of sections)form.insertBefore($(section),$('common-validation'));
        $('common-basic').querySelector('.common-subheading').hidden=coupang;

    }
    function changed(path=null){validationSequence++;validationRead?.cancel();if(viewMarket&&activeTarget()?.status!=='UNSUPPORTED'){const target=activeTarget();if(path&&target?.working)M.recordChange(target,M.changeForPath(draft,path));}else {referenceDirty=true;syncNewTargets();}dirty=hasChanges();$('common-validation').hidden=true;updateHeading();submissions.changed();renderLiveState();}
    function updateHeading(){const imported=reference?.markets.COUPANG.coupang?.sellerProductId;$('common-editor-title').textContent=reference?.common.name||'상품 등록';$('common-source').hidden=!imported;$('common-source').textContent=imported?'쿠팡 연결 · 등록상품 '+imported:'';if($('common-link-actions')){$('common-link-actions').hidden=!entry?.token||!!entry.commonId;$('common-link-actions').querySelectorAll('button').forEach(b=>b.disabled=busy||loading);}}
    function updateBusy(){const imageState=$('common-upload-state');if(imageState){imageState.textContent=uploads.size?'R2 이미지 확인 중…':'';imageState.hidden=!uploads.size;}for(const control of [...$('common-editor-form').querySelectorAll('button,input,select,textarea'),...$('common-market-selection').querySelectorAll('input')]){if(control.dataset.locked!=='true'){if(busy){if(!control.dataset.wasDisabled)control.dataset.wasDisabled=control.disabled?'true':'false';control.disabled=true;}else if(control.dataset.wasDisabled){control.disabled=control.dataset.wasDisabled==='true';delete control.dataset.wasDisabled;}}}$('common-save').disabled=busy||loading||uploads.size>0||!!categoryRead||naverState.uploading||naverState.categoryLoading;$('common-validate').disabled=busy||uploads.size>0||!!categoryRead||!!viewMarket&&activeTarget()?.status!=='READY';$('common-editor-form').setAttribute('aria-busy',String(busy));document.querySelectorAll('[data-view-market]').forEach(button=>button.disabled=!allowed(button.dataset.viewMarket)||busy||loading||uploads.size>0||naverState.uploading||naverState.categoryLoading);const refresh=document.querySelector('[data-live-refresh]');if(refresh)refresh.disabled=busy||loading||uploads.size>0||activeTarget()?.status==='UNSUPPORTED'||(!reference?.id&&!entry?.token);if(lotteonForm)lotteonForm.setBusy(busy||loading||viewMarket==='LOTTEON'&&activeTarget()?.status!=='READY');if(elevenstForm)elevenstForm.setBusy(busy||loading||viewMarket==='ELEVENST'&&activeTarget()?.status!=='READY');if(naverForm)naverForm.setBusy(busy||loading||viewMarket==='NAVER'&&(activeTarget()?.status!=='READY'||expired()));submissions.update();}
    function markLocks(){document.querySelectorAll('#common-editor-form :disabled').forEach(el=>{if(!el.closest('#common-naver-host')&&!el.dataset.wasDisabled&&!['common-save','common-validate','common-market-save'].includes(el.id))el.dataset.locked='true';});}
    function renderBasic(){
        if(viewMarket==='COUPANG'){renderCoupangBasic();return;}
        $('common-basic-fields').innerHTML=`<div class="editor-grid">${(viewMarket?['productCode','name','productName','brand','model']:['productCode','name','brand']).map(key=>{const [label,type='text',choices=[]]=commonSpec[key];return field('common.'+key,viewMarket==='COUPANG'&&key==='name'?'등록상품명':label,type,viewMarket&&draft.markets[viewMarket]&&['name','brand'].includes(key)?M.resolved(reference,viewMarket,key):draft.common[key],choices,!!viewMarket&&key==='productCode',['name','productCode'].includes(key));}).join('')}${viewMarket==='COUPANG'?field('markets.COUPANG.overrides.productName','노출상품명','text',activeTarget()?.mode==='UPDATE'?(draft.markets.COUPANG.overrides.productName??''):(draft.markets.COUPANG.overrides.productName??draft.common.productName)):''}${viewMarket&&M.markets.some(([key])=>key===viewMarket)?field('markets.'+viewMarket+'.categoryCode','카테고리 코드','text',draft.markets[viewMarket].categoryCode,[],activeTarget()?.mode==='UPDATE',true):''}</div>`;
        if(!viewMarket){mountCommonBrand();mountCommonCategories();}
        const locked=!!viewMarket;
        $('common-option-fields').innerHTML=`<div class="common-row">${field('stockMode','수량 관리','select',draft.stockMode,['OPTION','PRODUCT'],!!viewMarket)}${draft.stockMode==='PRODUCT'?field('productQuantity','상품 공통 수량','number',draft.productQuantity,[],activeTarget()?.mode==='UPDATE'&&(draft.productQuantity==null||draft.productQuantity===''),true):''}</div>`+draft.options.map((o,n)=>`<article class="editor-option-card"><div class="editor-option-head"><h3>옵션 ${n+1}</h3><button type="button" class="btn small danger" data-remove-option="${e(o.id)}" ${locked||draft.options.length===1?'disabled':''}>삭제</button></div><div class="editor-grid common-option-inputs">${field('options.'+n+'.name','옵션명','text',o.name,[],false,true)}${field('options.'+n+'.sku','판매자 상품코드','text',o.sku)}${!viewMarket?field('options.'+n+'.originalPrice','정상가','number',o.originalPrice):''}${field('options.'+n+'.price','판매가','number',o.price,[],false,true)}${draft.stockMode==='OPTION'?field('options.'+n+'.quantity',viewMarket?'판매 수량':'기본 판매 수량','number',o.quantity,[],false,true):''}</div><h4>구매 속성</h4>${purchaseFields(o,n)}</article>`).join('')+(!viewMarket?`<h3>서비스 선택</h3>${draft.services.map((s,n)=>`<div class="common-row">${field('services.'+n+'.name','선택 그룹명','text',s.name)}${field('services.'+n+'.choices','선택지','text',(s.choices||[]).join(', '))}<button type="button" class="btn small danger" data-remove-service="${e(s.id)}">삭제</button></div>`).join('')}<button type="button" class="btn small" data-add-service>서비스 선택 추가</button>`:'');
        const stock=$('common-field-stockMode');if(stock){stock.querySelector('[value=OPTION]').textContent='옵션별 수량';stock.querySelector('[value=PRODUCT]').textContent='상품 공통 수량';}
        $('common-option-add').disabled=locked||!!viewMarket;
    }
    function updateCommonReturnTotal(){const host=document.querySelector('[data-common-return-total]');if(!host)return;const values=[draft.delivery.initialReturnCharge,draft.delivery.returnCharge];host.textContent=values.every(v=>/^\d+$/.test(v||''))?'초도 '+Number(values[0]).toLocaleString('ko-KR')+'원 + 회수 '+Number(values[1]).toLocaleString('ko-KR')+'원 = 왕복 반품 배송비 '+(Number(values[0])+Number(values[1])).toLocaleString('ko-KR')+'원':'편도 배송비 두 금액을 입력하면 왕복 반품 배송비 합계를 표시합니다.';}
    function purchaseFields(option,n){return pairs('options.'+n+'.attributes',option.attributes,!!viewMarket);}
    function pairs(path,rows,locked=false){return `<div>${(rows||[]).map((r,n)=>`<div class="common-map-row">${field(path+'.'+n+'.name','항목','text',r.name,[],locked)}${field(path+'.'+n+'.value','값','text',r.value,[],locked)}<button type="button" class="btn small" data-remove-pair="${e(path)}" data-index="${n}" ${locked?'disabled':''}>삭제</button></div>`).join('')}<button type="button" class="btn small" data-add-pair="${e(path)}" ${locked?'disabled':''}>항목 추가</button></div>`;}
    function selectedMediaOptions(){return `<option value="">공통</option>${draft.options.map(o=>`<option value="${e(o.id)}">${e(o.name||'이름 없는 옵션')}</option>`).join('')}`;}
    function renderMedia(){
        if(viewMarket==='COUPANG'){renderCoupangMedia();return;}
        $('common-media-option').innerHTML=selectedMediaOptions();$('common-media-option').value=selectedMedia||'';
        const imgs=M.imagesFor(draft,selectedMedia),contents=draft.media.contents.filter(c=>(c.optionId??null)===selectedMedia);
        $('common-media-fields').innerHTML=`<h3>이미지</h3><div class="editor-image-controls"><label class="field"><span>R2 이미지 주소</span><input id="common-image-url" type="url"></label><button type="button" class="btn" data-add-image>추가</button><button type="button" class="btn" data-r2-images>R2 이미지 조회</button></div><p class="product-meta">R2에 저장된 JPG·PNG 이미지를 선택해 주세요.</p><p id="common-image-error" role="alert" class="form-error" hidden></p><p id="common-upload-state" role="status" class="common-upload-status">${uploads.size?'R2 이미지 확인 중…':''}</p><div class="editor-image-grid">${imgs.map((i,n)=>`<figure><div class="editor-image-preview"><img src="${e(i.url)}" alt="${e((i.representative?'대표':'추가')+' 이미지 '+(n+1))}" referrerpolicy="no-referrer" loading="lazy"><span hidden>이미지 없음</span></div><figcaption>${i.representative?'대표':'추가'} · ${n+1}</figcaption><div class="editor-image-actions"><button type="button" class="btn small" data-edit-image="${e(i.id)}">편집</button><button type="button" class="btn small" data-image-action="represent" data-image-id="${e(i.id)}">대표</button><button type="button" class="btn small" data-image-action="up" data-image-id="${e(i.id)}" ${n===0?'disabled':''}>위</button><button type="button" class="btn small" data-image-action="down" data-image-id="${e(i.id)}" ${n===imgs.length-1?'disabled':''}>아래</button><button type="button" class="btn small danger" data-image-action="remove" data-image-id="${e(i.id)}">삭제</button></div></figure>`).join('')||'<p class="common-empty">등록된 이미지가 없습니다.</p>'}</div><h3>상세 설명</h3>${contents.map(c=>{const n=draft.media.contents.indexOf(c);return `<div class="editor-content"><div class="common-subheading"><strong>${c.type==='HTML'?'HTML 설명':'설명 이미지'}</strong><button type="button" class="btn small danger" data-remove-content="${e(c.id)}">삭제</button></div>${field('media.contents.'+n+'.value',c.type==='HTML'?'HTML 원문':'HTTPS 이미지 주소',c.type==='HTML'?'textarea':'url',c.value)}<div class="editor-preview" data-preview-id="${e(c.id)}"></div></div>`;}).join('')}<div class="common-row"><button type="button" class="btn small" data-add-content="HTML">HTML 설명 추가</button><button type="button" class="btn small" data-add-content="IMAGE">설명 이미지 추가</button></div>`;
        $('common-notice-fields').innerHTML=`<div class="editor-grid">${['material','origin','manufacturer'].map(k=>field('common.'+k,commonSpec[k][0],commonSpec[k][1]||'text',draft.common[k])).join('')}</div>`;
        FormActions.bindEnter($('common-image-url'),$('common-media-fields').querySelector('[data-add-image]'));
        previews();

    }
    function previews(){for(const host of document.querySelectorAll('[data-preview-id]')){const c=draft.media.contents.find(c=>c.id===host.dataset.previewId);if(!c)continue;host.innerHTML=c.type==='HTML'?`<iframe class="marketplace-html" title="상품 설명 미리보기" sandbox="" referrerpolicy="no-referrer" srcdoc="${e(C.preview(c.value))}"></iframe>`:(c.assetId||M.url(c.value))?`<img src="${e(c.value)}" alt="설명 이미지" referrerpolicy="no-referrer">`:'<p class="common-empty">이미지 없음</p>';host.insertAdjacentHTML('beforeend',c.type==='IMAGE'?`<button type="button" class="btn small" data-edit-content="${e(c.id)}">편집</button>`:window.ImageCropEditor?.htmlButtons(c.value,n=>`data-edit-content="${e(c.id)}" data-html-image="${n}"`)||'');}}
    function ownMedia(kind,row){
        if(viewMarket!=='COUPANG'||row.optionId)return row;
        const copy={...structuredClone(row),id:M.uuid(),optionId:selectedMedia,sourceId:row.id};draft.media[kind].push(copy);return copy;
    }
    async function editMedia(kind,id,htmlIndex){
        if(busy||loading||uploads.size||!window.ImageCropEditor)return;let row=draft.media[kind].find(v=>v.id===id);if(!row)return;
        const owner=draft,pending={};uploads.add(pending);updateBusy();
        try{const html=htmlIndex!=null,url=html?ImageCropEditor.htmlImages(row.value)[htmlIndex]?.url:kind==='images'?row.url:row.value;const assetId=html?(url?.match(/^\/api\/marketplaces\/assets\/([a-f0-9-]{36})$/i)?.[1]||null):row.assetId;const edited=await ImageCropEditor.open({url,assetId,square:viewMarket==='COUPANG'&&kind==='images'});if(!edited||draft!==owner)return;row=ownMedia(kind,row);if(html)row.value=ImageCropEditor.replaceHtml(row.value,htmlIndex,edited.url);else {row[kind==='images'?'url':'value']=edited.url;row.assetId=edited.assetId;}mediaChanged(kind,row.optionId??null);renderMedia();}
        catch(err){$('common-error').textContent=err.message;$('common-error').hidden=false;}
        finally{uploads.delete(pending);updateBusy();}
    }
    $('common-media-fields').addEventListener('click',event=>{const button=event.target.closest('[data-edit-image],[data-edit-content]');if(!button||button.disabled)return;void editMedia(button.dataset.editImage?'images':'contents',button.dataset.editImage||button.dataset.editContent,button.hasAttribute('data-html-image')?Number(button.dataset.htmlImage):null);});

    function mediaChanged(kind,optionId){if(viewMarket){if(viewMarket==='COUPANG')M.materializeMedia(draft,kind,optionId);const target=activeTarget();M.recordChange(target,{path:'media.'+kind,optionId:optionId??null});}changed();}
    const addressKeys=['outboundCode','returnCode','returnName','returnContact','returnZip','returnAddress','returnAddressDetail'];
    function renderDelivery(){
        if(viewMarket==='COUPANG'){renderCoupangDelivery();return;}
        $('common-delivery-fields').innerHTML=`${!viewMarket?'<div class="common-subheading"><h3>마켓별 출고지·반품/교환지</h3><button type="button" class="btn" data-common-address-search>주소록 조회·선택</button></div><div class="common-address-summary" data-common-address-summary></div>':''}${(viewMarket?[[null,M.deliveryKeys]]:[['배송',['method','carrier','bundle','shippingDays']],['배송비',['chargeType','charge','freeOver']],['도서산간 배송',['remoteArea','jejuCharge','remoteCharge']],['반품 배송비',['initialReturnCharge','returnCharge']]]).map(([title,keys])=>(title?'<h3>'+e(title)+'</h3>':'')+'<div class="editor-grid">'+keys.map(k=>{const [label,type='text',choices=[]]=deliverySpec[k];return field('delivery.'+k,label,type,draft.delivery[k],choices);}).join('')+'</div>').join('')}`;
        if(!viewMarket){
            const host=$('common-delivery-fields');host.insertAdjacentHTML('beforeend','<p class="product-meta" data-common-return-total></p><p class="product-meta">지역 추가비가 출고지·묶음배송 그룹·배송비 정책에 연결된 마켓은 선택한 정책과 비교합니다. 정책은 자동 변경하지 않습니다.</p>');updateCommonReturnTotal();
            host.querySelector('[data-common-address-summary]').innerHTML=['COUPANG','NAVER','ELEVENST','LOTTEON'].map(m=>{const book=reference.markets[m].addressBook;return `<div><strong>${e(marketName(m))}</strong> ${[['outbound','출고지'],['returns','반품/교환지']].map(([k,label])=>`${label}: ${book?.[k]?e(book[k].name)+' · '+e(book[k].code):'미선택'}`).join(' / ')}</div>`;}).join('');
            host.querySelector('[data-common-address-search]').onclick=()=>{
                if(busy||loading)return;
                window.CommonAddressPicker.open({selections:Object.fromEntries(['COUPANG','NAVER','ELEVENST','LOTTEON'].map(m=>[m,reference.markets[m].addressBook||{}])),locked:Object.fromEntries(['COUPANG','NAVER','ELEVENST','LOTTEON'].map(m=>[m,(editState?.targets.get(m)||localTargets.get(m))?.mode==='UPDATE'])),onApply:({selections})=>{
                    if(busy||viewMarket)return;const candidate=M.normalize(reference);M.applyAddressSelections(candidate,selections);reference=candidate;draft=reference;naverMounted=null;changed();render();
                }});
            };
        }
    }
    function selectCoupangAddress(kind){
        if(busy||loading)return;
        const target=activeTarget();
        window.CommonAddressPicker.open({markets:['COUPANG'],kind,onApply:({selections})=>{
            if(busy||activeTarget()!==target)return;
            const values=M.addressDeliveryValues(selections.COUPANG);for(const [key,value] of Object.entries(values)){draft.delivery[key]=value;changed('delivery.'+key);}render();
        }});
    }
    function ensureCoupang(document=draft){const config=document.markets.COUPANG;config.coupang??={sellerProductId:null,delivery:[],settings:[],options:[],documents:[],source:null};const c=config.coupang;for(const o of document.options)if(!c.options.some(v=>v.optionId===o.id))c.options.push({optionId:o.id,sellerProductItemId:null,vendorItemId:null,registration:Object.entries({maximumBuyForPerson:'0',maximumBuyForPersonPeriod:'1',unitCount:'1',outboundShippingTimeDay:'1',parallelImported:'NOT_PARALLEL_IMPORTED',overseasPurchased:'NOT_OVERSEAS_PURCHASED',pccNeeded:'false'}).map(([name,value])=>({name,value})),attributes:(o.attributes||[]).map(a=>({name:a.name,value:a.value,exposed:'EXPOSED'})),notices:[],certifications:[]});return c;}
    const cpSection=(id,title,body)=>`<section id="${id}" class="coupang-form-section"><h2>${e(title)}</h2>${body}</section>`;
    const cpGrid=F.grid;
    const cpRow=F.row;
    function cpArray(path,rows,key,options={}){
        const [name,type='text',,required,choices=[]]=C.spec[key]||[key];
        let inherited=activeTarget()?.mode==='CREATE'?draft.common[({taxType:'taxType',adultOnly:'adultOnly',modelNo:'model'})[key]]??'':'';if(key==='taxType'&&inherited==='DUTYFREE')inherited='FREE';
        return field(path+'.value-'+key,options.label||name,options.type||type,Object.hasOwn(options,'value')?options.value:rows.find(r=>r.name===key)?.value??inherited,options.choices||choices,!!options.readonly,options.required??!!required)
            .replaceAll('data-common-field=',`data-array-path="${e(path)}" data-array-key="${e(key)}" data-common-field=`);
    }
    function cpOption(){const c=ensureCoupang(),option=draft.options.find(o=>o.id===selectedMarketOption)||draft.options[0];selectedMarketOption=option?.id||null;const index=c.options.findIndex(o=>o.optionId===selectedMarketOption);return {c,option,index,row:c.options[index],prefix:'markets.COUPANG.coupang.options.'+index};}
    function renderCommonBrandSummary(){
        const host=document.querySelector('.common-brand-summary');if(!host)return;
        host.innerHTML=['COUPANG','NAVER','LOTTEON','ELEVENST'].map(m=>{const target=editState?.targets.get(m)||localTargets.get(m),selection=M.brandSelection(target?.status==='READY'?target.working:reference,m);return `<span><strong>${e(marketName(m))}</strong> ${selection?.id||m==='ELEVENST'&&selection?.name?e(selection.name)+' <small>'+e(selection.id||'텍스트')+'</small>':'미선택'}</span>`;}).join('');
    }
    function mountCommonBrand(){
        const input=$('common-basic-fields').querySelector('[data-common-field="common.brand"]');if(!input)return;
        const wrapper=input.closest('label'),host=document.createElement('div');host.className='field common-brand-field';
        host.innerHTML=`<label for="${e(input.id)}">브랜드 <span class="editor-required" aria-label="필수">*</span></label><div class="common-brand-inline"></div><div class="common-brand-summary"></div><p class="common-brand-note">브랜드명을 한 번 검색하고 마켓별 결과를 선택하세요.</p>`;
        const button=document.createElement('button');button.type='button';button.className='btn';button.textContent='브랜드 검색';button.dataset.commonBrandSearch='';
        host.querySelector('.common-brand-inline').append(input,button);wrapper.replaceWith(host);renderCommonBrandSummary();
        FormActions.bindEnter(input,button);
        button.onclick=()=>{if(busy||loading)return;const cp=editState?.targets.get('COUPANG')||localTargets.get('COUPANG');window.CommonBrandPicker.open({name:reference.common.brand,selections:Object.fromEntries(['COUPANG','NAVER','LOTTEON','ELEVENST'].map(m=>{const t=editState?.targets.get(m)||localTargets.get(m),value=M.brandSelection(t?.status==='READY'?t.working:reference,m);return [m,value?.name&&(value.id||m==='ELEVENST')?value:null];})),locked:{COUPANG:M.brandLocked(reference,'COUPANG')||cp?.mode==='UPDATE'},onApply:({name,selections})=>{
            if(busy||viewMarket)return;
            const candidate=M.normalize(reference);M.setCommonBrand(candidate,name);M.applyBrandSelections(candidate,selections);
            reference=candidate;draft=reference;
            changed();render();
        }});};
    }
    function mountCommonCategories(){
        const host=document.createElement('div');host.className='field common-brand-field';
        host.innerHTML='<label for="common-category-query">마켓별 카테고리</label><div class="common-brand-inline"><input id="common-category-query" type="search" maxlength="200" placeholder="카테고리명 또는 코드"><button type="button" class="btn" data-common-category-search>카테고리 검색</button></div><div class="common-brand-summary" data-common-category-summary></div><p class="common-brand-note">같은 검색어로 조회하고 마켓별 카테고리를 선택하세요. 각 탭에서 직접 수정한 값은 유지됩니다.</p>';
        $('common-basic-fields').querySelector('.editor-grid').append(host);
        const input=host.querySelector('input'),button=host.querySelector('button');FormActions.bindEnter(input,button);
        const selections=Object.fromEntries(['COUPANG','NAVER','ELEVENST','LOTTEON'].map(m=>{const t=editState?.targets.get(m)||localTargets.get(m),d=t?.status==='READY'?t.working:reference,code=m==='NAVER'?d.markets.NAVER.naver?.editorInput?.fields?.['originProduct.leafCategoryId']||d.markets.NAVER.categoryCode:d.markets[m].categoryCode;return [m,code?{id:code,name:code}:null];}));
        host.querySelector('[data-common-category-summary]').innerHTML=Object.entries(selections).map(([m,v])=>`<span><strong>${e(marketName(m))}</strong> ${e(v?.id||'미선택')}</span>`).join('');
        button.onclick=()=>{if(busy||loading)return;window.CommonCategoryPicker.open({name:input.value,selections,locked:Object.fromEntries(['COUPANG','NAVER','ELEVENST','LOTTEON'].map(m=>[m,(editState?.targets.get(m)||localTargets.get(m))?.mode==='UPDATE'])),onApply:({selections:chosen})=>{applyCommonCategories(chosen);
        }});};
    }
    async function applyCommonCategories(chosen){
        if(busy||viewMarket)return;
        const candidate=M.normalize(reference);M.applyCategorySelections(candidate,chosen);
        const previous=editState?.targets.get('COUPANG')||localTargets.get('COUPANG');
        const projected=previous?.mode==='UPDATE'?null:M.createTarget(candidate,'COUPANG',previous);
        busy=true;updateBusy();
        try{
            if(chosen.COUPANG?.id&&projected?.working.markets.COUPANG.categoryCode===chosen.COUPANG.id){
                const r=await window.CoupangRead.start('/api/marketplaces/coupang/categories/'+encodeURIComponent(chosen.COUPANG.id)+'/rules').promise,c=projected.working.markets.COUPANG.coupang;
                const removed=C.categoryRemovals({options:(c?.options||[]),documents:c?.documents||[]},r);
                if(removed.length&&!window.confirm('새 카테고리에서 제외되는 입력: '+removed.join(', ')+'\n이 항목을 제외하고 적용할까요?'))return;
            }
            reference=candidate;draft=reference;for(const m of Object.keys(chosen))ruleErrors.delete(m);
            naverMounted=null;changed();render();const target=editState?.targets.get('COUPANG')||localTargets.get('COUPANG');
            if(chosen.COUPANG?.id)await category(target?.working||reference,target,true);
        }catch(err){$('common-error').textContent=err.message;$('common-error').hidden=false;}
        finally{busy=false;updateBusy();}
    }
    function mountBrand(locked){const c=ensureCoupang(),input=$('common-basic-fields').querySelector('[data-common-field="common.brand"]');if(!input)return;window.CoupangBrandPicker.mount(input,{name:draft.common.brand,id:c.settings.find(v=>v.name==='brandId')?.value,locked,nameEditable:false,onSelect:brand=>{
        M.applyBrandSelections(draft,{COUPANG:{id:brand.brandId,name:brand.brandName}});draft.common.brand=brand.brandName;
        changed('common.brand');changed('markets.COUPANG.coupang.settings');renderBasic();renderMarkets();markLocks();
    }});}
    function brandErrors(document){const m=document.markets.COUPANG;if(!document.selectedMarkets.includes('COUPANG')||m?.coupang?.sellerProductId)return [];const brand=m?.coupang?.settings?.find(v=>v.name==='brandId')?.value;return document.common.brand&&brand?[]:[{market:'COUPANG',path:'common.brand',message:'브랜드를 검색해서 선택해 주세요.'}];}
    const cpCombination=row=>C.registrationOptionName(row||{attributes:[]})||'미입력';
    function updateCoupangCombinations(){renderMedia();}
    function hydrateCoupangRules(c,r){
        if(!r)return;
        for(const row of c.options){
            for(const a of r.attributes||[])if((a.exposed!=='EXPOSED'||a.required==='MANDATORY'&&(!a.groupNumber||a.groupNumber==='NONE'))&&!row.attributes.some(v=>v.name===a.name))row.attributes.push({name:a.name,value:'',exposed:a.exposed});
            const notice=r.notices?.find(v=>v.name===row.notices[0]?.category)||r.notices?.[0];
            if(notice)for(const f of notice.fields)if(!row.notices.some(v=>v.category===notice.name&&v.name===f.name))row.notices.push({category:notice.name,name:f.name,content:/A\/S|AS 책임/.test(f.name)?S.serviceContact(draft.common):''});
            for(const cert of r.certifications||[])if(cert.required==='MANDATORY'&&!row.certifications.some(v=>v.type===cert.type))row.certifications.push({type:cert.type,code:'',attachments:[]});
        }
        for(const doc of r.documents||[])if(C.requiredDocument(doc,{options:c.options.map(o=>({registration:Object.fromEntries(o.registration.map(v=>[v.name,v.value]))}))})&&!c.documents.some(v=>v.templateName===doc.name))c.documents.push({templateName:doc.name,path:''});
    }
    function renderCoupangBasic(){
        const c=ensureCoupang(),update=activeTarget()?.mode==='UPDATE',status=ruleErrors.get('COUPANG')||(rules.has('COUPANG')?'카테고리 규격 조회 완료':'');
        if(!update&&rules.get('COUPANG')?.categoryCode===draft.markets.COUPANG.categoryCode)hydrateCoupangRules(c,rules.get('COUPANG'));
        const basicFields={brand:()=>field('common.brand','브랜드','text',draft.common.brand),displayProductName:()=>field('markets.COUPANG.overrides.productName','노출상품명','text',draft.markets.COUPANG.overrides.productName??(update?'':draft.common.productName)),sellerProductName:()=>field('common.name','등록상품명','text',draft.common.name,[],false,true),displayCategoryCode:()=>field('markets.COUPANG.categoryCode','카테고리 코드','text',draft.markets.COUPANG.categoryCode,[],update,true),generalProductName:()=>field('common.productName','제품명','text',draft.common.productName,[],update),productGroup:()=>cpArray('markets.COUPANG.coupang.settings',c.settings,'productGroup',{label:'상품군'}),sellerProductId:()=>F.field({caption:'등록상품 ID',control:`<input aria-label="등록상품 ID" disabled value="${e(c.sellerProductId||'')}">`}),productId:()=>F.field({caption:'노출상품 ID',control:`<input aria-label="노출상품 ID" disabled value="${e(c.source?.basic?.productId||'')}">`})};
        $('common-basic-fields').innerHTML=cpSection('coupang-basic','상품 정보',F.basic({mode:update?'edit':'new',field:key=>basicFields[key]()})+`<div class="editor-category-controls"><button type="button" class="btn" data-category-load>카테고리 규격 조회</button><span role="status">${e(status)}</span></div>`);
        mountBrand(update);
        const basicGrid=$('common-basic-fields').querySelector('.coupang-field-grid');basicGrid.classList.add('coupang-basic-grid');
        const categoryInput=$('common-basic-fields').querySelector('[data-common-field="markets.COUPANG.categoryCode"]'),categoryLabel=categoryInput.closest('label.field');
        FormActions.bindEnter(categoryInput,$('common-basic-fields').querySelector('[data-category-load]'));
        const categoryRow=document.createElement('div');categoryRow.className='field coupang-form-row';const categoryCaption=document.createElement('label');categoryCaption.className='coupang-row-label';categoryCaption.htmlFor=categoryInput.id;categoryCaption.innerHTML=categoryLabel.firstElementChild.innerHTML;
        const categoryBody=document.createElement('div');categoryBody.className='coupang-row-body coupang-category-inline';categoryBody.append(categoryInput,$('common-basic-fields').querySelector('.editor-category-controls'));categoryRow.append(categoryCaption,categoryBody);categoryLabel.replaceWith(categoryRow);if(update)$('common-basic-fields').querySelector('.editor-category-controls').hidden=!ruleErrors.get('COUPANG');
        const columns=C.purchaseColumns(rules.get('COUPANG'),c.options.map(row=>({...row,originalAttributes:c.source?.options?.find(o=>o.sellerProductItemId===row.sellerProductItemId)?.attributes||[],attributePermissions:c.source?.options?.find(o=>o.sellerProductItemId===row.sellerProductItemId)?.attributePermissions||[]})));
        const headers=['옵션 아이디',...columns.map(r=>r.name),'품번 (MPN)','바코드 (GTIN)','정상가','판매가','재고','판매자상품코드'];
        const rows=draft.options.map((o,n)=>{
            const row=c.options.find(v=>v.optionId===o.id),index=c.options.indexOf(row),p='markets.COUPANG.coupang.options.'+index;
            const source=c.source?.options?.find(v=>v.sellerProductItemId===row.sellerProductItemId);
            const attribute=(r,purchase=false)=>{
                const locked=purchase?update&&!C.canEditPurchase({...row,originalAttributes:source?.attributes||row.attributes,attributePermissions:source?.attributePermissions||[]},r.name,true):false;
                let k=row.attributes.findIndex(a=>a.name===r.name);if(k<0){k=row.attributes.length;row.attributes.push({name:r.name,value:'',exposed:r.exposed||'EXPOSED'});}
                return field(p+'.attributes.'+k+'.value',r.name==='Manufacturer Part Number'?'품번 (MPN)':r.name==='Global Trade Item Number'?'바코드 (GTIN)':r.name,'text',row.attributes[k].value,[],locked);
            };
            const purchases=columns.map(column=>{const r=C.purchaseAttribute({...row,attributePermissions:source?.attributePermissions},column);return (column.group?`<select aria-label="구매옵션 택1" data-cp-group="${e(o.id)}" data-cp-choices="${e(column.choices.map(a=>a.name).join('|'))}" ${update&&column.choices.some(r=>source?.attributes?.some(a=>a.name===r.name))?'disabled':''}>${column.choices.map(a=>`<option value="${e(a.name)}" ${a.name===r.name?'selected':''}>${e(a.name)}</option>`).join('')}</select>`:'')+attribute(r,true)+(r.usableUnits?.length?'<small>단위: '+e(r.usableUnits.join(', '))+'</small>':'');});
            const identity=name=>{const r=row.attributes.find(a=>a.name===name)||rules.get('COUPANG')?.attributes.find(a=>a.name===name);return r?attribute(r):'<span class="product-meta">미제공</span>';};
            const cells=[e(row.vendorItemId||(update?'미확인':'등록 후 발급'))+(nativeNew()&&draft.options.length>1?'<button type="button" class="btn small danger" data-cp-remove-option="'+e(o.id)+'">삭제</button>':''),...purchases,identity('Manufacturer Part Number'),identity('Global Trade Item Number'),cpArray(p+'.registration',row.registration,'originalPrice',{label:'정상가',readonly:update&&!!row.vendorItemId}),field('options.'+n+'.price','판매가','number',o.price,[],false,true),draft.stockMode==='PRODUCT'?e(draft.productQuantity??'미확인'):field('options.'+n+'.quantity','재고','number',o.quantity,[],false,true),field('options.'+n+'.sku','판매자상품코드','text',o.sku)];
            return '<tr>'+cells.map((value,k)=>`<td data-label="${e(headers[k])}">${value}</td>`).join('')+'</tr>';
        }).join('');
        $('common-option-fields').innerHTML=cpSection('coupang-option-list','옵션 목록',(draft.stockMode==='PRODUCT'?cpGrid(field('productQuantity','상품 공통 재고수량','number',draft.productQuantity,[],false,true)):'')+F.optionTable({headers,rows,widths:[144,...columns.map(()=>176),176,176,126,126,96,176]})+(nativeNew()?'<button type="button" class="btn small" data-cp-add-option>옵션 추가</button>':''));
        $('common-option-add').disabled=true;
    }
    function renderCoupangMedia(){
        if(!draft.options.some(o=>o.id===selectedMedia))selectedMedia=draft.options[0]?.id||null;
        $('common-media-option').innerHTML=draft.options.map(o=>`<option value="${e(o.id)}">${e(activeTarget()?.mode==='UPDATE'?o.name||'이름 없는 옵션':cpCombination(ensureCoupang().options.find(r=>r.optionId===o.id)))}</option>`).join('');$('common-media-option').value=selectedMedia||'';
        const ownImages=M.imagesFor(draft,selectedMedia),sharedImages=(draft.media.imageOverrides?.[selectedMedia]?[]:M.imagesFor(draft,null)).filter(i=>!ownImages.some(v=>v.sourceId===i.id)),images=[...sharedImages,...ownImages],contents=draft.media.contents.filter(c=>(c.optionId??null)===selectedMedia),sharedContents=(draft.media.contentOverrides?.[selectedMedia]?[]:draft.media.contents).filter(c=>!c.optionId&&!contents.some(v=>v.sourceId===c.id));
        const imageCard=(i,n)=>{const shared=!i.optionId,index=ownImages.indexOf(i);return F.imageCard({preview:`<img src="${e(i.url)}" alt="${i.representative?'대표':'추가'} 이미지 ${n+1}" referrerpolicy="no-referrer" loading="lazy"><span hidden>이미지 없음</span>`,caption:`${i.representative?'대표':'추가'} · ${n+1}${shared?' · 공통 기준':''}`,note:shared?'편집하면 이 옵션의 개별값으로 저장':'',actions:`<button type="button" class="btn small" data-edit-image="${e(i.id)}">편집</button>`+(shared?'':`<button type="button" class="btn small" data-image-action="represent" data-image-id="${e(i.id)}" ${sharedImages.some(v=>v.representative)?'disabled title="공통 기준 대표 이미지 사용 중"':''}>대표로 설정</button><button type="button" class="btn small" data-image-action="up" data-image-id="${e(i.id)}" ${index===0?'disabled':''}>위</button><button type="button" class="btn small" data-image-action="down" data-image-id="${e(i.id)}" ${index===ownImages.length-1?'disabled':''}>아래</button><button type="button" class="btn small danger" data-image-action="remove" data-image-id="${e(i.id)}">삭제</button>`)});};
        const typeOf=i=>i.imageType||(i.representative?'REPRESENTATION':'DETAIL');
        const groups=F.imageGroups({images:type=>images.map((i,n)=>typeOf(i)===type?imageCard(i,n):'').join(''),count:type=>images.filter(i=>typeOf(i)===type).length});
        const controls=F.imageControls({prefix:'common',state:uploads.size?'R2 이미지 확인 중…':''});
        $('common-media-fields').innerHTML=`<div class="coupang-option-workspace"><div class="coupang-option-tabs" role="tablist" aria-label="옵션별 정보">${draft.options.map(o=>`<button type="button" role="tab" data-coupang-media-option="${e(o.id)}" aria-selected="${o.id===selectedMedia}" tabindex="${o.id===selectedMedia?0:-1}" aria-controls="common-coupang-option-panel">${e(activeTarget()?.mode==='UPDATE'?o.name||'이름 없는 옵션':cpCombination(ensureCoupang().options.find(r=>r.optionId===o.id)))}</button>`).join('')}</div><div id="common-coupang-option-panel" role="tabpanel">`+cpSection('coupang-images','대표 이미지·추가 이미지',controls+groups)+cpSection('coupang-description','상세설명',F.description({cards:[...sharedContents,...contents].map((content,index)=>{const n=draft.media.contents.indexOf(content),shared=!content.optionId;return F.contentCard({label:content.type==='HTML'?'HTML 설명':'설명 이미지',index,field:field('media.contents.'+n+'.value',content.type==='HTML'?'설명 원문':'설명 이미지 주소',content.type==='HTML'?'textarea':'text',content.value,[],shared),preview:`<div class="editor-preview" data-preview-id="${e(content.id)}"></div>`,note:shared?'편집하면 이 옵션의 개별값으로 저장':'',remove:shared?'':`<button type="button" class="btn small" data-remove-content="${e(content.id)}">설명 삭제</button>`});}).join('')}))+'</div></div>';
        for(const button of $('common-media-fields').querySelectorAll('[data-coupang-media-option]'))button.onclick=()=>{selectedMedia=button.dataset.coupangMediaOption;renderMedia();};
        $('common-media-fields').querySelector('[role=tablist]').onkeydown=ev=>{
            if(!['ArrowLeft','ArrowRight','Home','End'].includes(ev.key)||busy||loading||uploads.size)return;
            ev.preventDefault();const ids=draft.options.map(o=>o.id),index=ids.indexOf(selectedMedia),next=ev.key==='Home'?0:ev.key==='End'?ids.length-1:(index+(ev.key==='ArrowRight'?1:-1)+ids.length)%ids.length;
            selectedMedia=ids[next];renderMedia();$('common-media-fields').querySelector('[role=tab][aria-selected=true]').focus();
        };
        FormActions.bindEnter($('common-image-url'),$('common-media-fields').querySelector('[data-add-image]'));
        previews();
    }
    const coupangSettingUi=new WeakMap(),coupangCertBackups=new WeakMap();
    function cpSettingUi(){const target=activeTarget()||draft;let states=coupangSettingUi.get(target);if(!states)coupangSettingUi.set(target,states=new Map());const id=cpOption().row?.optionId;let state=states.get(id);if(!state)states.set(id,state={});return state;}
    function cpChoices(key,label,pairs,value,readonly=false,action=null){
        const {prefix}=cpOption(),delivery=key?.startsWith('delivery.'),array=key?.startsWith('registration.')?prefix+'.registration':key&&!delivery?'markets.COUPANG.coupang.settings':null,name=key?.slice(key.indexOf('.')+1),path=delivery?'delivery.'+cpDeliveryKeys[name]:array?array+'.value-'+name:null;
        return F.choices({label,pairs,value,disabled:readonly,attrs:(v,n)=>`name="${e(path||'common-cp-'+action)}" id="${e(fieldId(path||'common-cp-'+action)+'-'+n)}" ${path?`data-common-field="${e(path)}" ${array?`data-array-path="${e(array)}" data-array-key="${e(name)}"`:''}`:`data-cp-setting-action="${e(action)}"`}`});
    }
    function renderCoupangSettings(){
        const {c,row:o,prefix:p}=cpOption();if(!o){$('common-market-fields').innerHTML='';return;}
        if(activeTarget()?.mode==='CREATE'&&rules.get('COUPANG'))hydrateCoupangRules(c,rules.get('COUPANG'));
        const ui=cpSettingUi(),extraOpen=document.getElementById('common-cp-extra-settings')?.open,update=activeTarget()?.mode==='UPDATE',approved=update&&!!o.vendorItemId;
        const picker=draft.options.length>1?`<div class="common-coupang-context"><label class="field editor-option-picker"><span>옵션별 설정 적용 대상</span><select id="common-market-option">${draft.options.map(v=>`<option value="${e(v.id)}" ${v.id===selectedMarketOption?'selected':''}>${e(update?v.name||'이름 없는 옵션':cpCombination(c.options.find(r=>r.optionId===v.id)))}</option>`).join('')}</select></label></div>`:'';
        const values=Object.fromEntries(o.registration.map(v=>[v.name,v.value])),settings=Object.fromEntries(c.settings.map(v=>[v.name,v.value]));
        const types=rules.get('COUPANG')?.certifications||[],certMode=ui.certificationSelection??F.certificationMode(o.certifications);
        const limit=ui.purchaseLimitMode??(values.maximumBuyForPerson==null||values.maximumBuyForPerson===''?'':Number(values.maximumBuyForPerson)===0?'OFF':'ON'),period=ui.salePeriodMode??(!settings.saleEndedAt?'':settings.saleEndedAt.startsWith('2099-')?'OFF':'ON');
        const configured=(scope,keys)=>keys.map(key=>scope==='settings'&&key==='manufacture'?field('common.manufacturer','제조사','text',draft.common.manufacturer):cpArray(scope==='settings'?'markets.COUPANG.coupang.settings':p+'.registration',scope==='settings'?c.settings:o.registration,key,{label:({unitCount:'구성 단위수량',extraInfoMessage:'주문제작 입력 안내'})[key],readonly:approved&&key.startsWith('autoPricingInfo.')})).join('');
        const additionalKeys=C.optionKeys.filter(k=>!['externalVendorSku','originalPrice','salePrice','maximumBuyCount','unitCount','parallelImported','overseasPurchased','pccNeeded','adultOnly','maximumBuyForPerson','maximumBuyForPersonPeriod','taxType','searchTags','outboundShippingTimeDay'].includes(k));
        const extraSettings=c.settings.filter(v=>!['saleStartedAt','saleEndedAt','manufacture','bundleInfo.bundleType','productGroup','brandId','extraInfoMessage','vendorUserId'].includes(v.name)),extras=o.registration.filter(v=>!C.optionKeys.includes(v.name));
        const main=F.settings({mode:update?'edit':'new',types,certMode,limit,period,bundle:settings['bundleInfo.bundleType'],saleEndedAt:settings.saleEndedAt,extraId:'common-cp-extra-settings',choice:cpChoices,configured,
            registrationChoice:(key,label,pairs)=>cpChoices('registration.'+key,label,pairs,values[key]),
            certification:F.certification({types,entries:o.certifications,prefix:'common-cp',entryType:ui.entryType,entryCode:ui.entryCode,entryField:(entry,n,r)=>field(p+'.certifications.'+n+'.code','인증번호','text',entry.code,[],false,r?.required==='MANDATORY'&&r.dataType==='CODE')}),
            extra:configured('settings',['extraInfoMessage'])+configured('registration',['unitCount','overseasPurchased','pccNeeded',...additionalKeys])+extraSettings.map(v=>cpArray('markets.COUPANG.coupang.settings',c.settings,v.name,{readonly:!C.settingsKeys.includes(v.name)})).join('')+extras.map(v=>cpArray(p+'.registration',o.registration,v.name,{readonly:true})).join('')
        });
        const documents=F.documents({items:c.documents,rules:rules.get('COUPANG')?.documents||[],prefix:'common-cp',url:C.url,required:d=>rules.get('COUPANG')?.documents.some(r=>r.name===d.templateName&&C.requiredDocument(r,{options:c.options.map(v=>({registration:Object.fromEntries(v.registration.map(f=>[f.name,f.value]))}))})),field:(d,n,required)=>field('markets.COUPANG.coupang.documents.'+n+'.path',d.templateName||'서류 주소','text',d.path||d.vendorPath,[],false,required)});
        const tags=o.registration.find(f=>f.name==='searchTags')?.value||'';
        const filters=o.attributes.filter(a=>a.exposed!=='EXPOSED'&&a.name!=='Manufacturer Part Number').map(a=>field(p+'.attributes.'+o.attributes.indexOf(a)+'.value',a.name,'text',a.value,[],a.exposed!=='NONE')).join('');
        const noticeTypes=rules.get('COUPANG')?.notices||[],noticeCategory=o.notices[0]?.category||noticeTypes[0]?.name||'';
        const notices=o.notices.filter(v=>v.category===noticeCategory).map(v=>{const n=o.notices.indexOf(v);return field(p+'.notices.'+n+'.content',(v.category||'')+' · '+(v.name||'고시 항목'),'textarea',v.content,[],false,rules.get('COUPANG')?.notices.find(r=>r.name===v.category)?.fields.some(f=>f.name===v.name&&f.required==='MANDATORY'));}).join('');
        $('common-market-fields').innerHTML=picker+cpSection('coupang-main-info','상품주요정보','<div id="common-cp-settings-fields">'+main+'</div>')+cpSection('coupang-documents','구비서류',documents)+cpSection('coupang-search-tags','검색어',cpArray(p+'.registration',o.registration,'searchTags')+`<small id="common-coupang-tag-count" class="product-meta"></small><p id="common-coupang-tag-limit" class="form-error" role="alert" hidden></p><div id="common-coupang-tag-list" class="coupang-tag-list"></div>`)+cpSection('coupang-search-filters','검색필터',filters||'<p class="product-meta">검색 속성 미제공</p>')+cpSection('coupang-notices','상품정보제공고시',F.notices({referenceAttrs:'data-notice-reference',checked:o.notices.length&&o.notices.every(v=>v.content==='상품 상세페이지 참조'),disabled:!o.notices.length,selector:F.field({caption:'고시 유형 <span class="editor-required" aria-label="필수">*</span>',control:`<select data-notice-category ${noticeTypes.length?'':'disabled'}>${[...new Set([noticeCategory,...noticeTypes.map(v=>v.name)])].filter(Boolean).map(name=>`<option value="${e(name)}" ${name===noticeCategory?'selected':''}>${e(name)}</option>`).join('')}</select>`}),fields:notices}));
        if(extraOpen)$('common-cp-extra-settings').open=true;
        FormActions.bindEnter($('common-cp-cert-code'),$('common-market-fields').querySelector('[data-add-cert]'));
        renderCoupangTags(tags);
    }
    function renderCoupangTags(value){const host=$('common-coupang-tag-list');if(!host)return;const state=F.tagState(value);host.innerHTML=state.html;$('common-coupang-tag-count').textContent=state.count;$('common-coupang-tag-limit').hidden=!state.invalid;$('common-coupang-tag-limit').textContent=state.invalid?'검색어는 최대 20개, 각 20자 이하로 입력해 주세요.':'';}
    const cpDeliveryKeys={deliveryMethod:'method',deliveryCompanyCode:'carrier',deliveryChargeType:'chargeType',deliveryCharge:'charge',freeShipOverAmount:'freeOver',deliveryChargeOnReturn:'initialReturnCharge',remoteAreaDeliverable:'remoteArea',unionDeliveryType:'bundle',returnCharge:'returnCharge'};
    function renderCoupangDelivery(){
        const d={...draft.delivery},{row:o,prefix:p}=cpOption();if(['FREE','NOT_FREE'].includes(d.chargeType)&&!d.freeOver)d.freeOver='0';if(d.chargeType==='FREE'&&!d.charge)d.charge='0';
        const values=Object.fromEntries(Object.entries(cpDeliveryKeys).map(([key,input])=>[key,d[input]]));
        const configured=keys=>keys.map(key=>{const [baseLabel,type='text',,required,choices=[]]=C.spec[key],label=({deliveryCompanyCode:'택배사',deliveryMethod:'배송방법',deliveryChargeType:'배송비 종류'})[key]||baseLabel;return field('delivery.'+cpDeliveryKeys[key],label,key==='deliveryCompanyCode'?'select':type,values[key],key==='deliveryCompanyCode'?Object.keys(C.courierNames):choices,false,!!required);}).join('');
        const address=returns=>{
            const code=returns?d.returnCode:d.outboundCode,book=(draft.markets.COUPANG.addressBook||reference.markets.COUPANG.addressBook)?.[returns?'returns':'outbound'],matching=book?.code===code?book:null;
            const text=returns?[d.returnZip,d.returnAddress,d.returnAddressDetail].filter(Boolean).join(' '):matching?[matching.zipCode,matching.address,matching.detail].filter(Boolean).join(' '):'';
            return F.address({returns,title:returns?d.returnName||'반품/교환지':matching?.name||'상품출고지',text:text||(code?'선택한 주소 코드 '+code:'주소록에서 주소를 선택해 주세요.'),code,phone:d.returnContact,buttonAttrs:`data-coupang-address="${returns?'returns':'outbound'}"`});
        };
        const shipping=F.shipping({address:address(false),choice:cpChoices,configured,values,shippingTimes:F.shippingTimes({mode:activeTarget()?.mode==='UPDATE'?'edit':'new',selectionMode:cpSettingUi().shippingTimeMode,options:ensureCoupang().options.map(row=>({name:draft.options.find(v=>v.id===row.optionId)?.name,days:row.registration.find(v=>v.name==='outboundShippingTimeDay')?.value})),choice:cpChoices,field:(n,label,value,common)=>cpArray('markets.COUPANG.coupang.options.'+n+'.registration',ensureCoupang().options[n].registration,'outboundShippingTimeDay',{label,value}).replace('data-common-field=',(common?'data-cp-shipping-common ':'')+'data-common-field=')})});
        const returns=F.returns({address:address(true),field:(key,label,value)=>field('delivery.'+cpDeliveryKeys[key],label,'number',value,[],false,true),values,prefix:'common-cp'});
        $('common-delivery-fields').innerHTML=cpSection('coupang-shipping','배송',shipping)+cpSection('coupang-returns','반품·교환',returns);
        updateCoupangDeliveryDisplay();for(const button of $('common-delivery-fields').querySelectorAll('[data-coupang-address]'))button.onclick=()=>selectCoupangAddress(button.dataset.coupangAddress);
    }
    function updateCoupangDeliveryDisplay(){
        const d={...draft.delivery},host=$('common-delivery-fields');if(d.chargeType==='FREE'&&!d.charge)d.charge='0';
        host.querySelector('[data-coupang-delivery-charge]').hidden=d.chargeType==='FREE'&&String(d.charge)==='0';
        host.querySelector('[data-coupang-free-over]').hidden=!(d.chargeType==='CONDITIONAL_FREE'||!d.chargeType||!!d.freeOver&&Number(d.freeOver)!==0);
        const initial=d.chargeType==='FREE'?d.initialReturnCharge:d.charge,total=Number(initial)+Number(d.returnCharge),valid=[initial,d.returnCharge].every(v=>/^\d+$/.test(String(v??'')));
        host.querySelector('.coupang-return-total').textContent=valid&&Number.isSafeInteger(total)?'고객 사유로 반품 시 왕복 배송비는 '+total.toLocaleString('ko-KR')+'원입니다.':'배송비를 입력하면 왕복 반품 배송비 합계가 표시됩니다.';
    }
    function renderMarkets(){
        $('common-market-tabs').hidden=true;
        if(viewMarket&&!M.markets.some(([key])=>key===viewMarket)){$('common-market-fields').innerHTML=`<p class="product-meta">${e(marketName(viewMarket))} 상품 연동 준비 중입니다. 공통 기준 정보는 위 입력에서 확인할 수 있습니다.</p>`;return;}
        if(viewMarket==='COUPANG'){renderCoupangSettings();markLocks();return;}
        if(viewMarket==='NAVER'){renderNaver();return;}
        let html=`<div class="editor-grid">${(viewMarket?['manufacturer','origin','material','afterService','taxType','adultOnly']:['taxType','adultOnly','afterServiceManager','afterServiceTelephone','afterService','saleStartedAt','saleEndedAt']).map(key=>{const [label,type='text',choices=[]]=commonSpec[key];return field('common.'+key,label,type,draft.common[key],choices);}).join('')}</div>`;
        if(!viewMarket)html=[['과세·연령',['taxType','adultOnly']],['A/S',['afterServiceManager','afterServiceTelephone','afterService']],['판매기간',['saleStartedAt','saleEndedAt']]].map(([title,keys])=>'<h3>'+e(title)+'</h3><div class="editor-grid '+(title==='A/S'?'common-service-settings':'')+'">'+keys.map(key=>{const [label,type='text',choices=[]]=commonSpec[key];return field('common.'+key,label,type,draft.common[key],choices);}).join('')+'</div>').join('');
        if(viewMarket){const c=reference.markets[viewMarket];c.esm??={siteId:viewMarket==='GMARKET'?'2':'1',goodsStatus:'',shippingPolicyId:'',returnPolicyId:'',itemCode:'',attributes:[],notices:[]};const v=c.esm;html+=`<h3>${e(marketName(viewMarket))} 준비 입력</h3><div class="editor-grid">${field('markets.'+viewMarket+'.categoryCode','카테고리 코드','text',c.categoryCode)}${[['siteId','사이트 ID'],['goodsStatus','판매 상태'],['shippingPolicyId','발송 정책 ID'],['returnPolicyId','반품 주소번호'],['itemCode','사이트 상품코드']].map(([k,l])=>field('markets.'+viewMarket+'.esm.'+k,l,'text',v[k],[],k==='siteId')).join('')}</div><h3>속성·추천 옵션</h3>${pairs('markets.'+viewMarket+'.esm.attributes',v.attributes)}<h3>상품고시</h3>${pairs('markets.'+viewMarket+'.esm.notices',v.notices)}`;}
        if(!viewMarket)html+=`<label class="common-inline-label"><input type="checkbox" data-common-long-sale ${draft.common.saleEndedAt?.startsWith('2099-12-31')?'checked':''}>현재부터 2099년 12월 31일 23:59까지 설정</label>`;
        $('common-market-fields').innerHTML=html;markLocks();
    }
    function renderSelection(){$('common-market-selection').innerHTML=M.markets.map(([key,label])=>`<label><input type="checkbox" data-selected-market="${key}" ${(sendMarkets||new Set(reference.selectedMarkets)).has(key)?'checked':''} ${!['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(key)||!allowed(key)?'disabled':''}><span>${e(label)}${!['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(key)?' · 준비 입력':''}</span></label>`).join('')+M.channels.filter(c=>!c.commonDraft).map(c=>`<label><input type="checkbox" disabled><span>${e(c.label)} · 준비 중</span></label>`).join('');}
    function renderViews(){const all=[['','공통 기준 정보'],...viewMarkets];$('common-view-tabs').innerHTML=all.map(([key,label])=>`<button type="button" role="tab" data-view-market="${e(key)}" aria-selected="${(viewMarket||'')===key}" aria-controls="common-editor-form" tabindex="${(viewMarket||'')===key?0:-1}" ${!allowed(key)?'disabled':''}>${e(label)}</button>`).join('');const selected=$('common-view-tabs').querySelector('[aria-selected=true]'),strip=$('common-view-tabs');if(selected){const a=selected.getBoundingClientRect(),b=strip.getBoundingClientRect();if(a.right>b.right)strip.scrollLeft+=a.right-b.right;else if(a.left<b.left)strip.scrollLeft+=a.left-b.left;}}
    function changeLabel(change){const tail=change.path.split('.').at(-1),dates={saleStartedAt:'판매 시작일시',saleEndedAt:'판매 종료일시',saleStartDate:'판매 시작일시',saleEndDate:'판매 종료일시',slStrtDttm:'판매 시작일시',slEndDttm:'판매 종료일시',selBgnDy:'판매 시작일시',selEndDy:'판매 종료일시'};if(dates[tail])return dates[tail];if(change.path==='markets.COUPANG.coupang.documents')return '구비서류';if(change.path.startsWith('partner.'))return window.ElevenstEditorModel?.spec?.[tail]?.[0]||tail;if(change.path.startsWith('lotteon.'))return window.LotteonEditorModel?.spec?.[tail]?.[0]||tail;if(change.path.startsWith('markets.COUPANG.coupang.settings.'))return C.spec[tail]?.[0]||tail;if(change.path.startsWith(N.prefix)){const key=change.path.slice(N.prefix.length);return key.startsWith('fields.')?NM.spec[key.slice(7)]?.[0]||key.slice(7):({options:'옵션·추가금·재고',optionNames:'옵션명',optionMode:'옵션 유형',images:'이미지',description:'상세설명'})[key]||key;}if(change.path==='markets.COUPANG.overrides.productName')return '노출상품명';if(change.path==='productQuantity')return '상품 공통 재고수량';if(change.path.startsWith('common.'))return commonSpec[change.path.slice(7)]?.[0]||change.path;const option=reference.options.find(o=>o.id===change.optionId)?.name||'옵션';return change.path.startsWith('options.')?option+' · '+({name:'옵션명',sku:'판매자 상품코드',originalPrice:'정상가',price:'판매가',quantity:'판매 수량'})[change.path.slice(8)]:change.path.startsWith('media.')?(change.optionId?option+' · ':'공통 · ')+(change.path==='media.images'?'이미지':'설명'):change.path.startsWith('delivery.')?deliverySpec[change.path.slice(9)]?.[0]||change.path:change.path.includes('attributes')?'속성':change.path.includes('notices')?'상품고시':change.path.includes('registration')?'옵션 등록 설정':change.path.includes('settings')?'상품 설정':change.path;}
    function comparisonValue(value){
        if(value===undefined)return '공통 기준 없음';if(value===null||value==='')return '빈값';
        if(typeof value!=='object'){const text=String(value);return e(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(text)?text.replace('T',' '):text);}
        if(Object.hasOwn(value,'이름')&&Object.hasOwn(value,'ID'))return e(value.이름||'빈값')+' <small>ID: '+e(value.ID||'미선택')+'</small>';
        const text=JSON.stringify(value,null,2);return `<details><summary>${Array.isArray(value)?value.length+'개 항목':'상세값 보기'}</summary><pre>${e(text)}</pre></details>`;
    }
    const comparisonSelections=new Map();
    function renderStoredComparison(target){
        const comparison=target.comparison;if(target.mode!=='UPDATE')return '';if(!['READY','UNSUPPORTED'].includes(target.status))return '<p class="product-meta">마켓 최신값을 확인한 뒤 저장값을 비교할 수 있습니다. 보관된 입력은 유지됩니다.</p>';if(!comparison)return '';
        let selected=comparisonSelections.get(target.market);if(!selected||selected.token!==comparison.token){selected={token:comparison.token,keys:new Set(),expanded:false};comparisonSelections.set(target.market,selected);}
        const disabled=busy||loading||uploads.size>0||expired(),rows=comparison.rows||[],chosen=rows.filter(r=>selected.keys.has(r.key));
        const different=rows.filter(r=>(r.kind|| (r.savedKnown?'DIFFERENCE':'INFORMATION'))==='DIFFERENCE'),missing=rows.filter(r=>!different.includes(r));
        const renderRows=items=>items.map(row=>{const field=row.fields[0],option=activeTarget()?.working?.options.find(o=>o.id===field.optionId),label=row.label?`${option?option.name+' · ':''}${row.label}${row.source==='COMMON'?' · 공통 상속':''}`:row.key==='brand'?'브랜드':changeLabel(field);return `<div class="common-change-row"><label class="common-inline-label"><input type="checkbox" data-comparison-select="${e(row.key)}" ${selected.keys.has(row.key)?'checked':''} ${disabled||!row.adoptAllowed&&!row.replayAllowed?'disabled':''}><strong>${e(label)}</strong></label><div class="common-change-values"><div><small>마켓 최신 조회값</small>${row.observedKnown?comparisonValue(row.observedDisplay??row.observed):'조회 미확인'}</div><div><small>앱 저장값</small>${row.savedKnown?comparisonValue(row.savedDisplay??row.saved):'앱 저장값 없음'}</div></div>${row.reason?`<p class="product-meta">${e(row.reason)}</p>`:''}<div class="common-row"><button type="button" class="btn small" data-resolve-key="${e(row.key)}" data-resolve-action="MARKET" ${disabled||!row.adoptAllowed?'disabled':''}>마켓 조회값을 앱에 저장</button><button type="button" class="btn small" data-resolve-key="${e(row.key)}" data-resolve-action="APP" ${disabled||!row.replayAllowed?'disabled':''}>앱 저장값으로 수정 준비</button></div></div>`;}).join('');
        const bulk=rows.length?`<div class="common-row"><button type="button" class="btn small" data-resolve-bulk="MARKET" ${disabled||!chosen.length||chosen.some(r=>!r.adoptAllowed)?'disabled':''}>선택한 마켓 조회값을 앱에 저장</button><button type="button" class="btn small" data-resolve-bulk="APP" ${disabled||!chosen.length||chosen.some(r=>!r.replayAllowed)?'disabled':''}>선택한 앱 저장값으로 수정 준비</button><span>${chosen.length}개 선택</span></div>`:'';
        return `<section class="common-saved-comparison"><h3>마켓 최신 조회값 / 앱 저장값</h3><p class="product-meta">앱 저장값은 마지막 내부 저장 의도입니다. 선택한 항목만 해당 마켓에 적용하며 공통 기준과 다른 마켓은 유지됩니다.</p>${bulk}${different.length?renderRows(different):`<p>${missing.length?'앱에 저장한 값이 있는 항목에서는 차이가 없습니다.':'확인 가능한 항목의 마켓 조회값과 앱 저장값이 같습니다.'}</p>`}${missing.length?`<details data-comparison-missing ${selected.expanded?'open':''}><summary>기본값·확인 정보 ${missing.length}개</summary><p class="product-meta">앱 저장 의도가 없는 기본값과 동일성을 확인하지 못한 항목입니다. 실제 차이로 분류하지 않습니다.</p>${renderRows(missing)}</details>`:''}</section>`;
    }
    async function resolveComparison(keys,action){
        const target=activeTarget();if(busy||loading||uploads.size||!target?.comparison||expired())return;
        const rows=target.comparison.rows.filter(r=>keys.includes(r.key));if(!rows.length||rows.length!==keys.length)return;
        const seq=sequence,market=viewMarket,oldReference=structuredClone(reference),previous=editState,fields=rows.flatMap(r=>r.fields);
        const signature=JSON.stringify([target.comparison.token,action,keys]);if(target.resolveRequest?.signature!==signature)target.resolveRequest={signature,id:M.uuid()};
        const request={sessionId:editState.session.draftId?editState.session.id:null,token:editState.session.draftId?null:entry.token,revision:editState.session.revision,market,listingVersion:target.listing?.version||1,comparisonToken:target.comparison.token,requestId:target.resolveRequest.id,choices:keys.map(key=>({key,action}))};
        busy=true;validationSequence++;submissions.changed();updateBusy();renderLiveState();
        try{
            const result=await apiPost('/api/marketplaces/editor/resolve',request);if(seq!==sequence)return;
            reference=M.normalize(result.reference);
            // Preserve unsaved common/provider inputs; the selected provider's saved input is authoritative.
            for(const key of ['common','options','stockMode','productQuantity','services','media','delivery','selectedMarkets'])reference[key]=oldReference[key];
            for(const key of Object.keys(oldReference.markets))if(key!==market)reference.markets[key]=oldReference.markets[key];
            const retained=M.afterResolution(previous,result.session,market,fields,result.applied);naverMounted=null;coupangSettingUi.delete(target);installSession(result.session,retained.targets);
            if(!previous.session.draftId){entry.kind='MARKET_ONLY';entry.token=result.token||entry.token;rememberPath();}
            dirty=hasChanges();$('common-validation').hidden=false;$('common-validation').className='editor-validation '+(result.applied?'editor-validation-success':'editor-validation-pending');$('common-validation').textContent=result.applied?(action==='MARKET'?'선택한 마켓 조회값을 앱에 저장했습니다.':'선택한 앱 저장값으로 수정 준비했습니다. 입력 검증과 전송 확인을 진행해 주세요.'):result.reason;
        }catch(err){if(seq===sequence){$('common-error').textContent=err.message;$('common-error').hidden=false;}}
        finally{if(seq===sequence){busy=false;render();}}
    }
    function renderLiveState(){
        const target=activeTarget(),host=$('common-live-state');host.hidden=!target;if(!target)return;
        const state=expired()?'조회 유효기간 만료':target.status==='READY'?(target.mode==='UPDATE'?'마켓 최신 조회값':'신규 등록 입력'):target.status==='UNSUPPORTED'?'공통 기준 미리보기 · 연동 준비 중':target.status==='UNLOADED'?(loading?'마켓 조회 중…':'탭을 선택하면 조회합니다'):target.status==='DELETED'?'마켓 상품 삭제·미존재 확인':'조회 실패';
        const rows=target.working?M.comparisonRows(target):[];
        const html=`<div class="common-live-heading"><strong>${e(marketName(viewMarket))} · ${e(state)}</strong>${target.mode==='CREATE'&&target.status==='READY'?'<span>공통값 자동 적용 · 직접 바꾼 항목은 개별값 유지</span>':''}${target.observedAt?`<span>${e(target.observedAt)}</span>`:''}<button type="button" class="btn small" data-live-refresh ${loading||busy||uploads.size||target.status==='UNSUPPORTED'||(!reference.id&&!entry?.token)?'disabled':''}>최신값 다시 조회</button></div>${target.error||target.refreshError?`<p class="form-error">${e(target.refreshError||target.error)}</p>`:''}${target.status==='DELETED'?'<p>공통 상품과 다른 마켓은 유지됩니다. 기존 입력을 보존해 새 등록을 준비할 수 있습니다.</p><button type="button" class="btn" data-listing-recreate '+(!target.listing?.recreateAllowed||busy||loading?'disabled':'')+'>새로 등록 준비</button>':''}${renderStoredComparison(target)}${rows.length?`<div class="common-selected-changes"><strong>현재 편집 · 전송 준비 변경 ${rows.length}개</strong>${rows.map(row=>{
            const conflict=row.keys.some(key=>target.reviewRequired.has(key)),key=row.key;
            return `<div class="common-change-row"><strong>${e(row.label?(C.spec[row.label]?.[0]||row.label):changeLabel(row.change))}</strong><div class="common-change-values"><div><small>${target.mode==='CREATE'?'공통 기준값':'마켓 최신 조회값'}</small>${comparisonValue(row.before)}</div><div><small>현재 편집값${target.localDirty?' · 미저장':''}</small>${comparisonValue(row.after)}</div></div>${conflict?'<span class="common-review-value">최신값 변경 확인 필요</span><button type="button" class="btn small" data-review-row="'+e(key)+'">내 변경 다시 적용</button>':''}<button type="button" class="btn small" data-reset-row="${e(key)}">${conflict?'최신값 사용':'변경 취소'}</button></div>`;
        }).join('')}</div>`:''}`;
        // Avoid replacing a clicked button during the duplicate change event on input blur.
        if(host._comparisonHtml===html)return;
        host._comparisonHtml=html;host.innerHTML=html;
    }
    function renderApply(){const host=$('common-apply-panel');host.hidden=!!viewMarket||!editState||!allowed('');if(host.hidden)return;const available=[...editState.targets.values()].filter(t=>allowed(t.market));const changes=[{path:'markets.COUPANG.overrides.productName',optionId:null},...['name','productName','brand','manufacturer','origin','material','model','afterService','afterServiceManager','afterServiceTelephone','saleStartedAt','saleEndedAt','taxType','adultOnly'].map(k=>({path:'common.'+k,optionId:null})),...reference.options.flatMap(o=>['name','sku','originalPrice','price',...(reference.stockMode==='OPTION'?['quantity']:[])].map(k=>({path:'options.'+k,optionId:o.id}))),...(reference.stockMode==='PRODUCT'?[{path:'productQuantity',optionId:null}]:[]),...M.deliveryKeys.map(k=>({path:'delivery.'+k,optionId:null})),...[null,...reference.options.map(o=>o.id)].flatMap(optionId=>[{path:'media.images',optionId},{path:'media.contents',optionId}])];$('common-apply-fields').innerHTML=changes.map(c=>`<label><input type="checkbox" data-apply-path="${e(c.path)}" ${c.path.startsWith('media.')&&!c.optionId&&!available.some(t=>t.market==='NAVER'&&t.status==='READY')?'disabled title="이미지·설명은 옵션별로 적용해 주세요."':['common.brand','common.origin','common.material','common.afterService','stockMode','productQuantity'].includes(c.path)&&available.filter(t=>t.status==='READY').every(t=>t.mode==='UPDATE'&&t.market!=='NAVER')?'disabled':''} ${c.optionId?`data-apply-option="${e(c.optionId)}"`:''}><span>${e(changeLabel(c))}</span></label>`).join('');$('common-apply-targets').innerHTML=available.map(t=>`<label class="common-inline-label"><input type="checkbox" data-apply-target="${e(t.market)}" ${t.status!=='READY'?'disabled':''}>${e(marketName(t.market))}</label>`).join('');}
    function nativeChanged(value){
        const target=activeTarget();if(viewMarket!=='NAVER'||!target?.working)return;
        const before=naverLast||structuredClone(value);
        N.edited(target.working,before,value,target.mode==='CREATE'?reference:null);
        const pricing=target.working.markets.NAVER.naver.editorState.pricing,entered=JSON.stringify(value);
        if(target.mode==='CREATE'&&pricing==='COMMON')N.priceProjection(reference,value);
        target.working.markets.NAVER.naver.editorInput=value;
        for(const change of N.changes(before,value))M.recordChange(target,change);
        const calculated=entered!==JSON.stringify(value);
        naverLast=structuredClone(value);naverMounted=naverForm?.value()===value?value:null;dirty=true;target.localDirty=true;
        if(calculated)naverForm?.refreshPrices();
        $('common-validation').hidden=true;updateHeading();renderLiveState();renderNaverControls();submissions.changed();
    }
    function naverSourceOptions(kind){
        const config=activeTarget()?.working?.markets.NAVER.naver,meta=N.state(config||{}),source=meta[kind+'Source'],option=meta[kind+'OptionId'];
        return `<option value="" ${!source?'selected':''}>사용할 원본 선택</option><option value="COMMON" ${source==='COMMON'?'selected':''}>공통 입력</option>`+reference.options.map(o=>`<option value="OPTION:${e(o.id)}" ${source==='OPTION'&&option===o.id?'selected':''}>${e(o.name||'이름 없는 옵션')}</option>`).join('')+`<option value="CUSTOM" ${source==='CUSTOM'?'selected':''}>현재 편집값 유지</option>`;
    }
    function renderNaverControls(){
        const target=activeTarget(),config=target?.working?.markets.NAVER.naver,host=$('common-naver-controls');host.hidden=viewMarket!=='NAVER'||!allowed('')||!config;if(host.hidden)return;
        const meta=N.state(config);
        host.innerHTML=`<div class="common-subheading"><h2>공통값 적용 설정</h2>${target.mode==='CREATE'?'<button type="button" class="btn small" data-naver-inherit="all">공통값 다시 적용</button>':''}</div><div class="common-row"><span>${target.mode==='CREATE'?(meta.pricing==='COMMON'?'공통 정상가·판매가와 즉시할인으로 기본가·추가금 계산':'스마트스토어 개별 가격 사용'):'기존 스마트스토어 조회값 유지'}</span><button type="button" class="btn small" data-naver-inherit="pricing">공통 가격 다시 적용</button></div><div class="common-row"><label class="field"><span>이미지 원본</span><select data-naver-source="image">${naverSourceOptions('image')}</select></label><label class="field"><span>상세설명 원본</span><select data-naver-source="description">${naverSourceOptions('description')}</select></label></div>${meta.pricing==='COMMON'&&N.pricingError(config.editorInput)?'<p role="alert" class="editor-error">'+e(N.pricingError(config.editorInput))+'</p>':''}${meta.unmapped?.length?'<p class="product-meta">확인이 필요한 기존 입력: '+meta.unmapped.map(e).join(' · ')+'</p>':''}`;
        host.querySelectorAll('input,select,button').forEach(el=>el.disabled=busy||naverState.uploading||naverState.categoryLoading||target.status!=='READY'||expired());
    }
    function renderNaver(){
        const target=activeTarget();if(!target?.working)return;
        const config=target.working.markets.NAVER.naver??={},input=config.editorInput??=NM.fresh(),context=target.naverContext||{},limits=context.limits||(target.mode==='UPDATE'?{categoryReadonly:true}:{}),mode=target.mode==='UPDATE'?'edit':'new';
        naverLast=structuredClone(input);
        if(!naverForm){naverForm=window.NaverProductForm.mount({host:$('common-naver-host'),idPrefix:'common-naver',productCode:()=>reference?.common.productCode||'',manageOptionRows:nativeNew(),mode,value:input,limits,optionIdentities:context.optionIdentities||[],onChange:nativeChanged,onBusy:state=>{naverState=state;$('common-save').disabled=busy||state.uploading||state.categoryLoading;document.querySelectorAll('[data-view-market]').forEach(button=>button.disabled=!allowed(button.dataset.viewMarket)||busy||loading||state.uploading||state.categoryLoading);renderNaverControls();submissions.update();},onError:message=>{$('common-error').textContent=message;$('common-error').hidden=!message;}});}
        else if(naverMounted!==input)naverForm.setValue(input,{mode,limits,optionIdentities:context.optionIdentities||[]});
        naverMounted=input;renderNaverControls();
    }
    function nativeErrors(){
        if(submissionMarket()!=='NAVER')return [];
        const target=submissionTarget(),config=target?.working?.markets.NAVER.naver,meta=N.state(config||{});
        const errors=viewMarket==='NAVER'&&naverForm?naverForm.validate():NM.validate(config?.editorInput||NM.fresh(),target?.naverContext?.limits||{},target?.mode==='UPDATE'?'edit':'new');
        if(target?.mode==='CREATE'&&!standalone())for(const kind of ['image','description'])if(!['COMMON','CUSTOM'].includes(meta[kind+'Source'])&&!(meta[kind+'Source']==='OPTION'&&reference.options.some(o=>o.id===meta[kind+'OptionId'])))errors.push({path:kind==='image'?'images':'description',message:(kind==='image'?'이미지':'상세설명')+' 원본을 선택하거나 현재 편집값 유지로 변경해 주세요.'});
        return errors.map(i=>({...i,market:'NAVER'}));
    }
    function draftForSaving(){
        const copy=structuredClone(reference);
        for(const target of [...(editState?.targets.values()||[]),...localTargets.values()])if(target.market==='NAVER'&&target.working?.markets.NAVER.naver){
            const config=target.working.markets.NAVER.naver;
            copy.markets.NAVER.naver={...copy.markets.NAVER.naver,...(target.mode==='CREATE'?{editorInput:NM.input(config.editorInput)}:{}),editorState:structuredClone(config.editorState)};
        }
        if(nativeNew()&&viewMarket==='LOTTEON')copy.markets.LOTTEON.lotteon=structuredClone(activeTarget().working.markets.LOTTEON.lotteon);
        if(nativeNew()&&viewMarket==='ELEVENST')copy.markets.ELEVENST.elevenst=structuredClone(activeTarget().working.markets.ELEVENST.elevenst);
        return copy;
    }
    let elevenstSchema=null,elevenstSchemaRequest=null,elevenstForm=null,elevenstMounted=null;
    function renderElevenst(){
        if(!elevenstSchema){if(!elevenstSchemaRequest)elevenstSchemaRequest=apiGet('/api/marketplaces/elevenst/schema').then(v=>{elevenstSchema=v;elevenstSchemaRequest=null;if(viewMarket==='ELEVENST')render();}).catch(err=>{elevenstSchemaRequest=null;$('common-error').textContent=err.message;$('common-error').hidden=false;});return;}
        const target=activeTarget();if(!target?.working)return;const input=window.ElevenstEditorModel.input(target.working);if(!input)return;
        if(elevenstMounted===input&&elevenstForm){elevenstForm.setBusy(busy||loading||target.status!=='READY');return;}
        elevenstMounted=input;elevenstForm=window.ElevenstProductForm.mount($('common-elevenst-host'),{input,schema:elevenstSchema,mode:target.mode,productCode:reference.common.productCode,manageOptions:nativeNew(),onBusy:value=>{naverState.uploading=value;updateBusy();},onChange:value=>{const before=structuredClone(window.ElevenstEditorModel.input(target.working));if(nativeNew())target.working.markets.ELEVENST.elevenst.editorState=null;window.ElevenstEditorModel.mark(target.working,before,value);target.working.markets.ELEVENST.elevenst.editorInput=value;elevenstMounted=value;for(const c of window.ElevenstEditorModel.diff(before,value))M.recordChange(target,c);draft=target.working;changed();}});
    }
    let lotteonSchema=null,lotteonSchemaRequest=null,lotteonForm=null,lotteonMounted=null;
    function renderLotteon(){
        if(!lotteonSchema){if(!lotteonSchemaRequest)lotteonSchemaRequest=apiGet('/api/marketplaces/lotteon/schema').then(v=>{lotteonSchema=v;lotteonSchemaRequest=null;if(viewMarket==='LOTTEON')render();}).catch(err=>{lotteonSchemaRequest=null;$('common-error').textContent=err.message;$('common-error').hidden=false;});return;}
        const target=activeTarget(),L=window.LotteonEditorModel;if(!target?.working)return;const input=L.input(target.working);if(!input)return;if(lotteonMounted===input&&lotteonForm){lotteonForm.setBusy(busy||loading||target.status!=='READY');return;}
        lotteonMounted=input;lotteonForm=window.LotteonProductForm.mount($('common-lotteon-host'),{input,schema:lotteonSchema,mode:target.mode,productCode:reference.common.productCode,manageOptions:nativeNew(),onBusy:value=>{naverState.uploading=value;updateBusy();},onChange:value=>{const before=structuredClone(L.input(target.working));if(nativeNew())target.working.markets.LOTTEON.lotteon.editorState=null;L.mark(target.working,before,value);lotteonMounted=L.input(target.working);for(const c of L.diff(before,value))M.recordChange(target,c);draft=target.working;changed();}});
    }
    function render(){
        $('common-naver-controls').hidden=viewMarket!=='NAVER'||!allowed('');const eleven=['ELEVENST','LOTTEON'].includes(viewMarket);$('common-elevenst-host').hidden=viewMarket!=='ELEVENST';$('common-lotteon-host').hidden=viewMarket!=='LOTTEON';for(const id of ['common-basic','common-media','common-notice','common-delivery','common-markets','common-naver-host','common-naver-controls'])if(eleven)$(id).hidden=true;

        for(const control of $('common-editor-form').querySelectorAll('button,input,select,textarea')){if(control.closest('#common-naver-host'))continue;delete control.dataset.locked;delete control.dataset.wasDisabled;if(control.closest('.editor-actions')||['common-media-option','common-option-add','common-market-save'].includes(control.id))control.disabled=false;}
        configureCoupangLayout();if(['ELEVENST','LOTTEON'].includes(viewMarket))for(const id of ['common-basic','common-media','common-notice','common-delivery','common-markets','common-naver-host','common-naver-controls'])$(id).hidden=true;renderSelection();renderViews();
        if(viewMarket==='LOTTEON')renderLotteon();else if(viewMarket==='ELEVENST')renderElevenst();else if(viewMarket==='NAVER')renderNaver();else{renderBasic();renderMedia();renderDelivery();renderMarkets();}
        updateHeading();renderLiveState();renderApply();$('common-editor-form').hidden=false;
        const preparation=viewMarket&&activeTarget()?.status==='UNSUPPORTED';
        $('common-save').hidden=false;$('common-save').textContent=viewMarket&&!preparation?'임시 저장':'공통 기준 저장';
        $('common-market-save').textContent=viewMarket==='COUPANG'?'쿠팡에 저장':viewMarket==='NAVER'?'스마트스토어에 저장':viewMarket==='LOTTEON'?'롯데ON에 저장':viewMarket==='ELEVENST'?'11번가에 저장':'마켓에 저장';
        markLocks();const unavailable=viewMarket&&(activeTarget()?.status!=='READY'||expired());
        if(unavailable)for(const control of $('common-editor-form').querySelectorAll('input,select,textarea,button')){const local=preparation&&control.closest('#common-market-fields')&&(control.dataset.commonField?.startsWith('markets.'+viewMarket+'.')||control.dataset.addPair||control.dataset.removePair||control.dataset.naverBrandName!==undefined||control.dataset.naverBrandSearch!==undefined);if(!local&&!control.closest('#common-market-selection')&&!control.hasAttribute('data-common-exit')&&!control.hasAttribute('data-listing-recreate')&&!control.hasAttribute('data-live-refresh')&&!['common-save','common-market-save'].includes(control.id))control.disabled=true;}
        submissions.draft(reference);updateBusy();
    }
    function chooseView(market){if(!allowed(market))return;if((market||null)!==viewMarket){validationSequence++;validationRead?.cancel();$('common-validation').hidden=true;}syncNewTargets();viewMarket=market||null;selectedMarket=viewMarket||'COUPANG';draft=viewMarket&&activeTarget()?.status!=='UNSUPPORTED'?activeTarget()?.working||reference:reference;selectedMarketOption=draft.options[0]?.id;selectedMedia=viewMarket&&activeTarget()?.status==='READY'?draft.options[0]?.id??null:null;render();if(activeTarget()?.status==='UNLOADED'&&!loading){void loadTarget();return;}if(viewMarket==='COUPANG'&&activeTarget()?.status==='READY'&&draft.markets.COUPANG.categoryCode&&rules.get('COUPANG')?.categoryCode!==draft.markets.COUPANG.categoryCode)category();}
    async function loadTarget(){if(loading||!reference?.id||activeTarget()?.status!=='UNLOADED')return;await refreshTarget(true);}
    function installSession(session,preserve=true){clearTimeout(expiryTimer);const remaining=Date.parse(session.expiresAt)-Date.now();expiryTimer=setTimeout(()=>{renderLiveState();submissions.update();},Math.max(0,Math.min(remaining,2147483647)));const previous=preserve instanceof Map?{targets:preserve}:preserve?{targets:new Map([...localTargets,...(editState?.targets||[])])}:null;editState=M.sessionState(reference,session,previous);localTargets.clear();syncNewTargets();chooseView(viewMarket);}
    async function startSession(){if(!reference?.id)return;const seq=sequence,read=window.CoupangRead.start('/api/marketplaces/drafts/'+encodeURIComponent(reference.id)+'/edit-sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(unified?{lazy:true,initialMarket:viewMarket||entry?.initialTab||null}:{})});sessionRead=read;try{const session=await read.promise;if(seq!==sequence)return;installSession(session);}finally{if(sessionRead===read)sessionRead=null;}}
    const standaloneObservation=market=>entry?.mode==='MARKET_EDIT'&&!entry.commonId&&market===entry.originMarket;
    async function refreshStandalone(){
        const next=entry.token?await apiPost('/api/marketplaces/editor/refresh',{token:entry.token}):await apiGet('/api/marketplaces/editor/entry?mode=MARKET_EDIT&market='+encodeURIComponent(entry.originMarket)+'&productId='+encodeURIComponent(entryData.editorProductId));
        entry=next;reference=M.normalize(next.reference);installSession(next.session);
    }
    async function recreateListing(){
        const target=activeTarget();if(busy||loading||target?.status!=='DELETED'||!target.listing?.recreateAllowed)return;
        if(!window.confirm('기존 마켓 연결을 이력에 보존하고 현재 입력으로 새 등록을 준비할까요? 실제 마켓 등록은 실행하지 않습니다.'))return;
        const market=viewMarket;target.recreateRequestId??=M.uuid();busy=true;updateBusy();
        try{
            const targets=M.proposals(editState,[...editState.targets.values()].filter(t=>t.status==='READY').map(t=>t.market)).filter(t=>t.market!==market);targets.push({market,changes:[...target.changes.values()],resolvedConflicts:[]});
            const value=await apiPost('/api/marketplaces/editor/recreate',{sessionId:editState.session.id,revision:reference.revision,market,listingVersion:target.listing.version,requestId:target.recreateRequestId,reference,targets});
            acceptEntry(value);referenceDirty=false;dirty=false;rememberPath();$('common-validation').hidden=false;$('common-validation').className='editor-validation editor-validation-success';$('common-validation').textContent='새 등록 입력 준비 완료 · 아직 마켓에 전송하지 않았습니다.';
        }catch(err){$('common-error').textContent=err.message;$('common-error').hidden=false;}
        finally{busy=false;render();}
    }
    async function refreshTarget(firstLoad=false){if(busy||loading||uploads.size||naverState.uploading||(!reference?.id&&!entry?.token))return;if(!firstLoad&&dirty&&!window.confirm('현재 입력을 유지하면서 마켓의 최신값을 다시 조회할까요?'))return;const market=viewMarket,seq=sequence;loading=true;renderLiveState();updateBusy();submissions.changed();try{if(standaloneObservation(market)){await refreshStandalone();}else if(expired()||!editState)await startSession();else {sessionRead=window.CoupangRead.start('/api/marketplaces/edit-sessions/'+encodeURIComponent(editState.session.id)+'/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({market})});const session=await sessionRead.promise;if(seq!==sequence)return;installSession(session);}if(seq!==sequence)return;dirty=referenceDirty||[...editState.targets.values()].some(t=>t.changes.size);}catch(err){if(seq!==sequence)return;const target=editState?.targets.get(market);if(target){target.refreshError=err.message;target.status='FAILED';}else {$('common-error').textContent=err.message;$('common-error').hidden=false;}}finally{if(seq===sequence){loading=false;sessionRead=null;chooseView(viewMarket);submissions.update();}}}
    async function category(document=draft,target=activeTarget(),confirmed=false){
        if(categoryRead)return;
        const code=document.markets.COUPANG.categoryCode;
        if(!/^\d+$/.test(code)){ruleErrors.set('COUPANG','카테고리 코드를 숫자로 입력해 주세요.');return;}
        const seq=++categorySequence;ruleErrors.set('COUPANG','카테고리 규격 조회 중…');
        try{
            categoryRead=window.CoupangRead.start('/api/marketplaces/coupang/categories/'+encodeURIComponent(code)+'/rules');updateBusy();
            const r=await categoryRead.promise;if(seq!==categorySequence||code!==document.markets.COUPANG.categoryCode)return;
            if(r.categoryCode!==code||!Array.isArray(r.attributes))throw Error('카테고리 규격 응답을 확인해 주세요.');
            const c=ensureCoupang(document),previous=rules.get('COUPANG');
            if(target?.mode!=='UPDATE'){
                const removed=C.categoryRemovals({options:c.options,documents:c.documents},r);
                if(removed.length&&!confirmed&&!window.confirm('새 카테고리에서 제외되는 입력: '+[...new Set(removed)].join(', ')+'\n이 항목을 제외할까요?')){if(previous){document.markets.COUPANG.categoryCode=previous.categoryCode;if(document===draft&&viewMarket==='COUPANG')changed('markets.COUPANG.categoryCode');}return;}
                for(let n=0;n<c.options.length;n++){
                    const o=c.options[n];if(removed.length||previous?.categoryCode!==code)o.attributes=o.attributes.filter(a=>r.attributes.some(v=>v.name===a.name&&v.exposed===a.exposed));
                    for(const a of r.attributes)if(a.required==='MANDATORY'&&!o.attributes.some(v=>v.name===a.name)&&(!a.groupNumber||a.groupNumber==='NONE'))o.attributes.push({name:a.name,value:'',exposed:a.exposed});
                    if(removed.length||previous?.categoryCode!==code)o.certifications=o.certifications.filter(a=>r.certifications.some(v=>v.type===a.type)||['NOT_REQUIRED','PRESENTED_IN_DETAIL_PAGE'].includes(a.type));
                    const notice=r.notices?.find(v=>v.name===o.notices[0]?.category)||r.notices?.[0];
                    for(const cert of r.certifications||[])if(cert.required==='MANDATORY'&&!o.certifications.some(v=>v.type===cert.type))o.certifications.push({type:cert.type,code:'',attachments:[]});
                    if(notice)o.notices=notice.fields.map(f=>({category:notice.name,name:f.name,content:o.notices.find(v=>v.category===notice.name&&v.name===f.name)?.content??''}));
                }
            }
            if(target?.mode!=='UPDATE'&&previous?.categoryCode!==code)c.documents=c.documents.filter(d=>r.documents.some(r=>r.name===d.templateName));
            if(target?.mode!=='UPDATE')for(const doc of r.documents||[])if(C.requiredDocument(doc,{options:c.options.map(o=>({registration:Object.fromEntries(o.registration.map(v=>[v.name,v.value]))}))})&&!c.documents.some(v=>v.templateName===doc.name))c.documents.push({templateName:doc.name,path:''});
            rules.set('COUPANG',r);ruleErrors.delete('COUPANG');
        }catch(err){if(seq===categorySequence)ruleErrors.set('COUPANG',err.message);}
        finally{if(seq===categorySequence){categoryRead=null;renderBasic();renderMedia();renderMarkets();renderDelivery();markLocks();updateBusy();}}
    }
    async function initialize(){
        if(loading)return;loading=true;$('common-loading').hidden=false;$('common-error').hidden=true;$('common-retry').hidden=true;const seq=++sequence;
        try{
            if(unified){const query=new URLSearchParams({mode:entryData.editorMode});for(const [key,value] of [['market',entryData.editorMarket],['productId',entryData.editorProductId],['draftId',entryData.editorDraftId]])if(value)query.set(key,value);const value=await apiGet('/api/marketplaces/editor/entry?'+query);if(seq!==sequence)return;acceptEntry(value);}
            else {reference=edit?M.normalize(await apiGet('/api/marketplaces/drafts/'+encodeURIComponent(edit[1]))):M.fresh(new URLSearchParams(location.search).get('market')||'COUPANG');draft=reference;if(reference.id)await startSession();chooseView(null);}
            if(seq!==sequence)return;dirty=false;referenceDirty=false;
        }catch(err){if(seq===sequence){$('common-error').textContent=err.message;$('common-error').hidden=false;$('common-retry').hidden=false;}}
        finally{if(seq===sequence){loading=false;$('common-loading').hidden=true;if(draft){updateBusy();if(activeTarget()?.status==='UNLOADED')void loadTarget();}else submissions.update();}}
    }
    function acceptEntry(value){entry=value;sendMarkets=entry.originMarket?new Set([entry.originMarket]):null;reference=entry.reference?M.normalize(entry.reference):M.fresh(entry.originMarket||new URLSearchParams(location.search).get('market')||'COUPANG');draft=reference;viewMarket=entry.initialTab||null;editState=null;localTargets.clear();naverMounted=null;if(entry.session)installSession(entry.session,false);else chooseView(viewMarket);if(nativeNew()&&viewMarket==='NAVER'){const config=activeTarget().working.markets.NAVER.naver;if(!entry.reference)config.editorInput=NM.fresh();config.editorState={...N.state(config),pricing:'CUSTOM',optionStructure:'CUSTOM',imageSource:'CUSTOM',descriptionSource:'CUSTOM'};render();}}
    function validationSummary(result){return `<div class="common-validation-markets">${(viewMarket?[viewMarket]:draft.selectedMarkets).map(m=>{const errors=(result.errors||[]).some(v=>!v.market||v.market===m),unverified=(result.unverified||[]).some(v=>!v.market||v.market===m),state=errors?'오류':unverified?'규격 확인 필요':result.valid?(result.scope==='FORMAT'?'입력 형식 확인':'검증 완료'):'문서 검증',style=errors?'fail':unverified?'pending':'ok';return `<span class="badge ${style}">${e(marketName(m)+' · '+state)}</span>`;}).join('')}</div>`;}
    function showResult(result,saved=false){const box=$('common-validation'),errors=result.errors||[],unverified=result.unverified||[];box.hidden=false;box.className='editor-validation '+(errors.length?'form-error':result.valid||saved?'editor-validation-success':'editor-validation-pending');box.innerHTML=`<strong>${saved?'임시 저장 완료':result.valid?(result.scope==='FORMAT'?'입력 형식 확인 완료':'입력 검증 완료'):'입력 확인 결과'}</strong>${saved?'':validationSummary(result)}${!saved&&result.scope==='FORMAT'?'<p class="product-meta">마켓 규격·이미지 실제 규격·수정 권한은 전송 확인에서 추가 검사합니다.</p>':''}${errors.length?`<ul class="common-validation-list">${errors.map((issue,n)=>`<li><button type="button" class="marketplace-product-link" data-issue="${n}">${e((issue.market?marketName(issue.market)+' · ':'')+issue.message)}</button></li>`).join('')}</ul>`:''}${unverified.length?`<ul class="common-validation-list">${unverified.map(v=>`<li>${e((v.market?marketName(v.market)+' · ':'')+v.message)}</li>`).join('')}</ul>`:''}`;if(result.images?.length)box.insertAdjacentHTML('beforeend','<div class="table-scroll"><table><thead><tr><th>마켓·용도</th><th>실제 크기</th><th>용량</th><th>검사</th></tr></thead><tbody>'+result.images.map(i=>'<tr><td>'+e(marketName(i.market)+' · '+(i.role==='DESCRIPTION'?'상세설명':i.role.startsWith('REPRESENTATION')?'대표':'추가'))+'</td><td>'+e(i.width==null?'미확인':i.width+'×'+i.height+'px')+'</td><td>'+e(i.bytes==null?'미확인':i.bytes+'바이트')+'</td><td>'+e({PASS:'적합',FAIL:'부적합',UNVERIFIED:'미확인'}[i.status]||i.status)+'</td></tr>').join('')+'</tbody></table></div>');if(result.warnings?.length)box.insertAdjacentHTML('beforeend','<ul>'+result.warnings.map(i=>'<li>'+e(i.message)+'</li>').join('')+'</ul>');box.querySelectorAll('[data-issue]').forEach(button=>button.addEventListener('click',()=>focusIssue(errors[Number(button.dataset.issue)])));if(errors.length)focusIssue(errors[0]);else box.focus();}
    function focusIssue(issue){if(issue.market==='LOTTEON'){chooseView('LOTTEON');const key=issue.path?.replace(/^lotteon\.product\.fields\./,'');const control=$('common-lotteon-host').querySelector('[data-el-field="'+CSS.escape(key||'')+'"]');$('common-lotteon-host').scrollIntoView({block:'start'});if(control&&!control.disabled){control.setAttribute('aria-invalid','true');control.focus({preventScroll:true});}return;}if(issue.market==='NAVER'){chooseView('NAVER');const path=issue.path?.replace(N.prefix+'fields.','').replace(N.prefix,''),control=$('common-naver-host').querySelector('[data-field="'+CSS.escape(path||'')+'"]');if(control){control.setAttribute('aria-invalid','true');control.closest('section').scrollIntoView({block:'start'});control.focus({preventScroll:true});}else $('common-naver-host').scrollIntoView({block:'start'});return;}if(issue.market&&draft.markets[issue.market]){selectedMarket=issue.market;renderMarkets();$('common-markets').scrollIntoView({block:'start'});}else {const section=/^common\.(material|origin|manufacturer|afterService)$/.test(issue.path||'')&&!viewMarket?'common-notice':issue.path?.startsWith('media')?'common-media':issue.path?.startsWith('delivery')?'common-delivery':issue.path?.startsWith('markets')?'common-markets':'common-basic';$(section).scrollIntoView({block:'start'});}const path=(issue.path||'').replace(/\[(\d+)\]/g,'.$1'),control=document.querySelector('[data-common-field="'+CSS.escape(path)+'"]');if(control&&!control.disabled){control.setAttribute('aria-invalid','true');control.focus({preventScroll:true});}}
    async function validate(){
        if(busy||loading||uploads.size||categoryRead||naverState.uploading||naverState.categoryLoading||!reference)return;
        const target=activeTarget(),markets=viewMarket?[viewMarket]:reference.selectedMarkets,errors=['ELEVENST','LOTTEON'].includes(viewMarket)?[]:viewMarket==='NAVER'?nativeErrors():target?.mode==='UPDATE'?M.validateChanges(target):M.validate(target?.working||reference,true);
        if(errors.length){showResult({valid:false,errors,unverified:[]});return;}
        const seq=++validationSequence;busy=true;updateBusy();$('common-error').hidden=true;$('common-validation').hidden=true;
        try{validationRead=window.CoupangRead.start('/api/marketplaces/editor/validate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({reference:draftForSaving(),token:editState?.session.draftId?null:entry?.token,sessionId:editState?.session.draftId?editState.session.id:null,targets:M.proposals(editState||{targets:localTargets},markets),markets})});const result=await validationRead.promise;if(seq===validationSequence)showResult(result);}
        catch(err){if(seq===validationSequence){$('common-error').textContent=err.message;$('common-error').hidden=false;}}
        finally{validationRead=null;busy=false;updateBusy();}
    }

    async function save(quiet=false){
        if(busy||loading||uploads.size||categoryRead||naverState.uploading||naverState.categoryLoading||!reference)return null;
        const stale=[...(editState?.targets.values()||[])].filter(t=>['UNLOADED','FAILED'].includes(t.status)&&t.changes.size);
        if(stale.length&&!expired()){
            loading=true;updateBusy();
            try{for(const target of stale){if(standaloneObservation(target.market)){await refreshStandalone();}else {const result=await apiPost('/api/marketplaces/edit-sessions/'+encodeURIComponent(editState.session.id)+'/refresh',{market:target.market});installSession(result);}if(editState.targets.get(target.market)?.status!=='READY')throw Error(marketName(target.market)+' 최신 조회에 실패했습니다. 작성한 입력은 유지됩니다.');}}
            catch(err){$('common-error').textContent=err.message;$('common-error').hidden=false;return null;}
            finally{loading=false;updateBusy();}
        }
        const errors=[...(nativeNew()&&['ELEVENST','LOTTEON'].includes(viewMarket)?[]:M.validate(reference)),...(quiet&&submissionMarket()==='COUPANG'?brandErrors(activeTarget()?.working||reference):[]),...(quiet?nativeErrors():[])];
        if(errors.length){showResult({valid:false,errors,unverified:[]});return null;}
        if([...(editState?.targets.values()||[]),...localTargets.values()].some(t=>t.reviewRequired.size)){$('common-error').textContent='외부 변경 항목에서 내 변경 다시 적용 또는 최신값 사용을 선택한 뒤 저장해 주세요.';$('common-error').hidden=false;return null;}
        if(editState&&expired()){$('common-error').textContent='조회 세션이 만료되었습니다. 입력을 유지한 채 최신값을 다시 조회해 주세요.';$('common-error').hidden=false;return null;}
        busy=true;updateBusy();$('common-error').hidden=true;
        try{
            const input=draftForSaving();
            if(unified&&standalone()&&(!reference.id||entry.token&&editState?.session.draftId==null)){
                const targets=M.proposals(editState||{targets:localTargets},[entry.originMarket]);
                const result=await apiPost('/api/marketplaces/editor/drafts',{token:entry.token,market:entry.originMarket,reference:input,targets});
                reference=M.normalize(result.reference);entry.kind=entry.mode==='MARKET_EDIT'?'MARKET_ONLY':entry.originMarket+'_REGISTRATION';installSession(result.session,false);referenceDirty=false;dirty=false;rememberPath();
                if(!quiet)showResult({valid:false,errors:[],unverified:[]},true);return reference;
            }
            if(!editState){
                const saved=reference.id?await apiRequest('/api/marketplaces/drafts/'+encodeURIComponent(reference.id),{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(input)}):await apiPost('/api/marketplaces/drafts',input);
                reference=M.normalize(saved);rememberPath();await startSession();
            }
            const targets=[...editState.targets.values()].filter(t=>t.status==='READY'&&['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(t.market)&&!t.reviewRequired.size).map(t=>({market:t.market,changes:[...(t.mode==='CREATE'&&t.market==='COUPANG'?t.createOverrides:t.changes).values()],resolvedConflicts:[...t.changes.values()].filter(c=>t.resolvedConflicts?.has(M.changeKey(c))).map(c=>({path:c.path,optionId:c.optionId??null}))}));
            const result=await apiRequest('/api/marketplaces/edit-sessions/'+encodeURIComponent(editState.session.id)+'/draft',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({reference:draftForSaving(),targets})});
            reference=M.normalize(result.reference);installSession(result.session,false);
            referenceDirty=false;dirty=false;
            rememberPath();chooseView(viewMarket);
            if(!quiet)showResult({valid:false,errors:[],unverified:[]},true);return reference;
        }catch(err){$('common-error').textContent=err.status===409?'다른 작업에서 정보가 변경되었습니다. 현재 입력은 유지됩니다. 최신값을 다시 조회해 주세요.':err.message;$('common-error').hidden=false;return null;}
        finally{busy=false;updateBusy();}
    }
    function rememberPath(){if(standalone()){if(entry.mode==='MARKET_NEW')history.replaceState(null,'','/marketplaces/'+entry.originMarket.toLowerCase()+'/products/new?draftId='+encodeURIComponent(reference.id));}else history.replaceState(null,'','/marketplaces/products/'+encodeURIComponent(reference.id)+'/edit');}
    function sourceOptions(){
        const target=editState?.targets.get(entry.originMarket),d=target?.working||reference;
        if(entry.originMarket==='COUPANG')return (d.markets.COUPANG.coupang?.options||[]).filter(o=>o.sellerProductItemId).map(o=>({id:o.optionId,name:d.options.find(v=>v.id===o.optionId)?.name||o.sellerProductItemId,remote:o.sellerProductItemId}));
        if(['ELEVENST','LOTTEON'].includes(entry.originMarket))return (entry.optionIdentities||[]).map(o=>({id:o.id,remote:o.remoteId,name:d.options.find(v=>v.id===o.id)?.name||o.remoteId}));
        return (target?.naverContext?.optionIdentities||[]).map(o=>({id:o.id,name:d.markets.NAVER.naver.editorInput.options.find(v=>v.id===o.id)?.values.join(' / ')||o.remoteId,remote:o.remoteId}));
    }
    function linkError(message){$('common-link-error').textContent=message||'';$('common-link-error').hidden=!message;}
    function openLink(mode){
        if(busy||loading||!entry?.token)return;linkMode=mode;linkCandidate=null;linkSequence++;linkPage=0;linkPages=0;linkError('');
        $('common-link-title').textContent=mode==='NEW'?'새 공통 상품으로 가져오기':'기존 공통 상품에 연결';
        $('common-link-content').innerHTML=mode==='NEW'?'<label class="field"><span>공통 상품코드</span><input id="common-link-code" maxlength="200"></label><p class="product-meta">조회한 상품·옵션·이미지로 공통 기준을 만듭니다. 마켓 상품은 변경하지 않습니다.</p>':'<div class="common-row"><label class="field"><span>공통 상품코드 또는 상품명</span><input id="common-link-query" type="search" maxlength="200"></label><button type="button" class="btn" id="common-link-search">검색</button></div><div id="common-link-results"></div><div class="pagination"><button type="button" class="btn small" id="common-link-prev">이전</button><span id="common-link-page"></span><button type="button" class="btn small" id="common-link-next">다음</button></div><div id="common-link-options"></div>';
        $('common-link-confirm').disabled=mode!=='NEW';
        if(mode==='EXISTING'){FormActions.bindEnter($('common-link-query'),$('common-link-search'));$('common-link-search').onclick=()=>{linkPage=0;void searchLinks();};$('common-link-prev').onclick=()=>{if(linkPage){linkPage--;void searchLinks();}};$('common-link-next').onclick=()=>{if(linkPage+1<linkPages){linkPage++;void searchLinks();}};updateLinkPages();}
        $('common-link-dialog').showModal();(mode==='NEW'?$('common-link-code'):$('common-link-query')).focus();
    }
    function updateLinkPages(){if(!$('common-link-page'))return;$('common-link-page').textContent=linkPages?(linkPage+1)+' / '+linkPages:'0 / 0';$('common-link-prev').disabled=linkPage===0;$('common-link-next').disabled=linkPage+1>=linkPages;}
    async function searchLinks(){
        const seq=++linkSequence;linkError('');$('common-link-search').disabled=true;
        try{const page=await apiGet('/api/marketplaces/editor/candidates?query='+encodeURIComponent($('common-link-query').value.trim())+'&page='+linkPage+'&size=10');if(seq!==linkSequence||!$('common-link-dialog').open)return;
            linkPages=page.totalPages;updateLinkPages();$('common-link-results').innerHTML=(page.items||[]).map(d=>'<button type="button" class="btn common-link-candidate" data-link-draft="'+e(d.id)+'">'+e(d.productCode||'상품코드 없음')+' · '+e(d.name||'이름 없는 상품')+'</button>').join('')||'<p class="product-meta">검색된 공통 상품이 없습니다.</p>';
        }catch(err){if(seq===linkSequence)linkError(err.message);}finally{if(seq===linkSequence)$('common-link-search').disabled=false;}
    }
    async function selectLink(id){
        const seq=++linkSequence;$('common-link-confirm').disabled=true;linkError('');
        try{const value=M.normalize(await apiGet('/api/marketplaces/drafts/'+encodeURIComponent(id)));if(seq!==linkSequence||!$('common-link-dialog').open)return;linkCandidate=value;
            $('common-link-options').innerHTML='<h3>'+e(value.common.name||value.common.productCode)+'</h3><p class="product-meta">각 마켓 옵션에 연결할 공통 옵션을 직접 선택하세요. 기존 공통 기준값은 유지됩니다.</p>'+sourceOptions().map(o=>'<label class="field common-link-option"><span>'+e(o.name)+' · '+e(o.remote)+'</span><select data-link-option="'+e(o.id)+'"><option value="">공통 옵션 선택</option>'+value.options.map(v=>'<option value="'+e(v.id)+'">'+e(v.name||'이름 없는 옵션')+(v.sku?' · '+e(v.sku):'')+'</option>').join('')+'<option value="ADD">새 공통 옵션으로 추가</option></select></label>').join('');$('common-link-confirm').disabled=false;
        }catch(err){if(seq===linkSequence)linkError(err.message);}
    }
    async function confirmLink(){
        if(busy||loading||!entry?.token)return;linkError('');const options=[...$('common-link-content').querySelectorAll('[data-link-option]')].map(s=>({sourceOptionId:s.dataset.linkOption,commonOptionId:s.value==='ADD'?null:s.value,add:s.value==='ADD'}));
        if(linkMode==='EXISTING'&&(!linkCandidate||options.some(v=>!v.add&&!v.commonOptionId))){linkError('모든 마켓 옵션의 연결을 직접 선택해 주세요.');return;}
        const used=options.filter(v=>!v.add).map(v=>v.commonOptionId);if(new Set(used).size!==used.length){linkError('같은 공통 옵션을 중복 연결할 수 없습니다.');return;}
        const code=linkMode==='NEW'?$('common-link-code').value.trim():null;if(linkMode==='NEW'&&!code){linkError('공통 상품코드를 입력해 주세요.');return;}
        const target=editState?.targets.get(entry.originMarket);if(target?.reviewRequired.size){linkError('외부 변경 항목을 먼저 확인해 주세요.');return;}
        busy=true;updateBusy();$('common-link-dialog').querySelectorAll('button,input,select').forEach(v=>v.disabled=true);
        try{const value=await apiPost('/api/marketplaces/editor/connect',{token:entry.token,commonId:linkCandidate?.id||null,revision:linkCandidate?.revision??null,productCode:code,options,changes:[...(target?.changes.values()||[])]});acceptEntry(value);referenceDirty=false;dirty=false;$('common-link-dialog').close();}
        catch(err){linkError(err.message);}finally{busy=false;updateBusy();$('common-link-dialog').querySelectorAll('button,input,select').forEach(v=>v.disabled=false);}
    }
    async function addR2Images(kind='images',url=null){
        if(busy||uploads.size)return;const owner=draft,optionId=selectedMedia,market=viewMarket,pending={};uploads.add(pending);updateBusy();
        try{
            const images=url!==null?[await window.R2ImagePicker.resolve(url)]:await window.R2ImagePicker.open({productCode:reference?.common.productCode||'',kind:kind==='contents'?'description':'images'});
            if(!images||draft!==owner||viewMarket!==market)return;
            if(optionId&&!draft.options.some(o=>o.id===optionId))throw Error('이미지의 옵션이 삭제되었습니다. 다시 선택해 주세요.');
            for(const image of images){if(kind==='contents')draft.media.contents.push({id:M.uuid(),type:'IMAGE',value:image.url,optionId});else{const imgs=M.imagesFor(draft,optionId);draft.media.images.push({id:M.uuid(),assetId:null,url:image.url,representative:!imgs.length&&!(market==='COUPANG'&&M.imagesFor(draft,null).some(i=>i.representative)),order:imgs.length,optionId});}}
            mediaChanged(kind,optionId);renderMedia();
        }catch(err){const host=$('common-image-error');if(host){host.textContent=err.message;host.hidden=false;}}
        finally{uploads.delete(pending);updateBusy();}
    }

    function input(control){if(busy||control.disabled||control.type==='radio'&&!control.checked)return;const path=control.dataset.commonField;if(!path)return;const previous=control.dataset.arrayPath?get(control.dataset.arrayPath)?.find(row=>row.name===control.dataset.arrayKey)?.value:get(path),next=path.endsWith('.choices')?control.value.split(',').map(v=>v.trim()).filter(Boolean):control.value;if(M.equal(previous,next,path))return;let changedPath=path;if(control.dataset.arrayPath){const rows=get(control.dataset.arrayPath),key=control.dataset.arrayKey;let row=rows.find(v=>v.name===key);if(!row){row={name:key,value:''};rows.push(row);}row.value=control.value;changedPath=control.dataset.arrayPath;}else if(!viewMarket&&path==='common.brand')M.setCommonBrand(draft,next);else {set(path,next);const match=/^media\.(images|contents)\.(\d+)\.(?:url|value)$/.exec(path);if(match)draft.media[match[1]][Number(match[2])].assetId=null;}changed(changedPath);if(viewMarket==='COUPANG'&&control.hasAttribute('data-cp-shipping-common'))for(const [n,row] of ensureCoupang().options.entries()){if(row.registration.find(v=>v.name==='outboundShippingTimeDay')?.value!==control.value){cpSetField(row.registration,'outboundShippingTimeDay',control.value);changed('markets.COUPANG.coupang.options.'+n+'.registration');}}if(viewMarket==='COUPANG'&&path==='delivery.chargeType'){
            const zeros=control.value==='FREE'?['charge','freeOver']:control.value==='NOT_FREE'?['freeOver','initialReturnCharge']:control.value==='CONDITIONAL_FREE'?['initialReturnCharge']:[];
            for(const key of zeros)if(draft.delivery[key]!=='0'){draft.delivery[key]='0';changed('delivery.'+key);const linked=$(fieldId('delivery.'+key));if(linked)linked.value='0';}
        }if(!viewMarket&&path.startsWith('delivery.'))updateCommonReturnTotal();if(!viewMarket&&path==='common.brand')renderCommonBrandSummary();if(path==='markets.COUPANG.categoryCode'){categorySequence++;categoryRead?.cancel();categoryRead=null;ruleErrors.delete('COUPANG');updateBusy();}if(viewMarket==='COUPANG'&&path.includes('.attributes.')&&path.endsWith('.value'))updateCoupangCombinations();if(path.startsWith('media.contents.'))previews();if(viewMarket==='COUPANG'&&path.includes('.documents.')){const n=Number(path.split('.documents.')[1].split('.')[0]),button=document.querySelector('[data-view-document="'+n+'"]');if(button)button.disabled=!C.url(control.value);}if(viewMarket==='COUPANG'&&path.includes('.notices.')){const checkbox=document.querySelector('[data-notice-reference]');if(checkbox)checkbox.checked=cpOption().row.notices.every(v=>v.content==='상품 상세페이지 참조');}if(viewMarket==='COUPANG'&&path.startsWith('delivery.'))updateCoupangDeliveryDisplay();for(const other of document.querySelectorAll('[data-common-field]'))if(other!==control&&other.dataset.commonField===path){if(other.type==='radio')other.checked=other.value===control.value;else other.value=control.value;}if(viewMarket==='COUPANG'&&control.dataset.arrayKey==='searchTags')renderCoupangTags(control.value);if(path==='common.brand')document.querySelectorAll('[data-coupang-brand]').forEach(el=>el.textContent=control.value||'미입력');if(path==='stockMode'){renderBasic();renderMarkets();markLocks();}}
    $('common-naver-controls').addEventListener('change',ev=>{
        const kind=ev.target.dataset.naverSource,target=activeTarget();if(!kind||!target?.working||busy||naverState.uploading)return;
        const [source,optionId]=ev.target.value.split(':');if(!source)return;
        const input=structuredClone(target.working.markets.NAVER.naver.editorInput);
        if(source!=='CUSTOM'){
            const chosen=N.selectSource(structuredClone(reference),kind,source,optionId).markets.NAVER.naver.editorInput;
            if(kind==='image')input.images=chosen.images;else input.description=chosen.description;
            if(!window.confirm((kind==='image'?'이미지':'상세설명')+'를 선택한 원본으로 바꿀까요?')){renderNaverControls();return;}
        }
        nativeChanged(input);const meta=target.working.markets.NAVER.naver.editorState;meta[kind+'Source']=source;meta[kind+'OptionId']=source==='OPTION'?optionId:null;renderNaver();
    });
    $('common-naver-controls').addEventListener('click',ev=>{
        const group=ev.target.closest('[data-naver-inherit]')?.dataset.naverInherit,target=activeTarget();if(!group||!target?.working||busy||naverState.uploading)return;
        if(!window.confirm(group==='all'?'스마트스토어 개별 입력을 공통값으로 다시 적용할까요?':'기본가와 모든 옵션 추가금을 공통 가격으로 다시 계산할까요?'))return;
        const desired=N.commonValues(reference),input=structuredClone(target.working.markets.NAVER.naver.editorInput);
        if(group==='all'){nativeChanged(desired);const c=target.working.markets.NAVER.naver;c.editorState={...N.state(c),overrides:[],pricing:'COMMON',optionStructure:'COMMON'};}
        else{N.priceProjection(reference,input);nativeChanged(input);target.working.markets.NAVER.naver.editorState.pricing='COMMON';}
        renderNaver();
    });
    $('common-editor-form').addEventListener('input',ev=>input(ev.target));
    $('common-editor-form').addEventListener('change',ev=>{
        const t=ev.target;if(!t.hasAttribute('data-cp-group')||t.disabled||busy)return;
        const c=ensureCoupang(),row=c.options.find(o=>o.optionId===t.dataset.cpGroup),choices=t.dataset.cpChoices.split('|');
        row.attributes=row.attributes.filter(a=>!choices.includes(a.name)||a.name===t.value);
        if(!row.attributes.some(a=>a.name===t.value))row.attributes.push({name:t.value,value:'',exposed:'EXPOSED'});
        changed('markets.COUPANG.coupang.options.'+c.options.indexOf(row)+'.attributes');updateCoupangCombinations();renderBasic();markLocks();
    });
    $('common-editor-form').addEventListener('change',ev=>{
        if(!ev.target.hasAttribute('data-notice-category')||busy||viewMarket!=='COUPANG')return;
        const type=rules.get('COUPANG')?.notices.find(v=>v.name===ev.target.value);if(!type)return;
        const {row,prefix}=cpOption();row.notices=type.fields.map(f=>({category:type.name,name:f.name,content:row.notices.find(v=>v.category===type.name&&v.name===f.name)?.content||''}));
        changed(prefix+'.notices.0.content');renderMarkets();markLocks();
    });
    const commonNoticeBackups=new WeakMap();
    $('common-editor-form').addEventListener('change',ev=>{
        if(!ev.target.hasAttribute('data-notice-reference')||busy||viewMarket!=='COUPANG')return;
        const {row,prefix}=cpOption();for(const notice of row.notices){if(ev.target.checked){if(notice.content!=='상품 상세페이지 참조')commonNoticeBackups.set(notice,notice.content);notice.content='상품 상세페이지 참조';}else if(notice.content==='상품 상세페이지 참조'){notice.content=commonNoticeBackups.get(notice)||'';commonNoticeBackups.delete(notice);}}
        changed(prefix+'.notices');renderMarkets();markLocks();
    });
    function cpSetField(rows,key,value){let entry=rows.find(v=>v.name===key);if(entry)entry.value=value;else rows.push({name:key,value});}
    $('common-market-fields').addEventListener('input',ev=>{if(viewMarket!=='COUPANG'||busy||!ev.target.dataset.certEntry)return;cpSettingUi()[ev.target.dataset.certEntry==='type'?'entryType':'entryCode']=ev.target.value;});
    $('common-editor-form').addEventListener('change',ev=>{
        const t=ev.target,action=t.dataset.cpSettingAction;if(viewMarket!=='COUPANG'||busy||t.disabled||!action||!t.checked)return;
        const {c,row,prefix}=cpOption(),ui=cpSettingUi(),types=rules.get('COUPANG')?.certifications||[];
        if(action==='shipping-time'){ui.shippingTimeMode=t.value;renderDelivery();markLocks();return;}
        if(action==='purchase-limit'){ui.purchaseLimitMode=t.value;if(t.value==='OFF'){cpSetField(row.registration,'maximumBuyForPerson','0');cpSetField(row.registration,'maximumBuyForPersonPeriod','1');changed(prefix+'.registration');}}
        if(action==='sale-period'){ui.salePeriodMode=t.value;if(t.value==='OFF'){const period=C.longSalePeriod();if(!c.settings.some(v=>v.name==='saleStartedAt'&&v.value))cpSetField(c.settings,'saleStartedAt',period.saleStartedAt);cpSetField(c.settings,'saleEndedAt',period.saleEndedAt);changed('markets.COUPANG.coupang.settings');}}
        if(action==='certification'){
            if(t.value!=='TARGET'&&(!types.some(v=>v.type===t.value)||types.some(v=>v.required==='MANDATORY')))return;
            if(F.certificationMode(row.certifications)==='TARGET')coupangCertBackups.set(row,structuredClone(row.certifications));
            row.certifications=t.value==='TARGET'?structuredClone(coupangCertBackups.get(row)||[]):[{type:t.value,code:'',attachments:[]}];ui.certificationSelection=t.value;changed(prefix+'.certifications');
        }
        renderMarkets();markLocks();
    });
    $('common-market-fields').addEventListener('click',ev=>{
        const button=ev.target.closest('button');if(viewMarket!=='COUPANG'||!button||button.disabled||busy)return;
        const data=button.dataset,{c,row,prefix}=cpOption(),ui=cpSettingUi(),types=rules.get('COUPANG')?.certifications||[];
        if(data.addCert!==undefined){
            const type=$('common-cp-cert-type').value,code=$('common-cp-cert-code').value.trim(),rule=types.find(v=>v.type===type);
            const error=message=>{$('common-cp-cert-error').textContent=message;$('common-cp-cert-error').hidden=false;};
            if(!rule||['NOT_REQUIRED','PRESENTED_IN_DETAIL_PAGE'].includes(type)){error('인증 유형을 선택해 주세요.');return;}
            if(rule.dataType==='CODE'&&!code){error('인증번호를 입력해 주세요.');return;}
            if(row.certifications.some(v=>v.type===type)){error('이미 추가된 인증 유형입니다. 아래 인증번호 입력칸에서 수정해 주세요.');return;}
            row.certifications.push({type,code,attachments:[]});ui.entryType='';ui.entryCode='';changed(prefix+'.certifications');renderMarkets();markLocks();
        }
        if(data.removeCert!==undefined&&!types.some(v=>v.type===data.removeCert&&v.required==='MANDATORY')){row.certifications=row.certifications.filter(v=>v.type!==data.removeCert);changed(prefix+'.certifications');renderMarkets();markLocks();}
        if(data.addDocument!==undefined){const name=$('common-cp-document-type').value;if(rules.get('COUPANG')?.documents.some(v=>v.name===name)&&!c.documents.some(v=>v.templateName===name)){c.documents.push({templateName:name,path:''});changed('markets.COUPANG.coupang.documents');renderMarkets();markLocks();}}
        if(data.removeDocument!==undefined){c.documents.splice(Number(data.removeDocument),1);changed('markets.COUPANG.coupang.documents');renderMarkets();markLocks();}
        if(data.viewDocument!==undefined){const doc=c.documents[Number(data.viewDocument)],url=C.url(doc?.path||doc?.vendorPath);if(url)window.open(url,'_blank','noopener,noreferrer');}
    });
    $('common-editor-form').addEventListener('change',ev=>{const t=ev.target;
        if(t.hasAttribute('data-common-long-sale')&&t.checked&&!viewMarket&&!busy){const period=C.longSalePeriod();draft.common.saleStartedAt=period.saleStartedAt.slice(0,16);draft.common.saleEndedAt='2099-12-31T23:59';changed('common.saleStartedAt');changed('common.saleEndedAt');renderMarkets();markLocks();return;}
        if(t.hasAttribute('data-coupang-long-period')&&t.checked&&!busy&&viewMarket==='COUPANG'){
            const rows=ensureCoupang().settings;
            for(const [name,value] of Object.entries(C.longSalePeriod())){let row=rows.find(v=>v.name===name);if(row)row.value=value;else rows.push({name,value});const control=document.querySelector('[data-array-path="markets.COUPANG.coupang.settings"][data-array-key="'+name+'"]');if(control)control.value=value;}
            cpSettingUi().salePeriodMode='ON';changed('markets.COUPANG.coupang.settings');renderMarkets();markLocks();
        }
        if(t.dataset.commonField)input(t);if(t.id==='common-market-option'){selectedMarketOption=t.value;renderMarkets();renderDelivery();markLocks();}});
    $('common-market-selection').addEventListener('change',ev=>{const key=ev.target.dataset.selectedMarket;if(!key||busy||!allowed(key))return;if(sendMarkets){ev.target.checked?sendMarkets.add(key):sendMarkets.delete(key);submissions.changed();return;}if(ev.target.checked&&!reference.selectedMarkets.includes(key))reference.selectedMarkets.push(key);if(!ev.target.checked)reference.selectedMarkets=reference.selectedMarkets.filter(v=>v!==key);referenceDirty=true;dirty=true;updateHeading();submissions.changed();if(!viewMarket){renderBasic();markLocks();updateBusy();}});
    $('common-view-tabs').addEventListener('click',ev=>{const button=ev.target.closest('[data-view-market]');if(button&&!button.disabled&&allowed(button.dataset.viewMarket)&&!busy&&!loading&&!uploads.size&&!naverState.uploading&&!naverState.categoryLoading){categorySequence++;categoryRead?.cancel();categoryRead=null;window.CommonAddressPicker.close();window.CommonCategoryPicker.close();window.CommonBrandPicker.close();window.NaverBrandPicker.close();window.CoupangBrandPicker.close();chooseView(button.dataset.viewMarket);}});
    $('common-view-tabs').addEventListener('keydown',ev=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(ev.key)||busy||loading||uploads.size||naverState.uploading||naverState.categoryLoading)return;ev.preventDefault();const keys=['',...viewMarkets.map(([k])=>k)].filter(allowed),index=keys.indexOf(viewMarket||''),next=ev.key==='Home'?0:ev.key==='End'?keys.length-1:(index+(ev.key==='ArrowRight'?1:-1)+keys.length)%keys.length;chooseView(keys[next]);$('common-view-tabs').querySelector('[aria-selected=true]').focus();});
    $('common-live-state').addEventListener('toggle',ev=>{if(ev.target.matches('[data-comparison-missing]')){const selected=comparisonSelections.get(viewMarket);if(selected)selected.expanded=ev.target.open;}},true);
    $('common-live-state').addEventListener('change',ev=>{if(ev.target.matches('[data-comparison-select]')){const selected=comparisonSelections.get(viewMarket);if(!selected)return;ev.target.checked?selected.keys.add(ev.target.dataset.comparisonSelect):selected.keys.delete(ev.target.dataset.comparisonSelect);renderLiveState();}});
    $('common-live-state').addEventListener('click',ev=>{const one=ev.target.closest('[data-resolve-key]');if(one)return resolveComparison([one.dataset.resolveKey],one.dataset.resolveAction);const bulk=ev.target.closest('[data-resolve-bulk]');if(bulk)return resolveComparison([...(comparisonSelections.get(viewMarket)?.keys||[])],bulk.dataset.resolveBulk);if(ev.target.closest('[data-listing-recreate]'))return recreateListing();const refresh=ev.target.closest('[data-live-refresh]');if(refresh)return refreshTarget();const rowButton=ev.target.closest('[data-reset-row],[data-review-row]');if(rowButton){const entry=activeTarget(),key=rowButton.dataset.resetRow??rowButton.dataset.reviewRow,row=M.comparisonRows(entry).find(r=>r.key===key);if(!row)return;if(rowButton.dataset.reviewRow!==undefined){for(const k of row.keys){(entry.resolvedConflicts??=new Set()).add(k);entry.reviewRequired.delete(k);}renderLiveState();submissions.update();}else{M.resetComparison(entry,row);coupangSettingUi.delete(entry);naverMounted=null;changed();render();}return;}const review=ev.target.closest('[data-review-change]');if(review){(activeTarget().resolvedConflicts??=new Set()).add(review.dataset.reviewChange);activeTarget().reviewRequired.delete(review.dataset.reviewChange);renderLiveState();submissions.update();return;}const button=ev.target.closest('[data-reset-button]');if(button){const entry=activeTarget(),change=entry.changes.get(button.dataset.resetButton);if(change){M.resetChange(entry,change);coupangSettingUi.delete(entry);naverMounted=null;changed();render();}}});
    $('common-live-state').addEventListener('toggle',ev=>{if(ev.target.matches('[data-comparison-missing]')){const selected=comparisonSelections.get(viewMarket);if(selected)selected.expanded=ev.target.open;}},true);
    $('common-live-state').addEventListener('change',ev=>{const key=ev.target.dataset.resetChange;if(key&&!ev.target.checked){const entry=activeTarget(),change=entry.changes.get(key);if(change){M.resetChange(entry,change);coupangSettingUi.delete(entry);naverMounted=null;changed();render();}}});
    $('common-apply').addEventListener('click',()=>{if(busy||loading||!editState||expired())return;const targets=[...document.querySelectorAll('[data-apply-target]:checked')].map(t=>t.dataset.applyTarget),changes=[...document.querySelectorAll('[data-apply-path]:checked')].map(t=>({path:t.dataset.applyPath,optionId:t.dataset.applyOption||null}));const error=$('common-apply-error');error.hidden=true;if(!targets.length||!changes.length){error.textContent='적용할 마켓과 항목을 선택해 주세요.';error.hidden=false;return;}try{editState.reference=M.normalize(reference);for(const market of targets)M.applyReference(editState,market,changes);dirty=referenceDirty||[...editState.targets.values()].some(t=>t.changes.size);updateHeading();submissions.changed();error.textContent='선택한 마켓의 변경 항목에 추가했습니다.';error.className='product-meta';error.hidden=false;}catch(err){error.textContent=err.message;error.className='form-error';error.hidden=false;}});
    $('common-media-option').addEventListener('change',ev=>{selectedMedia=ev.target.value||null;renderMedia();});
    $('common-option-add').addEventListener('click',()=>{if(busy||viewMarket)return;draft.options.push(M.blankOption());changed();renderBasic();renderMedia();renderMarkets();markLocks();});
    $('common-editor-form').addEventListener('click',ev=>{
        const b=ev.target.closest('button');if(!b||b.disabled||busy)return;const d=b.dataset;
        if(d.removeTag!==undefined&&viewMarket==='COUPANG'){
            const control=$('common-market-fields').querySelector('[data-array-key="searchTags"]');
            const tags=control.value.split(',').map(v=>v.trim()).filter(Boolean);tags.splice(Number(d.removeTag),1);control.value=tags.join(', ');input(control);return;
        }
        if(d.categoryLoad!==undefined)category();
        if(d.cpAddOption!==undefined){reference.options.push(M.blankOption());referenceDirty=true;syncNewTargets();chooseView(viewMarket);dirty=true;return;}
        if(d.cpRemoveOption){M.removeOption(reference,d.cpRemoveOption);referenceDirty=true;syncNewTargets();chooseView(viewMarket);dirty=true;return;}
        if(d.removeOption&&!viewMarket){M.removeOption(draft,d.removeOption);if(selectedMedia===d.removeOption)selectedMedia=null;changed();render();}
        if(d.addService!==undefined&&!viewMarket){draft.services.push({id:M.uuid(),name:'',choices:[]});changed();renderBasic();}
        if(d.removeService&&!viewMarket){draft.services=draft.services.filter(s=>s.id!==d.removeService);changed();renderBasic();}
        if(d.addPair){get(d.addPair).push({name:'',value:''});changed(d.addPair+'.0.value');d.addPair.startsWith('options.')?renderBasic():renderMarkets();}
        if(d.removePair){get(d.removePair).splice(Number(d.index),1);changed(d.removePair+'.0.value');d.removePair.startsWith('options.')?renderBasic():renderMarkets();}
        if(d.r2Images!==undefined){void addR2Images();return;}
        if(d.addImage!==undefined){void addR2Images('images',$('common-image-url').value.trim());return;}
        if(d.imageAction){const owner=draft.media.images.find(i=>i.id===d.imageId)?.optionId??null;if(d.imageAction==='represent')M.setRepresentative(draft,d.imageId);if(d.imageAction==='up')M.moveImage(draft,d.imageId,-1);if(d.imageAction==='down')M.moveImage(draft,d.imageId,1);if(d.imageAction==='remove')M.removeImage(draft,d.imageId);mediaChanged('images',owner);renderMedia();}
        if(d.addContent==='IMAGE'){void addR2Images('contents');return;}
        if(d.addContent){draft.media.contents.push({id:M.uuid(),type:d.addContent,value:'',optionId:selectedMedia});mediaChanged('contents',selectedMedia);renderMedia();}
        if(d.removeContent){const owner=draft.media.contents.find(c=>c.id===d.removeContent)?.optionId??null;draft.media.contents=draft.media.contents.filter(c=>c.id!==d.removeContent);mediaChanged('contents',owner);renderMedia();}
    });
    if($('common-link-dialog')){
        document.querySelector('[data-common-import]').onclick=()=>openLink('NEW');document.querySelector('[data-common-link]').onclick=()=>openLink('EXISTING');
        const close=()=>{if(busy)return;linkSequence++;$('common-link-dialog').close();};$('common-link-close').onclick=close;$('common-link-cancel').onclick=close;
        $('common-link-dialog').addEventListener('cancel',ev=>{if(busy)ev.preventDefault();else linkSequence++;});
        $('common-link-content').addEventListener('click',ev=>{const button=ev.target.closest('[data-link-draft]');if(button&&!busy&&!button.disabled)void selectLink(button.dataset.linkDraft);});
        $('common-link-confirm').onclick=confirmLink;
    }
    FormActions.bindExplicitSubmit($('common-editor-form'),$('common-save'),()=>save());$('common-validate').addEventListener('click',validate);$('common-retry').addEventListener('click',initialize);
    document.querySelectorAll('[data-common-exit]').forEach(link=>link.addEventListener('click',ev=>{ev.preventDefault();if((dirty||uploads.size)&&!window.confirm('저장하지 않은 입력을 버리고 목록으로 돌아갈까요?'))return;dirty=false;location.assign('/marketplaces');}));
    $('common-editor-form').addEventListener('error',ev=>{if(ev.target.tagName==='IMG'){ev.target.hidden=true;if(ev.target.nextElementSibling)ev.target.nextElementSibling.hidden=false;}},true);
    window.addEventListener('beforeunload',ev=>{if(dirty||uploads.size||naverState.uploading){ev.preventDefault();ev.returnValue='';}});window.addEventListener('pagehide',()=>{sequence++;categorySequence++;categoryRead?.cancel();validationRead?.cancel();sessionRead?.cancel();clearTimeout(expiryTimer);naverForm?.destroy();});
    const layoutObserver=new ResizeObserver(()=>{
        $('main-content').style.setProperty('--common-tabs-height',$('common-view-tabs').getBoundingClientRect().height+'px');
        $('main-content').style.setProperty('--common-actions-height',document.querySelector('#common-editor-form .editor-actions').getBoundingClientRect().height+'px');
    });
    layoutObserver.observe($('common-view-tabs'));layoutObserver.observe(document.querySelector('#common-editor-form .editor-actions'));
    window.addEventListener('pagehide',()=>layoutObserver.disconnect());
    initialize();
})();
