(() => {
    const { $, escape: e, stamp, badge, correction, pager } = AttendanceUI;
    let page = 0, selected, sequence = 0;
    async function load() {
        const current = ++sequence;
        try {
            const status = $('status').value;
            const data = await apiGet(`/api/attendance-manage/corrections?page=${page}${status ? '&status=' + encodeURIComponent(status) : ''}`);
            if (current !== sequence) return;
            $('requests-body').innerHTML = data.items.map(c => `<tr><td>${e(c.userName)}</td><td>${e(c.workDate)}</td><td>${e(stamp(c.requestedAt))}</td><td>${badge(c.status)}</td><td><button class="btn small" type="button" data-id="${e(c.id)}">검토</button></td></tr>`).join('') || '<tr><td colspan="5" class="empty">해당 상태의 정정 요청이 없습니다.</td></tr>';
            pager('requests-pager', data, p => { page = p; load(); });
        } catch (error) { setError('page-error', error.message); }
    }
    $('status').addEventListener('change', () => { page = 0; load(); });
    $('reload').addEventListener('click', load);
    $('requests-body').addEventListener('click', async event => {
        const button = event.target.closest('[data-id]'); if (!button) return;
        try {
            selected = await apiGet(`/api/attendance-manage/corrections/${button.dataset.id}`);
            $('review-detail').innerHTML = correction(selected);
            $('review-actions').hidden = selected.status !== 'PENDING';
            $('review-comment').value = ''; setError('review-error', null);
            $('review-dialog').showModal();
        } catch (error) { setError('page-error', error.message); }
    });
    async function review(action) {
        if ($('approve').disabled) return;
        const comment = $('review-comment').value.trim();
        if (action === 'reject' && !comment) { setError('review-error', '반려 사유를 입력해 주세요.'); $('review-comment').focus(); return; }
        if (!confirm(action === 'approve' ? '요청한 내용으로 근태 기록을 변경하시겠습니까?' : '이 요청을 반려하시겠습니까?')) return;
        $('approve').disabled = $('reject').disabled = true; setError('review-error', null);
        try {
            await apiPost(`/api/attendance-manage/corrections/${selected.id}/${action}`, { revision: selected.revision, comment });
            $('review-dialog').close(); setError('page-success', action === 'approve' ? '정정을 승인하고 근태에 반영했습니다.' : '정정 요청을 반려했습니다.');
            await load();
        } catch (error) {
            setError('review-error', error.message);
            if (error.status === 409) {
                try {
                    selected = await apiGet(`/api/attendance-manage/corrections/${selected.id}`);
                    $('review-detail').innerHTML = correction(selected);
                    $('review-actions').hidden = selected.status !== 'PENDING';
                } catch (reloadError) { setError('page-error', reloadError.message); }
                await load();
            }
        } finally { $('approve').disabled = $('reject').disabled = false; }
    }
    $('approve').addEventListener('click', () => review('approve'));
    $('reject').addEventListener('click', () => review('reject'));
    document.addEventListener('attendance-corrections-open', load);
})();
