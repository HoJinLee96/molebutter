/** 근태 화면 공통 표시. 서버에서 받은 문자열은 HTML에 넣기 전에 반드시 이스케이프한다. */
const AttendanceUI = (() => {
    const labels = { WORKING: '근무 중', ON_BREAK: '휴게 중', COMPLETED: '퇴근 완료', MISSING: '퇴근 누락',
        PENDING: '승인 대기', APPROVED: '승인', REJECTED: '반려', CANCELLED: '취소' };
    const $ = id => document.getElementById(id);
    const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const stamp = value => value ? value.replace('T', ' ').slice(0, 19) : '미기록';
    const duration = value => value == null ? '미확정' : `${Math.floor(value / 3600)}시간 ${Math.floor(value % 3600 / 60)}분`;
    const badge = status => `<span class="badge ${['MISSING', 'REJECTED'].includes(status) ? 'fail' : ['PENDING', 'ON_BREAK'].includes(status) ? 'pending' : 'ok'}">${escape(labels[status] ?? status)}</span>`;
    function snapshot(record) {
        if (!record) return '<p class="field-hint">기존 출근 기록 없음</p>';
        return `<dl class="attendance-detail"><dt>상태</dt><dd>${badge(record.status)}</dd><dt>출근</dt><dd>${escape(stamp(record.clockIn))}</dd><dt>퇴근</dt><dd>${escape(stamp(record.clockOut))}</dd></dl>
            <h4>휴게</h4>${record.breaks.length ? `<ul>${record.breaks.map(b => `<li>${escape(stamp(b.startedAt))}<br>~ ${escape(stamp(b.endedAt))}</li>`).join('')}</ul>` : '<p class="field-hint">휴게 기록 없음</p>'}`;
    }
    function correction(c) {
        return `<p><strong>${escape(c.userName)}</strong> · ${escape(c.workDate)} ${badge(c.status)}</p>
            <p class="attendance-reason"><strong>신청 사유</strong><br>${escape(c.reason)}</p>
            <div class="attendance-columns"><section><h3>변경 전</h3>${snapshot(c.before)}</section><section><h3>요청 내용</h3>${snapshot(c.after)}</section></div>
            <p class="field-hint">신청: ${escape(stamp(c.requestedAt))}</p>
            ${c.reviewedAt ? `<p>처리: ${escape(c.reviewerName)}${c.selfReviewed ? ' (본인 승인·처리)' : ''}<br>${escape(stamp(c.reviewedAt))}</p>` : ''}
            ${c.reviewComment ? `<p class="attendance-reason"><strong>처리 사유</strong><br>${escape(c.reviewComment)}</p>` : ''}`;
    }
    function pager(id, data, change, { numbered = false, label = '상품 목록 페이지 이동' } = {}) {
        const node = $(id);
        if (numbered) { numberedPager(node, data, change, label); return; }
        node.replaceChildren();
        if (data.totalPages <= 1) return;
        for (const [label, page, disabled] of [['이전', data.page - 1, data.page === 0], ['다음', data.page + 1, data.page + 1 >= data.totalPages]]) {
            const button = document.createElement('button');
            button.type = 'button'; button.className = 'btn small'; button.textContent = label; button.disabled = disabled;
            button.addEventListener('click', () => change(page));
            node.append(button);
            if (label === '이전') node.append(document.createTextNode(`${data.page + 1} / ${data.totalPages}`));
        }
    }
    function numberedPager(node, data, change, label) {
        const restoreFocus = node.contains(document.activeElement);
        node.replaceChildren();
        node.classList.add('pager-numbered');
        node.setAttribute('role', 'navigation');
        node.setAttribute('aria-label', label);
        if (data.totalPages < 1) return;
        const current = data.page, last = data.totalPages - 1;
        function group(className = '') {
            const element = document.createElement('div');
            element.className = 'pager-group ' + className; node.append(element); return element;
        }
        function button(parent, label, target, disabled = false, active = false) {
            const element = document.createElement('button');
            element.type = 'button'; element.className = 'btn small'; element.textContent = label;
            element.disabled = disabled;
            if (active) { element.setAttribute('aria-current', 'page'); element.setAttribute('aria-disabled', 'true'); }
            element.addEventListener('click', () => { if (!disabled && target !== current) change(target); });
            parent.append(element); return element;
        }
        const previous = group();
        button(previous, '맨 처음', 0, current === 0);
        button(previous, '이전', current - 1, current === 0);
        const numbers = group('pager-numbers');
        const start = Math.max(0, Math.min(current - 2, data.totalPages - 5));
        let currentButton;
        for (let page = start; page < Math.min(start + 5, data.totalPages); page++) {
            const element = button(numbers, String(page + 1), page, false, page === current);
            element.setAttribute('aria-label', `${page + 1}페이지`);
            if (page === current) currentButton = element;
        }
        const next = group();
        button(next, '다음', current + 1, current === last);
        button(next, '맨 마지막', last, current === last);
        const summary = document.createElement('span');
        summary.className = 'pager-summary'; summary.textContent = `${current + 1} / ${data.totalPages} 페이지`;
        summary.setAttribute('aria-live', 'polite'); node.append(summary);
        if (restoreFocus) currentButton?.focus();
    }
    document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
    $('signout-btn').addEventListener('click', async () => { try { await apiPost('/api/auth/signout'); } finally { location.href = '/signin'; } });
    return { $, escape, stamp, duration, badge, snapshot, correction, pager };
})();
