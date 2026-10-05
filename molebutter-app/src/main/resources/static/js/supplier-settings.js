(() => {
    const { $, escape: e } = AppUI;
    const admin=document.body.dataset.settingsAdmin==='true';let data,malls=[],candidates=[],busy=false;
    async function load(){[data,malls,candidates]=await Promise.all([apiGet('/api/settings/preferred-suppliers'),apiGet('/api/settings/suppliers'),admin?apiGet('/api/settings/supplier-store-identities'):Promise.resolve([])]);
        const groups=SupplierEditor.groups(data),pending=[...new Set(candidates.map(c=>c.mall))].filter(mall=>!groups.some(g=>g.mall===mall));
        $('preferred-rows').innerHTML=groups.map(g=>`<tr><td>${e(malls.find(m=>m.code===g.mall)?.name||g.mall)}</td><td>${g.branchRequired===false||data.branchRequirements?.[g.mall]===false?'전체 · 지점 구분 안 함':g.scope==='ALL'?'전체':g.storeIds.map(id=>e(SupplierEditor.storeName(data.stores.find(s=>s.id===id))||'매장 확인 필요')).join(', ')}${admin&&candidates.some(c=>c.mall===g.mall)?`<button type="button" class="product-name" data-mall-edit="${e(g.mall)}">매장 연결 확인</button>`:''}</td>${admin?`<td><div class="product-row-actions"><button type="button" class="btn small" data-mall-edit="${e(g.mall)}">수정</button><button type="button" class="btn small danger" data-mall-delete="${e(g.mall)}">제외</button></div></td>`:''}</tr>`).join('')||'<tr><td colspan="3">선호 매입처를 추가하면 최신화를 시작할 수 있습니다.</td></tr>';
        $('preferred-pending').innerHTML=admin?pending.map(mall=>`<button type="button" class="btn small" data-mall-edit="${e(mall)}">${e(malls.find(m=>m.code===mall)?.name||mall)} · 매장 연결 확인</button>`).join(''):'';
        if($('preferred-add'))$('preferred-add').disabled=false;
    }
    async function edit(mall){try{await SupplierEditor.open({mall,admin,onError:err=>setError('preferred-error','저장은 완료됐지만 화면 조회에 실패했습니다. '+err.message),onSaved:()=>{setError('preferred-success','저장했습니다. 비교·선정에 반영되며 수집 기준은 다음 최신화 작업부터 적용됩니다.');},onClosed:()=>load().catch(err=>setError('preferred-error',err.message))});}catch(err){setError('preferred-error',err.message);}}
    $('preferred-add')?.addEventListener('click',()=>edit());
    document.querySelector('[data-settings-panel="preferred"]').addEventListener('click',async ev=>{const b=ev.target.closest('button');if(!b||busy||!admin||!data)return;
        if(b.dataset.mallEdit)await edit(b.dataset.mallEdit);
        if(b.dataset.mallDelete&&confirm('이 쇼핑몰을 선호 목록에서 제외할까요? 기존 상품별 선정은 유지됩니다.')){busy=true;b.disabled=true;try{await SupplierEditor.request('DELETE','/api/settings/preferred-suppliers/'+b.dataset.mallDelete,{revision:data.revision});await load();setError('preferred-success','선호 목록에서 제외했습니다. 기존 상품별 선정은 유지됩니다.');}catch(err){setError('preferred-error',err.message);if(err.status===409)await load().catch(()=>{});}finally{busy=false;b.disabled=false;}}
    });
    $('preferred-reload').addEventListener('click',()=>load().catch(err=>setError('preferred-error',err.message)));
    load().catch(err=>setError('preferred-error',err.message));
})();
