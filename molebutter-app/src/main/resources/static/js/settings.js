(() => {
    const $ = id => document.getElementById(id);
    const escape = AppUI.escape;
    const admin = document.body.dataset.settingsAdmin === 'true';
    let brands = [], payments = [], schedule, target, saving = false, ready = false;
    function selectTab() {
        const requested = new URLSearchParams(location.search).get('tab');
        const tab = ['brands','preferred','suppliers','schedule',...(admin?['payment-methods']:[])].includes(requested) ? requested : 'brands';
        document.querySelectorAll('[data-tab]').forEach(a => { a.classList.toggle('active', a.dataset.tab === tab); if(a.dataset.tab === tab) a.setAttribute('aria-current','page'); else a.removeAttribute('aria-current'); });
        document.querySelectorAll('[data-settings-panel]').forEach(panel => panel.hidden = panel.dataset.settingsPanel !== tab);
    }
    $('settings-tabs').addEventListener('click', event => {
        const link = event.target.closest('[data-tab]');
        if(!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault(); history.pushState(null, '', link.getAttribute('href')); selectTab();
    });
    addEventListener('popstate', selectTab); selectTab();
    function controls() {
        document.querySelectorAll('.settings-section:not([data-settings-panel=preferred]) button, #settings-edit-form button, #settings-delete-form button').forEach(b => b.disabled = saving || !ready || b.dataset.locked === 'true');
    }
    function actions(kind, item) {
        if(!admin) return '';
        const id = item.id ?? item.code, locked = kind !== 'payment-methods' && (item.usageCount > 0 || item.builtIn);
        return `<td><button type="button" class="btn small" data-kind="${kind}" data-id="${escape(id)}" data-action="edit">이름 수정</button><button type="button" class="btn small danger" data-kind="${kind}" data-id="${escape(id)}" data-action="delete" data-locked="${locked}" ${locked?'disabled':''}>삭제</button></td>`;
    }
    async function loadBrands() {
        brands = await apiGet('/api/settings/brands');
        $('settings-brand-rows').innerHTML = brands.map(b => `<tr><td class="settings-name">${escape(b.name)}</td><td>${b.usageCount}개</td>${actions('brands',b)}</tr>`).join('') || `<tr><td colspan="${admin?3:2}" class="empty">등록된 브랜드가 없습니다.</td></tr>`;
        controls();
    }
    async function loadPayments() {
        if(!admin)return;
        payments=await apiGet('/api/settings/payment-methods');
        $('settings-payment-rows').innerHTML=payments.map(p=>`<tr><td class="settings-name">${escape(p.name)}</td>${actions('payment-methods',p)}</tr>`).join('')||'<tr><td colspan="2" class="empty">등록된 결제 수단이 없습니다.</td></tr>';
        controls();
    }
    async function loadSchedule() {
        schedule = await apiGet('/api/product-refresh/settings');
        $('settings-schedule-summary').textContent = schedule.scheduleEnabled ? `매일 ${schedule.scheduleTime} (한국 시간) · 자동관리 상품 실행` : '자동 최신화 꺼짐';
        if(admin) { $('schedule-enabled').checked=schedule.scheduleEnabled; $('schedule-time').value=schedule.scheduleTime; }
    }
    async function load() {
        ready=false; controls(); $('settings-reload').hidden=true; setError('settings-error',null);
        const result = await Promise.allSettled([loadBrands(),loadPayments(),loadSchedule(),apiGet('/api/settings/suppliers').then(list => {
            $('settings-suppliers').innerHTML=list.map(m=>`<li><strong>${escape(m.name)}</strong><span class="product-badge ${m.stockSupported===false?'warn':'good'}">${m.stockSupported===false?'재고 확인 보류':'옵션 조회 지원'}</span></li>`).join('');
        })]);
        const failure=result.find(r=>r.status==='rejected');
        ready=!failure; controls();
        if(failure) {setError('settings-error',failure.reason.message);$('settings-reload').hidden=false;}
    }
    async function save(action, reload, message, errorId='settings-error') {
        if(!admin || saving || !ready) return;
        saving=true; controls(); setError(errorId,null);setError('settings-success',null);
        try { await action(); await reload(); setError('settings-success',message); return true; }
        catch(error) {
            setError(errorId,error.message);
            if(error.status===409) {
                await reload().catch(()=>{});
                // 저장에 사용한 이전 버전은 유지한다. 새 내용을 확인하고 편집을 다시 열어야 한다.
                if(errorId!=='settings-error') { $('settings-edit-dialog').close();$('settings-delete-dialog').close();setError('settings-error',error.message+' 내용을 확인한 뒤 다시 선택해 주세요.'); }
            }
            return false;
        } finally {saving=false;controls();}
    }
    $('brand-create-form')?.addEventListener('submit',async event=>{event.preventDefault();if(await save(()=>apiPost('/api/settings/brands',{name:$('brand-name').value}),loadBrands,'브랜드를 추가했습니다.'))$('brand-name').value='';});
    $('payment-create-form')?.addEventListener('submit',async event=>{event.preventDefault();if(await save(()=>apiPost('/api/settings/payment-methods',{name:$('payment-name').value}),loadPayments,'결제 수단을 추가했습니다.'))$('payment-name').value='';});
    document.querySelectorAll('.settings-section').forEach(section=>section.addEventListener('click',event=>{
        const button=event.target.closest('[data-action]'); if(!button||button.disabled||saving||!admin)return;
        const kind=button.dataset.kind, row=(kind==='payment-methods'?payments:brands).find(v=>(v.id??v.code)===button.dataset.id);if(!row)return;
        target={kind,id:row.id??row.code,revision:row.revision};
        if(button.dataset.action==='edit') {setError('settings-edit-error',null);$('settings-edit-title').textContent=(kind==='payment-methods'?'결제 수단':'브랜드')+' 이름 수정';$('settings-edit-name').value=row.name;$('settings-edit-dialog').showModal();}
        else {setError('settings-delete-error',null);$('settings-delete-description').textContent=`‘${row.name}’ 등록을 삭제할까요?`;$('settings-delete-dialog').showModal();}
    }));
    const reloadTarget=()=>target?.kind==='payment-methods'?loadPayments():loadBrands();
    $('settings-edit-form').addEventListener('submit',async event=>{event.preventDefault();if(!target)return;if(await save(()=>apiPost(`/api/settings/${target.kind}/${encodeURIComponent(target.id)}`,{name:$('settings-edit-name').value,revision:target.revision}),reloadTarget,'이름을 변경했습니다.','settings-edit-error'))$('settings-edit-dialog').close();});
    $('settings-delete-form').addEventListener('submit',async event=>{event.preventDefault();if(!target)return;if(await save(()=>apiPost(`/api/settings/${target.kind}/${encodeURIComponent(target.id)}/delete`,{revision:target.revision}),reloadTarget,'등록을 삭제했습니다.','settings-delete-error'))$('settings-delete-dialog').close();});
    document.querySelectorAll('[data-settings-close]').forEach(b=>b.addEventListener('click',()=>{if(!saving)$(b.dataset.settingsClose).close();}));
    document.querySelectorAll('dialog').forEach(d=>d.addEventListener('cancel',event=>{if(saving)event.preventDefault();}));
    $('schedule-settings-form')?.addEventListener('submit',event=>{event.preventDefault();save(()=>apiPost('/api/product-refresh/settings',{revision:schedule.revision,scheduleEnabled:$('schedule-enabled').checked,scheduleTime:$('schedule-time').value}),loadSchedule,'예약 설정을 저장했습니다. 다음 예약 실행부터 적용됩니다.');});
    $('settings-reload').addEventListener('click',load);
    load();
})();
