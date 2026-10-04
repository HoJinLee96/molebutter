/** 근태 화면 공통 표시. 서버에서 받은 문자열은 HTML에 넣기 전에 반드시 이스케이프한다. */
const AttendanceUI = (() => {
    const labels = { WORKING: '근무 중', ON_BREAK: '휴게 중', COMPLETED: '퇴근 완료', MISSING: '퇴근 누락',
        PENDING: '승인 대기', APPROVED: '승인', REJECTED: '반려', CANCELLED: '취소' };
    const { $, escape, stamp, pager } = AppUI;
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
    document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
    return { $, escape, stamp, duration, badge, snapshot, correction, pager };
})();
