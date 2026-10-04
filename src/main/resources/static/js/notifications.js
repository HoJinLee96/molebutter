/* Persistent inbox. Reading and navigation never dismiss; only a successful DELETE does. */
(() => {
    const bell = document.getElementById('notification-bell');
    if (!bell) return;
    const escape = AppUI.escape;
    const dialog = document.createElement('dialog');
    dialog.id = 'notification-drawer'; dialog.className = 'notification-drawer';
    dialog.setAttribute('aria-labelledby', 'notification-title');
    dialog.innerHTML = `<header class="notification-heading"><h2 id="notification-title">알림 <span id="notification-total">0</span></h2><button type="button" class="btn small" data-notification-close>닫기</button></header><p id="notification-error" class="form-error" role="alert" hidden></p><div id="notification-list" aria-live="polite"></div><button type="button" class="btn" id="notification-more" hidden>이전 알림 더 보기</button>`;
    document.body.append(dialog);
    const list = dialog.querySelector('#notification-list'), more = dialog.querySelector('#notification-more'), error = dialog.querySelector('#notification-error');
    const toast = document.createElement('div'); toast.className = 'notification-toast'; toast.setAttribute('role', 'status'); toast.hidden = true;
    let snapshot = null, items = [], nextCursor = null, polling = false, loading = false, sequence = 0, toastTimer, initiator;
    const dirtyForms = new Set(), dismissed = new Set();
    let resetPending=false, refreshPending=false;
    document.addEventListener('input', event => { const form=event.target.closest('form'); if(form && !form.id.includes('filter') && !dialog.contains(form))dirtyForms.add(form); });
    document.addEventListener('click', event => {const button=event.target.closest('[data-choose-store],[data-remove-store],#supplier-editor-branch,#edit-auto');const form=button?.closest('form');if(form)dirtyForms.add(form);});
    window.AppNotifications={cleanForm:form=>dirtyForms.delete(form)};
    document.addEventListener('reset', event => dirtyForms.delete(event.target));
    function failure(message) { error.textContent = message || ''; error.hidden = !message; }
    function count(value) {
        document.querySelectorAll('[data-notification-open]').forEach(button => {
            button.querySelector('[data-notification-count]').textContent = value > 99 ? '99+' : value;
            button.querySelector('[data-notification-count]').hidden = !value;
            button.setAttribute('aria-label', `알림 ${value}개`);
        });
        dialog.querySelector('#notification-total').textContent=value;
    }
    function render() {
        list.innerHTML=items.map(n=>`<article class="notification-item ${escape(n.severity.toLowerCase())}" data-notification-id="${escape(n.id)}"><button type="button" class="notification-content" data-notification-target="${escape(n.id)}"><strong>${escape(n.title)}</strong>${n.message?`<span>${escape(n.message)}</span>`:''}<time>${escape(n.occurredAt.replace('T',' ').slice(0,19))}</time></button><button type="button" class="notification-dismiss" data-notification-dismiss="${escape(n.id)}" aria-label="${escape(n.title)} 알림 삭제">×</button></article>`).join('') || '<p class="empty">알림이 없습니다.</p>';
        more.hidden=!nextCursor;
    }
    async function page(append=false) {
        if(loading){if(!append)resetPending=true;return;}loading=true;more.disabled=true;const current=++sequence;
        try {
            const data=await apiGet('/api/notifications'+(append&&nextCursor?'?cursor='+encodeURIComponent(nextCursor):''));
            if(current!==sequence)return;
            items=(append?[...new Map([...items,...data.items].map(n=>[n.id,n])).values()]:data.items).filter(n=>!dismissed.has(n.id));nextCursor=data.nextCursor;render();
        } catch(err){failure(err.message);} finally{loading=false;more.disabled=false;if(resetPending){resetPending=false;page();}}
    }
    function popup(notices) {
        if(!notices.length)return;
        if([...document.querySelectorAll('dialog[open]')].some(modal=>modal!==dialog))return;
        document.body.append(toast);
        toast.textContent=notices.length===1?`${notices[0].title}${notices[0].message?' · '+notices[0].message:''}`:`새 알림 ${notices.length}개`;
        toast.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.hidden=true,5000);
    }
    async function refresh() {
        if(document.hidden)return;if(polling){refreshPending=true;return;}polling=true;
        try {
            const latest=await apiGet('/api/notifications/summary');
            const previous=snapshot; snapshot=latest;count(latest.count);
            const changed=previous&&(latest.latestId!==previous.latestId||latest.count!==previous.count);
            if(changed&&latest.latestId&&(!previous.latestId||BigInt(latest.latestId)>BigInt(previous.latestId))) {
                const newest=await apiGet('/api/notifications');
                popup(newest.items.filter(n=>!previous.latestId||BigInt(n.id)>BigInt(previous.latestId)));
            }
            if(dialog.open&&changed)await page();
        } catch(err) {if(dialog.open)failure(err.message);} finally{polling=false;if(refreshPending){refreshPending=false;setTimeout(refresh,0);}}
    }
    document.addEventListener('click', async event => {
        const button=event.target.closest('[data-notification-open]');if(!button)return;
        initiator=button;failure(null);
        document.getElementById('menu-close')?.click();
        if(!dialog.open)dialog.showModal();await page();await refresh();
    });
    dialog.querySelector('[data-notification-close]').addEventListener('click',()=>dialog.close());
    dialog.addEventListener('close',()=>{toast.hidden=true;initiator?.focus();});
    dialog.addEventListener('click',async event=>{
        if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close();return;}
        const button=event.target.closest('button');if(!button||button.disabled)return;
        if(button.dataset.notificationDismiss){button.disabled=true;failure(null);
            try {await apiRequest('/api/notifications/'+button.dataset.notificationDismiss,{method:'DELETE'});dismissed.add(button.dataset.notificationDismiss);items=items.filter(n=>n.id!==button.dataset.notificationDismiss);render();await refresh();(list.querySelector('button')||dialog.querySelector('[data-notification-close]')).focus();}
            catch(err){button.disabled=false;failure(err.message);}return;
        }
        if(button.dataset.notificationTarget){button.disabled=true;failure(null);
            try {const target=await apiGet('/api/notifications/'+button.dataset.notificationTarget+'/target');
                if(!target.url){failure(target.reason);return;}
                const url=new URL(target.url,location.origin);if(url.origin!==location.origin)throw Error('이동할 수 없는 주소입니다.');
                const dirty=[...dirtyForms].some(f=>f.isConnected&&f.getClientRects().length);
                if(dirty&&!confirm('저장하지 않은 입력이 있습니다. 이동할까요?'))return;
                location.assign(url.href);
            }catch(err){failure(err.message);}finally{button.disabled=false;}
        }
    });
    more.addEventListener('click',()=>page(true));
    document.addEventListener('app-operation-complete',event=>{
        // A successful submitted form no longer needs the navigation guard.
        if(event.detail?.success){const form=event.detail.form;if(form)dirtyForms.delete(form);}
        refresh();
    });
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
    window.addEventListener('focus',refresh);
    setInterval(refresh,15000);refresh();
})();
