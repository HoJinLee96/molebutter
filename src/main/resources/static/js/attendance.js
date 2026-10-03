(() => {
    const { $, escape: e, stamp, duration, badge, snapshot, correction, pager } = AttendanceUI;
    let state, fetchedAt, recordPage = 0, requestPage = 0, busy = false, selectedRecord, selectedRequest, editingRecord;
    let loadSequence = 0, breakSequence = 0;
    const activeStatuses = ['WORKING', 'ON_BREAK'];
    const message = (id, text) => setError(id, text);

    function renderCurrent() {
        const active = state.active, record = active ?? state.today;
        const old = active && active.workDate < state.serverNow.slice(0, 10);
        $('current-status').innerHTML = record ? badge(record.record.status) : '출근 전';
        $('current-date').textContent = record ? `${record.workDate} 근무` : `${state.serverNow.slice(0, 10)} · 오늘 출근 기록이 없습니다.`;
        $('current-in').textContent = record ? stamp(record.record.clockIn) : '—';
        $('overnight-hint').hidden = !old;
        $('clock-in').hidden = !!state.today || (!!active && !old);
        $('clock-in').textContent = old ? '오늘 출근' : '출근';
        $('clock-out').hidden = !active;
        $('clock-out').textContent = old ? '이전 근무 퇴근' : '퇴근';
        $('break-start').hidden = !active || active.record.status !== 'WORKING';
        $('break-end').hidden = !active || active.record.status !== 'ON_BREAK';
        document.querySelectorAll('#work-actions button').forEach(b => b.disabled = busy);
        updateElapsed();
    }
    function updateElapsed() {
        if (!state) return;
        const a = state.active ?? state.today;
        const elapsed = Math.max(0, Math.floor((performance.now() - fetchedAt) / 1000));
        $('current-work').textContent = a ? duration(a.workedSeconds == null ? null : a.workedSeconds + (a.record.status === 'WORKING' ? elapsed : 0)) : '—';
        $('current-break').textContent = a ? duration(a.breakSeconds == null ? null : a.breakSeconds + (a.record.status === 'ON_BREAK' ? elapsed : 0)) : '—';
    }
    async function load() {
        const sequence = ++loadSequence;
        const current = await apiGet('/api/attendance/current');
        if (sequence !== loadSequence) return;
        state = current; fetchedAt = performance.now();
        if (!$('month').value) $('month').value = state.serverNow.slice(0, 7);
        $('work-date').max = state.serverNow.slice(0, 10);
        renderCurrent();
        const month = encodeURIComponent($('month').value);
        const [records, requests] = await Promise.all([
            apiGet(`/api/attendance?month=${month}&page=${recordPage}`),
            apiGet(`/api/attendance/corrections?month=${month}&page=${requestPage}`),
        ]);
        if (sequence !== loadSequence) return;
        $('records-body').innerHTML = records.items.map(a => `<tr><td>${e(a.workDate)}</td><td>${badge(a.record.status)}</td><td>${e(stamp(a.record.clockIn))}</td><td>${e(stamp(a.record.clockOut))}</td><td>${e(duration(a.workedSeconds))}</td><td><button class="btn small" data-record="${e(a.id)}" type="button">상세</button></td></tr>`).join('') || '<tr><td colspan="6" class="empty">이 달의 근무 기록이 없습니다.</td></tr>';
        pager('records-pager', records, p => { recordPage = p; refresh(); });
        $('corrections-list').innerHTML = requests.items.map(c => `<div class="attendance-request"><div><strong>${e(c.workDate)}</strong> ${badge(c.status)}<p class="field-hint">${e(stamp(c.requestedAt))}</p></div><button class="btn small" data-request="${e(c.id)}" type="button">상세</button></div>`).join('') || '<p class="empty">정정 요청이 없습니다.</p>';
        pager('corrections-pager', requests, p => { requestPage = p; refresh(); });
    }
    async function refresh() { try { await load(); } catch (error) { message('page-error', error.message); } }
    async function mutate(url, body) {
        if (busy) return;
        busy = true; renderCurrent(); message('page-error', null); message('page-success', null);
        try {
            await apiPost(url, body);
            message('page-success', '근태 기록이 반영되었습니다.');
        } catch (error) { message('page-error', error.message); }
        finally { busy = false; await refresh(); renderCurrent(); }
    }
    $('clock-in').addEventListener('click', () => {
        if (!state || busy) return;
        const previous = state.active;
        if (previous && !confirm('이전 근무를 퇴근 누락으로 남기고 오늘 출근하시겠습니까? 이전 시각은 정정 요청으로 수정할 수 있습니다.')) return;
        mutate('/api/attendance/clock-in', previous ? { previousRecordId: previous.id, previousRevision: previous.revision, confirmMissing: true } : {});
    });
    for (const action of ['clock-out', 'break-start', 'break-end']) {
        $(action).addEventListener('click', () => {
            if (!state?.active || busy) return;
            if (action === 'clock-out' && state.active.record.status === 'ON_BREAK' && !confirm('휴게를 함께 종료하고 퇴근하시겠습니까?')) return;
            mutate(`/api/attendance/${action}`, { recordId: state.active.id, revision: state.active.revision });
        });
    }
    $('month').addEventListener('change', () => { recordPage = requestPage = 0; refresh(); });
    $('reload').addEventListener('click', refresh);
    $('records-body').addEventListener('click', async event => {
        const button = event.target.closest('[data-record]');
        if (!button) return;
        try {
            selectedRecord = await apiGet(`/api/attendance/${button.dataset.record}`);
            $('record-title').textContent = `${selectedRecord.workDate} 근무 상세`;
            $('record-detail').innerHTML = snapshot(selectedRecord.record) + `<p>순근무: ${e(duration(selectedRecord.workedSeconds))}</p>`;
            $('edit-record').hidden = activeStatuses.includes(selectedRecord.record.status);
            $('record-dialog').showModal();
        } catch (error) { message('page-error', error.message); }
    });
    function openCorrection(record) {
        editingRecord = record;
        $('correction-form').reset(); $('break-editor').replaceChildren(); message('correction-error', null);
        $('work-date').value = record?.workDate ?? state?.serverNow.slice(0, 10) ?? '';
        $('work-date').readOnly = !!record;
        $('proposed-in').value = record?.record.clockIn?.slice(0, 19) ?? '';
        $('proposed-out').value = record?.record.clockOut?.slice(0, 19) ?? '';
        record?.record.breaks.forEach(addBreak);
        $('correction-dialog').showModal();
    }
    $('edit-record').addEventListener('click', () => { $('record-dialog').close(); openCorrection(selectedRecord); });
    $('new-correction').addEventListener('click', () => openCorrection(null));
    function addBreak(value = {}) {
        if ($('break-editor').children.length >= 100) return;
        const id = ++breakSequence;
        const row = document.createElement('div'); row.className = 'attendance-break-row';
        row.innerHTML = `<div class="field"><label for="break-start-${id}">휴게 시작</label><input id="break-start-${id}" type="datetime-local" step="1" data-start required></div><div class="field"><label for="break-end-${id}">휴게 종료</label><input id="break-end-${id}" type="datetime-local" step="1" data-end required></div><button type="button" class="btn small">삭제</button>`;
        row.querySelector('[data-start]').value = value.startedAt?.slice(0, 19) ?? '';
        row.querySelector('[data-end]').value = value.endedAt?.slice(0, 19) ?? '';
        row.querySelector('button').addEventListener('click', () => row.remove());
        $('break-editor').append(row);
    }
    $('add-break').addEventListener('click', () => addBreak());
    $('correction-form').addEventListener('submit', async event => {
        event.preventDefault();
        const button = $('submit-correction'); if (button.disabled) return;
        button.disabled = true; message('correction-error', null);
        try {
            await apiPost('/api/attendance/corrections', {
                workDate: $('work-date').value, recordId: editingRecord?.id ?? null, revision: editingRecord?.revision ?? null,
                clockIn: $('proposed-in').value, clockOut: $('proposed-out').value,
                breaks: [...$('break-editor').children].map(row => ({ startedAt: row.querySelector('[data-start]').value, endedAt: row.querySelector('[data-end]').value })),
                reason: $('correction-reason').value.trim(),
            });
            $('month').value = $('work-date').value.slice(0, 7); recordPage = requestPage = 0;
            $('correction-dialog').close(); message('page-success', '정정 요청을 보냈습니다. 관리자 승인 후 반영됩니다.');
            await refresh();
        } catch (error) { message('correction-error', error.message); if (error.status === 409) await refresh(); }
        finally { button.disabled = false; }
    });
    $('corrections-list').addEventListener('click', async event => {
        const button = event.target.closest('[data-request]'); if (!button) return;
        openRequest(button.dataset.request);
    });
    async function openRequest(id) {
        try {
            selectedRequest = await apiGet(`/api/attendance/corrections/${encodeURIComponent(id)}`);
            $('request-detail').innerHTML = correction(selectedRequest);
            $('cancel-request').hidden = selectedRequest.status !== 'PENDING';
            message('request-error', null); $('request-dialog').showModal();
        } catch (error) { message('page-error', error.message); }
    }
    const linkedCorrection=new URLSearchParams(location.search).get('correction');
    if(linkedCorrection&&/^[0-9]+$/.test(linkedCorrection))openRequest(linkedCorrection);
    $('cancel-request').addEventListener('click', async () => {
        const button = $('cancel-request'); if (button.disabled || !confirm('정정 요청을 취소하시겠습니까?')) return;
        button.disabled = true;
        try {
            await apiPost(`/api/attendance/corrections/${selectedRequest.id}/cancel`, { revision: selectedRequest.revision });
            $('request-dialog').close(); message('page-success', '정정 요청을 취소했습니다.'); await refresh();
        } catch (error) {
            message('request-error', error.message);
            if (error.status === 409) { button.hidden = true; await refresh(); }
        } finally { button.disabled = false; }
    });
    setInterval(updateElapsed, 1000);
    setInterval(() => { if (!document.hidden && !busy) refresh(); }, 30000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden && !busy) refresh(); });
    refresh();
})();
