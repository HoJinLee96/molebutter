/* One query, separate marketplace choices. Applying is the only document mutation. */
(() => {
    'use strict';
    const e=AppUI.escape,providers=[
        {id:'COUPANG',label:'쿠팡',paged:true,url:(name,page)=>'/api/marketplaces/coupang/brands?brandName='+encodeURIComponent(name)+'&page='+page},
        {id:'NAVER',label:'스마트스토어',url:name=>'/api/marketplaces/naver/metadata/brands?name='+encodeURIComponent(name)},
        {id:'LOTTEON',label:'롯데ON',note:'최대 100개를 조회합니다. 원하는 브랜드가 없으면 검색어를 더 구체적으로 입력해 주세요.',url:name=>'/api/marketplaces/lotteon/metadata/brands?name='+encodeURIComponent(name)},
        {id:'ESM',label:'G마켓 · 옥션 (ESM)',pending:'브랜드 검색 연결 준비 중'},
        {id:'ELEVENST',label:'11번가',paged:true,textBrand:true,url:(name,page)=>'/api/marketplaces/elevenst/brands?name='+encodeURIComponent(name)+'&page='+page}
    ];
    let dialog,active=null,generation=0,query='',states=new Map(),choices={},changes={};
    function cancelReads(){for(const s of states.values())s.read?.cancel();}
    function close(){generation++;cancelReads();const previous=active;active=null;if(dialog?.open)dialog.close();previous?.onClose?.();}
    function ensure(){
        if(dialog)return;
        dialog=document.createElement('dialog');dialog.className='common-brand-dialog';dialog.setAttribute('aria-labelledby','common-brand-title');
        dialog.innerHTML='<div class="common-subheading"><h2 id="common-brand-title">마켓별 브랜드 선택</h2><button type="button" class="btn" data-common-brand-close>닫기</button></div><form data-common-brand-form><label class="field"><span>공통 브랜드명</span><input type="search" maxlength="200" data-common-brand-query required></label><button type="submit" class="btn primary">전체 검색</button></form><p class="common-brand-note">같은 검색어로 조회한 결과에서 마켓별 브랜드를 선택하세요. 기존 선택은 직접 바꾸거나 해제할 때까지 유지됩니다.</p><div class="common-brand-providers" data-common-brand-providers></div><p class="form-error" role="alert" data-common-brand-error hidden></p><div class="common-brand-actions"><button type="button" class="btn" data-common-brand-cancel>취소</button><button type="button" class="btn primary" data-common-brand-apply>선택한 브랜드 적용</button></div>';
        document.body.append(dialog);dialog.querySelector('[data-common-brand-close]').onclick=close;dialog.querySelector('[data-common-brand-cancel]').onclick=close;dialog.addEventListener('cancel',close);
        dialog.querySelector('form').onsubmit=event=>{event.preventDefault();search();};
        FormActions.bindEnterSubmit(dialog.querySelector('form'),dialog.querySelector('button[type=submit]'));
        dialog.querySelector('[data-common-brand-query]').oninput=updateApply;
        dialog.querySelector('[data-common-brand-providers]').onclick=event=>{
            const button=event.target.closest('[data-brand-market]');if(!button||!active)return;
            const market=button.dataset.brandMarket,s=states.get(market);if(!s||s.read||s.locked)return;
            if(button.dataset.brandIndex!==undefined){const brand=s.items[Number(button.dataset.brandIndex)];if(brand){choices[market]=brand;changes[market]=brand;render();}}
            else if(button.dataset.brandText!==undefined){choices[market]={id:'',name:query};changes[market]=choices[market];render();}
            else if(button.dataset.brandClear!==undefined){choices[market]=null;changes[market]=null;render();}
            else if(button.dataset.brandPage!==undefined)load(s,Number(button.dataset.brandPage));
            else if(button.dataset.brandRetry!==undefined)load(s,s.page||1);
        };
        dialog.querySelector('[data-common-brand-apply]').onclick=()=>{
            if(!active||isBusy()||!query||dialog.querySelector('[data-common-brand-query]').value.trim()!==query)return;
            const callback=active.onApply,payload={name:query,selections:structuredClone(changes)};
            try{callback(payload);close();}catch(error){const host=dialog.querySelector('[data-common-brand-error]');host.textContent=error.message;host.hidden=false;}
        };
    }
    const isBusy=()=>[...states.values()].some(s=>s.read);
    function updateApply(){dialog.querySelector('[data-common-brand-apply]').disabled=isBusy()||!query||dialog.querySelector('[data-common-brand-query]').value.trim()!==query;}
    function render(){
        dialog.querySelector('[data-common-brand-providers]').innerHTML=providers.filter(p=>!active.markets||active.markets.includes(p.id)).map(p=>{
            const s=states.get(p.id),selected=choices[p.id];
            const message=s.locked?'등록된 상품의 브랜드는 변경할 수 없습니다.':p.pending|| (s.read?'검색 중…':s.error||s.message||'');
            return `<section class="common-brand-provider"><div class="common-brand-provider-heading"><h3>${e(p.label)}</h3>${selected?`<button type="button" class="btn small" data-brand-market="${p.id}" data-brand-clear ${s.locked||s.read?'disabled':''}>선택 해제</button>`:''}</div><p class="common-brand-selected">${selected?`${e(selected.name)} <span>${e(selected.id)}</span>`:'선택 없음'}</p><p role="status" class="${s.error?'form-error':'product-meta'}">${e(message)}</p>${p.note?`<p class="product-meta">${e(p.note)}</p>`:''}${p.textBrand&&!s.locked&&query?`<button type="button" class="btn small" data-brand-market="${p.id}" data-brand-text ${s.read?'disabled':''}>브랜드명 ‘${e(query)}’ 텍스트 사용</button>`:''}${!s.locked&&!p.pending?s.items.map((b,n)=>`<button type="button" class="common-brand-result ${selected?.id===b.id?'is-selected':''}" data-brand-market="${p.id}" data-brand-index="${n}" aria-pressed="${selected?.id===b.id}" ${s.read?'disabled':''}><strong>${e(b.name)}</strong><span>${e(b.id)}</span><span>${selected?.id===b.id?'선택됨':'선택'}</span></button>`).join(''):''}${s.error?`<button type="button" class="btn small" data-brand-market="${p.id}" data-brand-retry ${s.read?'disabled':''}>다시 조회</button>`:''}${p.paged&&!s.locked&&s.loaded?`<div class="common-brand-pagination"><button type="button" class="btn small" data-brand-market="${p.id}" data-brand-page="${s.page-1}" ${s.page<=1||s.read?'disabled':''}>이전</button><span>${s.page} 페이지</span><button type="button" class="btn small" data-brand-market="${p.id}" data-brand-page="${s.page+1}" ${!s.hasNext||s.read?'disabled':''}>다음</button></div>`:''}</section>`;
        }).join('');updateApply();
    }
    function rows(provider,data){
        if(provider.id==='ELEVENST'){if(!Array.isArray(data?.items)||!Number.isInteger(data.page)||typeof data.hasNext!=='boolean'||typeof data.available!=='boolean')throw Error('브랜드 검색 결과를 확인해 주세요.');return data.items.map(b=>({id:b.id,name:b.name}));}
        if(provider.paged){if(!data||!Array.isArray(data.items)||!Number.isInteger(data.page)||data.page<1||typeof data.hasNext!=='boolean')throw Error('브랜드 검색 결과를 확인해 주세요.');return data.items.map(b=>({id:b.brandId,name:b.brandName}));}
        if(!Array.isArray(data))throw Error('브랜드 검색 결과를 확인해 주세요.');return data;
    }
    async function load(s,page=1){
        if(!active||s.locked||s.provider.pending||!query)return;
        s.read?.cancel();const token=generation,readQuery=query;let read;
        if(s.provider.id==='COUPANG')read=window.CoupangRead.start(s.provider.url(readQuery,page));
        else {const controller=new AbortController();read={cancel:()=>controller.abort(),promise:apiGet(s.provider.url(readQuery,page),{signal:controller.signal})};}
        s.read=read;s.error='';s.message='';render();
        try{
            const data=await read.promise;if(!active||token!==generation||s.read!==read)return;
            const list=rows(s.provider,data);if(list.some(b=>typeof b.id!=='string'||!b.id.trim()||typeof b.name!=='string'||!b.name.trim()))throw Error('브랜드 검색 결과를 확인해 주세요.');
            s.items=list;s.page=s.provider.paged?data.page:1;s.hasNext=!!data.hasNext;s.loaded=true;s.message=s.provider.id==='ELEVENST'?data.warning||'':list.length?'':'검색 결과가 없습니다.';
        }catch(error){if(active&&token===generation&&s.read===read)s.error=error.message||'브랜드 검색에 실패했습니다.';}
        finally{if(active&&token===generation&&s.read===read){s.read=null;render();}}
    }
    function search(){
        const name=dialog.querySelector('[data-common-brand-query]').value.trim();if(!name||name.length>200)return;
        generation++;cancelReads();query=name;dialog.querySelector('[data-common-brand-error]').hidden=true;
        for(const s of states.values()){s.read=null;s.items=[];s.loaded=false;s.page=1;s.error='';s.message='';}
        for(const s of states.values())if(!s.locked&&!s.provider.pending&&(!active.markets||active.markets.includes(s.provider.id)))load(s);render();
    }
    window.CommonBrandPicker={open(options){
        close();ensure();active=options;choices=structuredClone(options.selections||{});changes={};query='';states=new Map(providers.map(provider=>[provider.id,{provider,locked:!!options.locked?.[provider.id],items:[],read:null,page:1,loaded:false,message:'',error:''}]));
        dialog.querySelector('[data-common-brand-query]').value=options.name||'';dialog.querySelector('[data-common-brand-error]').hidden=true;render();dialog.showModal();dialog.querySelector('[data-common-brand-query]').focus();if(options.name?.trim())search();
    },close};
    window.addEventListener('pagehide',close);
})();
