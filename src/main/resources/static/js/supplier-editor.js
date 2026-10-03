/* 설정과 상품 상세에서 사용하는 공통 매장 선택창. 매장 등록과 선호 선택은 별도 동작이다. */
(() => {
    const e=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const name=s=>[s?.retailer,s?.name].filter(Boolean).join(' · ');
    const groups=data=>data.groups||[...new Set(data.rules.map(r=>r.mall))].map(mall=>{const rules=data.rules.filter(r=>r.mall===mall),all=rules.some(r=>!r.storeId);return {mall,scope:all?'ALL':'STORES',storeIds:all?[]:rules.map(r=>r.storeId)};});
    const request=(method,url,body)=>apiRequest(url,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    let active=false;
    async function open(options) {
        if(active)return;active=true;
        let data,malls,candidates;
        try {[data,malls,candidates]=await Promise.all([apiGet('/api/settings/preferred-suppliers'),apiGet('/api/settings/suppliers'),options.admin?apiGet('/api/settings/supplier-store-identities'):Promise.resolve([])]);}
        catch(err){active=false;throw err;}
        if(options.canOpen?.()===false){active=false;return;}
        const assignment=!!options.listing,admin=!!options.admin;
        let mall=options.mall||malls[0]?.code,scope='ALL',branchRequired=true,selected=[],busy=false,editing=null,officialDraft=false,channelPreview=null;
        const dialog=document.createElement('dialog');dialog.className='attendance-dialog supplier-editor';dialog.setAttribute('aria-labelledby','supplier-editor-title');
        dialog.innerHTML=`<h2 id="supplier-editor-title">${assignment?'판매글 매장 지정':'선호 매입처 설정'}</h2>
          <form class="form" id="supplier-editor-form"><fieldset class="supplier-editor-fields">
          <div id="supplier-editor-navigation" class="toolbar"><label class="field">쇼핑몰<select id="supplier-editor-mall" ${assignment?'disabled':''}>${malls.map(m=>`<option value="${e(m.code)}">${e(m.name)}</option>`).join('')}</select></label>
          ${assignment?'':`<div class="supplier-branch-policy"><button type="button" class="managed-switch" role="switch" aria-checked="true" id="supplier-editor-branch"><span></span><span>지점 구분 사용</span></button><p id="supplier-editor-branch-hint" class="field-hint"></p></div><label class="field" id="supplier-editor-scope-field">허용 범위<select id="supplier-editor-scope"><option value="ALL">전체</option><option value="STORES">특정 매장만</option></select></label>`}</div>
          <section id="supplier-editor-picker"><p id="supplier-editor-selection-label" class="field-hint">선택한 매장</p><div id="supplier-editor-tags" class="supplier-store-tags" aria-live="polite" tabindex="-1"></div>
          <div id="supplier-editor-results" class="supplier-store-options"></div><p id="supplier-editor-added" class="form-success" role="status" hidden></p>
          ${admin?'<button type="button" class="btn" id="supplier-editor-new">새 매장 추가</button><button type="button" class="btn" id="supplier-editor-official-new" hidden>공식몰 추가</button>':'<p class="field-hint">필요한 매장이 없다면 관리자에게 등록을 요청해 주세요.</p>'}
          <div id="supplier-editor-draft" class="supplier-store-draft" hidden>
            <h3 id="supplier-editor-draft-title">새 매장 추가</h3>
            <div id="supplier-editor-channel-fields" hidden><label class="field">공식몰 상품 링크<input type="url" id="supplier-editor-product-url" maxlength="2000" placeholder="https://brand.naver.com/스토어/products/상품번호"></label><button type="button" class="btn" id="supplier-editor-channel-check">판매채널 확인</button><p id="supplier-editor-channel-result" class="field-hint" role="status"></p></div>
            <label class="field" id="supplier-editor-retailer-field">백화점 이름<input id="supplier-editor-retailer" maxlength="120" list="supplier-editor-retailers"></label>
            <datalist id="supplier-editor-retailers"><option value="현대백화점"><option value="롯데백화점"><option value="신세계백화점"><option value="갤러리아백화점"><option value="AK플라자"></datalist>
            <p id="supplier-editor-retailer-fixed" class="field-hint" hidden>현대백화점</p>
            <label class="field"><span id="supplier-editor-name-label">지점 이름</span><input id="supplier-editor-name" maxlength="120" placeholder="목동점 등"></label>
            <p class="field-hint">등록한 매장은 이 창을 취소해도 유지됩니다.</p>
            <p id="supplier-editor-draft-error" class="form-error" role="alert" hidden></p><div class="attendance-actions"><button type="button" class="btn" id="supplier-editor-draft-cancel">뒤로</button><button type="button" class="btn" id="supplier-editor-draft-add">매장 등록</button></div>
          </div></section>
          <div id="supplier-editor-preference" ${assignment&&admin?'':'hidden'}><label><input type="checkbox" id="supplier-editor-add-preferred"> 선호 매입처에도 추가</label><p id="supplier-editor-preferred-hint" class="field-hint"></p></div>
          <div id="supplier-editor-identities"></div>
          <p id="supplier-editor-error" class="form-error" role="alert" hidden></p>
          <div id="supplier-editor-actions" class="attendance-actions"><button type="button" class="btn" id="supplier-editor-cancel">취소</button><button class="btn primary" id="supplier-editor-save">${assignment?'매장 지정 저장':'선호 매입처 저장'}</button></div>
          </fieldset></form>`;
        document.body.append(dialog);
        const $=id=>dialog.querySelector('#supplier-editor-'+id),error=message=>{ $('error').textContent=message||'';$('error').hidden=!message;};
        const invalid=(message,field)=>{const err=new Error(message);err.field=field;throw err;};
        const clearDraftError=()=>{$('draft-error').hidden=true;$('draft-error').textContent='';for(const id of ['name','retailer','product-url']){$(id).removeAttribute('aria-invalid');$(id).removeAttribute('aria-describedby');}};
        let focusAfterAction=null;
        const availableStores=()=>[...data.stores,...(admin?(data.companyChoices||[]):[]).filter(c=>!data.stores.some(s=>s.mall===c.mall&&s.kind==='COMPANY')).map(c=>({...c,id:'draft-company:'+c.mall,draft:true}))];
        const selectedStores=()=>selected.map(id=>availableStores().find(s=>s.id===id)).filter(Boolean);
        const allowed=id=>data.rules.some(r=>r.mall===mall&&(!r.storeId||r.storeId===id));
        function preferenceHint(){if(!assignment||!admin)return;const already=data.rules.some(r=>r.mall===mall&&!r.storeId)||(selected.length===1&&allowed(selected[0]));$('add-preferred').disabled=already;if(already)$('add-preferred').checked=false;$('preferred-hint').textContent=already?'이미 선호 대상입니다.':'';$('preferred-hint').hidden=!already;}
        function render(){
            $('picker').hidden=!branchRequired||!assignment&&scope==='ALL';
            if(admin){$('new').textContent=mall==='NAVER_SMART_STORE'?'백화점 지점 추가':'새 매장 추가';$('official-new').hidden=mall!=='NAVER_SMART_STORE';}
            if(!assignment){
                $('branch').setAttribute('aria-checked',String(branchRequired));$('scope-field').hidden=!branchRequired;
                const wasSpecific=groups(data).find(g=>g.mall===mall)?.scope==='STORES';
                $('branch-hint').textContent='저장하면 특정 매장 허용이 쇼핑몰 전체 허용으로 변경됩니다.';$('branch-hint').hidden=branchRequired||!wasSpecific;
            }
            $('retailers').innerHTML=[...new Set(['현대백화점','롯데백화점','신세계백화점','갤러리아백화점','AK플라자',...data.stores.map(s=>s.retailer).filter(Boolean)])].map(v=>`<option value="${e(v)}">`).join('');
            $('tags').innerHTML=selectedStores().map(s=>({key:s.id,label:name(s)})).map(s=>`<button type="button" class="supplier-store-tag" data-remove-store="${e(s.key)}" aria-label="${e(s.label)} 선택 해제">${e(s.label)} <span aria-hidden="true">×</span></button>`).join('');
            $('selection-label').textContent=`선택한 매장 ${selected.length}곳`;
            const stores=availableStores().filter(s=>s.mall===mall&&!(mall==='LOTTE_ON'&&s.kind==='SELLER'));
            $('results').innerHTML=stores.map(s=>`<div class="supplier-store-option"><button type="button" class="btn small" data-choose-store="${e(s.id)}" aria-pressed="${selected.includes(s.id)}">${e(name(s))}${selected.includes(s.id)?' ✓':''}</button><span class="field-hint">${e({ONLINE:'온라인 매장',SELLER:'판매자',BRANCH:'지점',COMPANY:'판매업체',BRAND_STORE:'공식 브랜드몰'}[s.kind])}</span>${admin&&s.kind!=='COMPANY'?`<div class="supplier-store-tools"><button type="button" class="btn small" data-edit-store="${e(s.id)}">이름 수정</button><button type="button" class="btn small danger" data-delete-store="${e(s.id)}">삭제</button></div>`:''}</div>`).join('')||'<p class="field-hint">등록된 매장이 없습니다.</p>';
            preferenceHint();renderIdentities();
        }
        function renderIdentities(){
            const list=admin&&branchRequired?candidates.filter(c=>c.mall===mall):[];
            $('identities').innerHTML=list.length?`<details class="supplier-identity-review"><summary>매장 연결 확인 (${list.length})</summary>${list.map((c,i)=>`<div class="supplier-identity-row"><strong>${e(name(c.evidence))}</strong><label class="field">연결할 기존 매장<select data-identity-target="${i}"><option value="">매장 선택</option>${data.stores.filter(s=>s.mall===mall&&s.kind===c.evidence.kind&&(!s.retailer||s.retailer===c.evidence.retailer)).map(s=>`<option value="${e(s.id)}">${e(name(s))}</option>`).join('')}</select></label><button type="button" class="btn small" data-bind-identity="${i}">동일 매장으로 연결</button></div>`).join('')}</details>`:'';
        }
        function resetMall(){branchRequired=data.branchRequirements?.[mall]!==false;clearDraftError();$('added').hidden=true;selected=[];editing=null;$('draft').hidden=true;
            if(assignment){selected=options.listing.store?[options.listing.store.id]:[];}
            else{const group=groups(data).find(g=>g.mall===mall);scope=group?.scope||'ALL';selected=[...(group?.storeIds||[])];if(!branchRequired){scope='ALL';selected=[];}$('scope').value=scope;}
            render();error(null);
        }
        function draftView(open){
            $('draft').hidden=!open;dialog.classList.toggle('is-store-editing',open);
            $('title').textContent=open?(editing?'매장 이름 수정':officialDraft?'공식몰 추가':'새 매장 추가'):(assignment?'판매글 매장 지정':'선호 매입처 설정');
            dialog.scrollTop=0;
        }
        function backToStores(){draftView(false);editing=null;officialDraft=false;channelPreview=null;$('product-url').value='';clearDraftError();error(null);$('new')?.focus();}
        function editDraft(store=null,official=false){
            officialDraft=official;channelPreview=null;$('channel-fields').hidden=!official;$('product-url').value='';$('channel-result').textContent='';$('draft-add').disabled=official;
            clearDraftError();error(null);$('added').hidden=true;editing=store;$('draft').hidden=false;
            const branch=!official&&(!store||store.kind==='BRANCH'),fixed=branch&&mall==='HI_THEHYUNDAI';
            $('retailer-field').hidden=!branch||fixed;$('retailer-fixed').hidden=!fixed;
            $('name-label').textContent=branch?'지점 이름':official||store?.kind==='BRAND_STORE'?'표시 이름':'매장 이름';
            $('name').placeholder=branch?'목동점, 천호점 등':'매장 이름';
            $('name').value=store?.name||'';$('retailer').value=fixed?'현대백화점':store?.retailer||'';
            draftView(true);$('draft-title').textContent=store?'매장 이름 수정':official?'공식몰 추가':'새 매장 추가';$('draft-add').textContent=store?'이름 변경 저장':'매장 등록';
            (official?$('product-url'):branch&&!fixed?$('retailer'):$('name')).focus();
        }
        async function reload(){data=await apiGet('/api/settings/preferred-suppliers');candidates=admin?await apiGet('/api/settings/supplier-store-identities'):[];selected=selected.filter(id=>availableStores().some(s=>s.id===id));render();}
        async function perform(fn){
            if(busy)return;busy=true;focusAfterAction=null;dialog.querySelector('fieldset').disabled=true;error(null);clearDraftError();
            try{await fn();}catch(err){
                if(err.field){$('draft-error').textContent=err.message;$('draft-error').hidden=false;$(err.field).setAttribute('aria-invalid','true');$(err.field).setAttribute('aria-describedby','supplier-editor-draft-error');focusAfterAction=$(err.field);}
                else if(err.status===409){
                    try{
                        await reload();
                        if(assignment){const d=await apiGet('/api/products/'+options.productId),c=d.comparison;
                            const current=[...c.groups.flatMap(g=>g.listings),...c.pending,...c.excluded].find(l=>l.id===options.listing.id);
                            if(!current)throw Error('판매글이 변경되었습니다. 창을 닫고 상품을 다시 조회해 주세요.');
                            options.listing=current;
                        }
                        if(officialDraft){channelPreview=null;$('draft-add').disabled=true;$('channel-result').textContent='';$('draft-error').textContent='설정이 변경되었습니다. 판매채널을 다시 확인해 주세요.';$('draft-error').hidden=false;}else error(err.message+' 최신 정보를 불러왔습니다. 내용을 확인하고 다시 저장해 주세요.');
                    }catch(loadError){error('최신 정보를 확인하지 못했습니다. 창을 다시 열어 주세요. '+loadError.message);}
                }else if(!$('draft').hidden){$('draft-error').textContent=err.message;$('draft-error').hidden=false;focusAfterAction=$('draft-error');}else {error(err.message);focusAfterAction=$('error');}
            }finally{busy=false;dialog.querySelector('fieldset').disabled=false;if(focusAfterAction&&dialog.open){focusAfterAction.focus({preventScroll:true});focusAfterAction.scrollIntoView({block:'nearest'});}}
        }
        $('mall').value=mall;resetMall();
        $('mall').addEventListener('change',()=>{mall=$('mall').value;resetMall();});
        $('branch')?.addEventListener('click',()=>{branchRequired=!branchRequired;scope='ALL';selected=[];$('scope').value=scope;draftView(false);render();});
        $('scope')?.addEventListener('change',()=>{scope=$('scope').value;$('draft').hidden=true;render();});
        for(const id of ['name','retailer','product-url'])$(id).addEventListener('input',clearDraftError);
        $('new')?.addEventListener('click',()=>editDraft());
        $('official-new')?.addEventListener('click',()=>editDraft(null,true));
        $('product-url').addEventListener('input',()=>{channelPreview=null;$('channel-result').textContent='';$('draft-add').disabled=true;});
        $('channel-check').addEventListener('click',()=>perform(async()=>{
            channelPreview=null;$('draft-add').disabled=true;$('channel-result').textContent='판매채널 확인 중…';
            try{
                const preview=await apiPost('/api/settings/supplier-stores/channel-preview',{productUrl:$('product-url').value.trim()});
                channelPreview=preview;$('channel-result').textContent=`판매채널 ${preview.channelName} · 채널 ID ${preview.channelUid}`;
                $('name').value=preview.channelName;$('draft-add').disabled=false;focusAfterAction=$('name');
            }catch(err){$('channel-result').textContent='';invalid(err.message,'product-url');}
        }));$('draft-cancel').addEventListener('click',backToStores);
        $('draft-add').addEventListener('click',()=>perform(async()=>{
            const wasEditing=!!editing;
            const kind=editing?.kind||(officialDraft?'BRAND_STORE':'BRANCH');
            const input={mall,kind,name:$('name').value.trim(),retailer:kind==='BRANCH'?$('retailer').value.trim():'',sellerKey:null};
            if(!input.name)invalid(input.kind==='BRANCH'?'지점 이름을 입력해 주세요.':'매장 이름을 입력해 주세요.','name');
            if(input.kind==='BRANCH'&&['NAVER_SMART_STORE','LOTTE_ON'].includes(mall)&&!input.retailer)invalid('백화점 이름을 입력해 주세요.','retailer');
            if(officialDraft){if(!channelPreview)invalid('먼저 판매채널을 확인해 주세요.','product-url');Object.assign(input,{productUrl:$('product-url').value.trim(),expectedChannelUid:channelPreview.channelUid,preferenceRevision:channelPreview.preferenceRevision});}
            const saved=await apiPost('/api/settings/supplier-stores'+(editing?'/'+editing.id:''),{...input,revision:editing?.revision});
            editing=null;officialDraft=false;channelPreview=null;await reload();
            draftView(false);render();$('added').textContent=wasEditing?'매장 이름을 변경했습니다.':`${name(saved)} 매장을 등록했습니다. 목록에서 눌러 선택한 뒤 ‘${assignment?'매장 지정 저장':'선호 매입처 저장'}’을 눌러 주세요.`;$('added').hidden=false;
            focusAfterAction=[...dialog.querySelectorAll('[data-choose-store]')].find(el=>el.dataset.chooseStore===saved.id)||$('results');

        }));
        dialog.addEventListener('click',ev=>{const b=ev.target.closest('button');if(!b||busy)return;
            if(b.dataset.chooseStore){const id=b.dataset.chooseStore;if(assignment){selected=[id];}else selected=selected.includes(id)?selected.filter(v=>v!==id):[...selected,id];render();[...dialog.querySelectorAll('[data-choose-store]')].find(el=>el.dataset.chooseStore===id)?.focus();}
            if(b.dataset.removeStore){const key=b.dataset.removeStore;selected=selected.filter(id=>id!==key);render();$('tags').focus();}
            if(b.dataset.editStore)editDraft(data.stores.find(s=>s.id===b.dataset.editStore));
            if(b.dataset.deleteStore){const s=data.stores.find(s=>s.id===b.dataset.deleteStore);if(confirm(`${name(s)} 매장을 삭제할까요? 사용 중인 매장은 삭제되지 않습니다.`))perform(async()=>{await apiPost(`/api/settings/supplier-stores/${s.id}/delete`,{revision:s.revision});await reload();});}
            if(b.dataset.bindIdentity!==undefined){const i=Number(b.dataset.bindIdentity),c=candidates.filter(c=>c.mall===mall)[i],id=dialog.querySelector(`[data-identity-target="${i}"]`).value,target=data.stores.find(s=>s.id===id);if(!target){error('연결할 매장을 선택해 주세요.');return;}if(confirm(`${name(c.evidence)}을 ${name(target)} 매장과 연결할까요?`))perform(async()=>{await apiPost(`/api/settings/supplier-stores/${id}/identity`,{revision:target.revision,supplierId:c.supplierId,observedRunId:c.observedRunId});await reload();});}
        });
        $('cancel').addEventListener('click',()=>dialog.close());
        dialog.addEventListener('cancel',ev=>{if(busy||!$('draft').hidden){ev.preventDefault();if(!busy)backToStores();}});
        dialog.addEventListener('keydown',ev=>{if(ev.key==='Escape'){ev.preventDefault();ev.stopPropagation();if(!busy){if(!$('draft').hidden)backToStores();else dialog.close();}}},true);
        dialog.addEventListener('click',ev=>{if(ev.target===dialog&&!busy){const r=dialog.getBoundingClientRect();if(ev.clientX<r.left||ev.clientX>r.right||ev.clientY<r.top||ev.clientY>r.bottom)dialog.close();}});
        dialog.addEventListener('close',()=>{active=false;dialog.remove();options.onClosed?.();},{once:true});
        dialog.querySelector('form').addEventListener('submit',ev=>{ev.preventDefault();if(!$('draft').hidden){$('draft-add').click();return;}perform(async()=>{
            if(assignment){if(selected.length!==1)throw Error('지정할 매장을 한 곳 선택해 주세요.');
                await apiPost(`/api/products/${options.productId}/suppliers/${options.listing.id}/store`,{revision:options.listing.revision,storeId:selectedStores()[0].draft?null:selected[0],newStore:selectedStores()[0].draft?{mall,kind:'COMPANY',name:selectedStores()[0].name}:null,addPreferred:admin&&$('add-preferred').checked,preferenceRevision:data.revision});
            }else{if(scope==='STORES'&&!selected.length)throw Error('매장을 한 곳 이상 선택해 주세요.');await request('PUT','/api/settings/preferred-suppliers/'+mall,{revision:data.revision,scope,storeIds:scope==='ALL'?[]:selectedStores().filter(s=>!s.draft).map(s=>s.id),newStores:scope==='ALL'?[]:selectedStores().filter(s=>s.draft).map(({mall,kind,name})=>({mall,kind,name})),branchRequired});}
            dialog.close();try{await options.onSaved?.();}catch(err){options.onError?.(err);}
        });});
        dialog.showModal();
    }
    window.SupplierEditor={open,groups,storeName:name,request};
})();
