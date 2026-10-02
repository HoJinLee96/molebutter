(() => {
    const { $, escape: e, stamp, badge, snapshot, pager } = AttendanceUI;
    const accountLabels = { ACTIVE: '정상', PENDING: '승인 대기', LOCKED: '잠금', SUSPENDED: '정지' };
    const duration = seconds => seconds == null ? '미확정' : `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds % 3600 / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
    const employee = u => `<strong>${e(u.name)}</strong><br><span class="field-hint">${e(u.email)}</span>`;
    const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' }).formatToParts(new Date());
    const year = parts.find(p => p.type === 'year').value, month = parts.find(p => p.type === 'month').value;
    $('records-from').value = `${year}-${month}-01`;
    $('records-to').value = `${year}-${month}-${new Date(Date.UTC(Number(year), Number(month), 0)).getUTCDate()}`;
    $('summary-month').value = `${year}-${month}`;
    let activeTab = 'records';
    const state = { records: { page: 0, sequence: 0 }, summary: { page: 0, sequence: 0 } };
    function filters(kind) { return new URLSearchParams(new FormData($(`${kind}-filter`))); }
    function validate(kind) {
        if (!$(`${kind}-filter`).reportValidity()) return false;
        if (kind === 'records') {
            const days = (Date.parse($('records-to').value) - Date.parse($('records-from').value)) / 86400000;
            if (days < 0 || days >= 366) { setError('page-error', '조회 기간은 시작일부터 종료일까지 최대 366일입니다.'); return false; }
        }
        return true;
    }
    async function load(kind, reset = false) {
        if (!validate(kind)) return;
        const s = state[kind];
        if (reset || !s.filter) { s.page = 0; s.filter = filters(kind); }
        const sequence = ++s.sequence, params = new URLSearchParams(s.filter); params.set('page', s.page);
        setError('page-error', null);
        $(`${kind}-body`).innerHTML = `<tr><td colspan="10" class="empty">불러오는 중…</td></tr>`;
        $(`${kind}-pager`).replaceChildren(); $(`${kind}-count`).textContent = '';
        try {
            const data = await apiGet(`/api/attendance-manage/${kind}?${params}`);
            if (sequence !== s.sequence) return;
            if (data.totalPages > 0 && s.page >= data.totalPages) { s.page = data.totalPages - 1; return load(kind); }
            $(`${kind}-count`).textContent = `전체 ${data.totalElements}건 · 시간 표기: 시:분:초`;
            $(`${kind}-body`).innerHTML = data.items.map(kind === 'records' ? recordRow : summaryRow).join('') || `<tr><td colspan="10" class="empty">조회 결과가 없습니다.</td></tr>`;
            pager(`${kind}-pager`, data, page => { s.page = page; load(kind); });
        } catch (error) {
            if (sequence !== s.sequence) return;
            $(`${kind}-body`).innerHTML = '<tr><td colspan="10" class="empty">조회하지 못했습니다. 조건을 확인하고 다시 조회해 주세요.</td></tr>';
            setError('page-error', error.message);
        }
    }
    function recordRow(r) {
        return `<tr><td>${employee(r.employee)}</td><td>${e(accountLabels[r.employee.status] ?? r.employee.status)}</td><td>${e(r.workDate)}</td><td>${badge(r.status)}</td><td>${e(stamp(r.clockIn))}</td><td>${e(stamp(r.clockOut))}</td><td>${duration(r.workedSeconds)}</td><td>${duration(r.breakSeconds)}</td><td><button class="btn small" type="button" data-record-id="${e(r.id)}">상세</button></td></tr>`;
    }
    function summaryRow(r) {
        return `<tr><td>${employee(r.employee)}</td><td>${e(accountLabels[r.employee.status] ?? r.employee.status)}</td><td>${e(r.recordedDays)}</td><td>${e(r.completedDays)}</td><td>${duration(r.workedSeconds)}</td><td>${duration(r.breakSeconds)}</td><td>${e(r.workingCount)}</td><td>${e(r.onBreakCount)}</td><td>${e(r.missingCount)}</td><td>${e(r.unconfirmedCount)}</td></tr>`;
    }
    for (const kind of ['records', 'summary']) {
        $(`${kind}-filter`).addEventListener('submit', event => { event.preventDefault(); load(kind, true); });
        $(`${kind}-download`).addEventListener('click', async () => {
            const button = $(`${kind}-download`); if (button.disabled || !validate(kind)) return;
            // 입력된 조건으로 재조회와 다운로드를 함께 실행해 화면과 파일의 검색 조건을 일치시킨다.
            const params = filters(kind); load(kind, true);
            button.disabled = true; button.textContent = '파일 준비 중…'; setError('page-error', null);
            try { await apiDownload(`/api/attendance-manage/${kind}.csv?${params}`, `attendance-${kind}.csv`); }
            catch (error) { setError('page-error', error.message); }
            finally { button.disabled = false; button.textContent = 'CSV 다운로드'; }
        });
    }
    function selectTab(kind) {
        activeTab = kind; setError('page-error', null); setError('page-success', null);
        document.querySelectorAll('[data-tab]').forEach(button => {
            const selected = button.dataset.tab === kind;
            button.classList.toggle('active', selected); button.setAttribute('aria-selected', String(selected)); button.tabIndex = selected ? 0 : -1;
            $(`panel-${button.dataset.tab}`).hidden = !selected;
        });
        if (kind === 'corrections') document.dispatchEvent(new Event('attendance-corrections-open'));
        else load(kind); // 승인 후 돌아올 때도 최신 합계를 조회한다.
    }
    const tabs = [...document.querySelectorAll('[data-tab]')];
    tabs.forEach((button, index) => {
        button.addEventListener('click', () => selectTab(button.dataset.tab));
        button.addEventListener('keydown', event => {
            const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : null;
            if (next === null) return;
            event.preventDefault(); tabs[next].focus(); selectTab(tabs[next].dataset.tab);
        });
    });
    let detailSequence = 0;
    $('records-body').addEventListener('click', async event => {
        const button = event.target.closest('[data-record-id]'); if (!button) return;
        const sequence = ++detailSequence;
        try {
            const r = await apiGet(`/api/attendance-manage/records/${button.dataset.recordId}`);
            if (sequence !== detailSequence || activeTab !== 'records') return;
            $('record-detail').innerHTML = `<p>${employee(r.employee)} · ${e(r.workDate)}</p><p>현재 계정 상태: ${e(accountLabels[r.employee.status])}</p>${snapshot(r)}<p>확정 순근무 ${duration(r.workedSeconds)} · 확정 휴게 ${duration(r.breakSeconds)}</p>`;
            $('record-dialog').showModal();
        } catch (error) { setError('page-error', error.message); }
    });
    load('records', true);
})();
