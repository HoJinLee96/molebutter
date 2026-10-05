/** Shared display and link safety. Functions do not install page event handlers. */
const AppUI = (() => {
    const $ = id => document.getElementById(id);
    const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const stamp = value => value ? value.replace('T', ' ').slice(0, 19) : '미기록';
    function webUrl(value) {
        try { const u = new URL(value); return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password ? u.href : ''; }
        catch { return ''; }
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
    return { $, escape, stamp, webUrl, pager };
})();
