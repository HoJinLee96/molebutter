/* One query, separate marketplace choices. Applying is the only document mutation. */
(() => {
    'use strict';
    const e=AppUI.escape,providers=[
        {id:'COUPANG',label:'쿠팡',url:()=>'/api/marketplaces/coupang/categories'},
        {id:'NAVER',label:'스마트스토어',url:()=>'/api/marketplaces/naver/metadata/categories?last=true'},
        {id:'ELEVENST',label:'11번가',url:()=>'/api/marketplaces/elevenst/categories'},
        {id:'GMARKET',label:'G마켓',pending:'카테고리 검색 연결 준비 중'},
        {id:'AUCTION',label:'옥션',pending:'카테고리 검색 연결 준비 중'},
        {id:'LOTTEON',label:'롯데ON',paged:true,url:page=>'/api/marketplaces/lotteon/metadata/categories/page?filter='+encodeURIComponent(/^[A-Z]{2}[0-9]{8,9}$/i.test(query)?query:'')+'&page='+(page-1),note:'롯데ON은 100개씩 조회하여 현재 페이지에서 이름을 검색합니다. 다음 페이지 또는 롯데ON 탭의 하위 분류 조회를 이용하세요.'},
    ];
    let dialog,active=null,generation=0,query='',states=new Map(),choices={},changes={};
    function cancelReads(){for(const s of states.values())s.read?.cancel();}
    function close(){generation++;cancelReads();active=null;if(dialog?.open)dialog.close();}
    function ensure(){
        if(dialog)return;
        dialog=document.createElement('dialog');dialog.className='common-brand-dialog common-category-dialog';dialog.setAttribute('aria-labelledby','common-category-title');
        dialog.innerHTML='<div class="common-subheading"><h2 id="common-category-title">마켓별 카테고리 선택</h2><button type="button" class="btn" data-common-category-close>닫기</button></div><form data-common-category-form><label class="field"><span>카테고리명 또는 코드</span><input type="search" maxlength="200" data-common-category-query required></label><button type="submit" class="btn primary">전체 검색</button></form><p class="common-brand-note common-category-note">같은 검색어로 조회한 결과에서 마켓별 카테고리를 선택하세요. 기존 선택은 직접 바꾸거나 해제할 때까지 유지됩니다. 변경 후 각 마켓 탭에서 새 카테고리 규격과 필수 입력을 확인하세요.</p><div class="common-brand-providers common-category-providers" data-common-category-providers></div><p class="form-error" role="alert" data-common-category-error hidden></p><div class="common-brand-actions common-category-actions"><button type="button" class="btn" data-common-category-cancel>취소</button><button type="button" class="btn primary" data-common-category-apply>선택한 카테고리 적용</button></div>';
        document.body.append(dialog);dialog.querySelector('[data-common-category-close]').onclick=close;dialog.querySelector('[data-common-category-cancel]').onclick=close;dialog.addEventListener('cancel',close);
        dialog.querySelector('form').onsubmit=event=>{event.preventDefault();search();};
        FormActions.bindEnterSubmit(dialog.querySelector('form'),dialog.querySelector('button[type=submit]'));
        dialog.querySelector('[data-common-category-query]').oninput=updateApply;
        dialog.querySelector('[data-common-category-providers]').onclick=event=>{
            const button=event.target.closest('[data-category-market]');if(!button||!active)return;
            const market=button.dataset.categoryMarket,s=states.get(market);if(!s||s.read||s.locked)return;
            if(button.dataset.categoryIndex!==undefined){const category=s.items[Number(button.dataset.categoryIndex)];if(category){choices[market]=category;changes[market]=category;render();}}
            else if(button.dataset.categoryClear!==undefined){choices[market]=null;changes[market]=null;render();}
            else if(button.dataset.categoryPage!==undefined)load(s,Number(button.dataset.categoryPage));
            else if(button.dataset.categoryRetry!==undefined)load(s,s.page||1);
        };
        dialog.querySelector('[data-common-category-apply]').onclick=()=>{
            if(!active||isBusy()||!query||dialog.querySelector('[data-common-category-query]').value.trim()!==query)return;
            const callback=active.onApply,payload={name:query,selections:structuredClone(changes)};
            try{callback(payload);close();}catch(error){const host=dialog.querySelector('[data-common-category-error]');host.textContent=error.message;host.hidden=false;}
        };
    }
    const isBusy=()=>[...states.values()].some(s=>s.read);
    function updateApply(){dialog.querySelector('[data-common-category-apply]').disabled=isBusy()||!query||dialog.querySelector('[data-common-category-query]').value.trim()!==query;}
    function render(){
        dialog.querySelector('[data-common-category-providers]').innerHTML=providers.map(p=>{
            const s=states.get(p.id),selected=choices[p.id];
            const message=s.locked?'기존 등록상품의 카테고리는 해당 마켓 편집 화면에서 확인해 주세요.':p.pending|| (s.read?'검색 중…':s.error||s.message||'');
            return `<section class="common-brand-provider common-category-provider"><div class="common-brand-provider-heading common-category-provider-heading"><h3>${e(p.label)}</h3>${selected?`<button type="button" class="btn small" data-category-market="${p.id}" data-category-clear ${s.locked||s.read?'disabled':''}>선택 해제</button>`:''}</div><p class="common-brand-selected common-category-selected">${selected?`${e(selected.name)} <span>${e(selected.id)}</span>`:'선택 없음'}</p><p role="status" class="${s.error?'form-error':'product-meta'}">${e(message)}</p>${p.note?`<p class="product-meta">${e(p.note)}</p>`:''}${!s.locked&&!p.pending?`<div class="common-category-results">${s.items.map((b,n)=>`<button type="button" class="common-brand-result common-category-result ${selected?.id===b.id?'is-selected':''}" data-category-market="${p.id}" data-category-index="${n}" aria-pressed="${selected?.id===b.id}" ${s.read?'disabled':''}><strong>${e(b.name)}</strong><span>${e(b.id)}</span><span>${selected?.id===b.id?'선택됨':'선택'}</span></button>`).join('')}</div>`:''}${p.paged&&!s.locked?`<div class="common-row"><button type="button" class="btn small" data-category-market="${p.id}" data-category-page="${s.page-1}" ${s.read||s.page<=1?'disabled':''}>이전</button><span>${s.page}쪽</span><button type="button" class="btn small" data-category-market="${p.id}" data-category-page="${s.page+1}" ${s.read||!s.hasNext?'disabled':''}>다음</button></div>`:''}${s.error?`<button type="button" class="btn small" data-category-market="${p.id}" data-category-retry ${s.read?'disabled':''}>다시 조회</button>`:''}</section>`;
        }).join('');updateApply();
    }
    function rows(provider,data){
        if(!Array.isArray(data))throw Error('카테고리 검색 결과를 확인해 주세요.');
        const values=data.filter(v=>['ELEVENST','LOTTEON'].includes(provider.id)?v.leaf===true:provider.id!=='NAVER'||v.last===true).map(v=>({id:v.id,name:v.wholeCategoryName||v.name}));
        if(values.some(v=>typeof v.id!=='string'||!(provider.id==='LOTTEON'?/^[A-Za-z0-9_-]{1,100}$/:/^\d+$/).test(v.id)||typeof v.name!=='string'||!v.name.trim()))throw Error('카테고리 검색 결과를 확인해 주세요.');
        return values;
    }
    async function load(s,page=1){
        if(!active||s.locked||s.provider.pending||!query)return;
        s.read?.cancel();const token=generation,readQuery=query;let read;
        if(s.cached&&s.provider.id!=='LOTTEON')read={cancel(){},promise:Promise.resolve(s.cached)};
        else if(s.provider.id==='COUPANG')read=window.CoupangRead.start(s.provider.url());
        else {const controller=new AbortController();read={cancel:()=>controller.abort(),promise:apiGet(s.provider.url(page),{signal:controller.signal})};}
        s.read=read;s.error='';s.message='';render();
        try{
            const data=await read.promise;if(!active||token!==generation||s.read!==read)return;
            if(s.provider.paged&&(!Array.isArray(data?.items)||!Number.isInteger(data.page)||typeof data.hasNext!=='boolean'))throw Error('카테고리 검색 결과를 확인해 주세요.');const values=rows(s.provider,s.provider.paged?data.items:data);s.cached=data;s.page=page;s.hasNext=!!data.hasNext;const terms=readQuery.toLocaleLowerCase().split(/\s+/).filter(Boolean),all=values.filter(v=>terms.every(term=>(v.name+' '+v.id).toLocaleLowerCase().includes(term))),list=all.slice(0,200);
            s.items=list;s.message=all.length>200?`검색 결과 ${all.length}개 중 200개 표시 · 검색어를 더 구체적으로 입력해 주세요.`:list.length?`${list.length}개 검색됨`:'검색 결과가 없습니다.';
        }catch(error){if(active&&token===generation&&s.read===read)s.error=error.message||'카테고리 검색에 실패했습니다.';}
        finally{if(active&&token===generation&&s.read===read){s.read=null;render();}}
    }
    function search(){
        const name=dialog.querySelector('[data-common-category-query]').value.trim();if(!name||name.length>200)return;
        generation++;cancelReads();query=name;dialog.querySelector('[data-common-category-error]').hidden=true;
        for(const s of states.values()){s.read=null;s.items=[];s.error='';s.message='';}
        for(const s of states.values())if(!s.locked&&!s.provider.pending)load(s);render();
    }
    window.CommonCategoryPicker={open(options){
        close();ensure();active=options;choices=structuredClone(options.selections||{});changes={};query='';states=new Map(providers.map(provider=>[provider.id,{provider,locked:!!options.locked?.[provider.id],items:[],read:null,page:1,hasNext:false,message:'',error:''}]));
        dialog.querySelector('[data-common-category-query]').value=options.name||'';dialog.querySelector('[data-common-category-error]').hidden=true;render();dialog.showModal();dialog.querySelector('[data-common-category-query]').focus();if(options.name?.trim())search();
    },close};
    window.addEventListener('pagehide',close);
})();
