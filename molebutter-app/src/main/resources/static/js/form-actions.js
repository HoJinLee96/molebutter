/** 입력 중 Enter와 사용자가 직접 누른 저장 버튼을 구분하는 공통 규칙. */
(() => {
    const inputs = new WeakMap(), defaults = new WeakMap(), explicit = new WeakMap(), guarded = new WeakSet(), composing = new WeakSet();
    const textTypes = new Set(['text', 'search', 'email', 'password', 'tel', 'url', 'number']);
    function available(button) {
        if (!button || button.isConnected === false || button.disabled || button.matches(':disabled')) return false;
        for (let node = button; node; node = node.parentElement) {
            if (node.hidden || node.inert || node.getAttribute('aria-disabled') === 'true' || node.getAttribute('aria-busy') === 'true') return false;
            const style = window.getComputedStyle(node);
            if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false;
            if (node.tagName === 'DIALOG' && !node.open) return false;
        }
        return true;
    }
    function bindEnter(input, button) {
        if (!input || !button) return;
        inputs.set(input, button);
    }
    function bindEnterSubmit(form, button) {
        if (!form || !button) return;
        defaults.set(form, button);
    }
    function bindExplicitSubmit(form, button, handler) {
        if (!form || !button) return;
        button.type = 'button';
        if (!guarded.has(form)) {
            guarded.add(form);
            form.addEventListener('submit', event => {
                event.preventDefault();
                event.stopImmediatePropagation();
            }, true);
        }
        const previous = explicit.get(button);
        if (previous) { previous.handler = handler; previous.form = form; return; }
        const binding = { form, handler, busy: false };
        explicit.set(button, binding);
        button.addEventListener('click', async event => {
            event.preventDefault();
            if (binding.busy || !available(button)) return;
            const form = binding.form;
            if (!available(form)) return;
            if (!form.noValidate && !button.formNoValidate && !form.reportValidity()) return;
            binding.busy = true;
            try {
                await binding.handler({ currentTarget: form, target: form, submitter: button, originalEvent: event, preventDefault() {} });
            } finally { binding.busy = false; }
        });
    }
    document.addEventListener('compositionstart', event => composing.add(event.target), true);
    document.addEventListener('compositionend', event => composing.delete(event.target), true);
    document.addEventListener('keydown', event => {
        const input = event.target;
        // 줄바꿈, 조합 확정, 선택 상자와 날짜/시간/자동완성의 기본 키 동작은 보존한다.
        if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229 || composing.has(input)
            || input.tagName !== 'INPUT' || !textTypes.has(input.type || 'text') || input.hasAttribute('list')) return;
        event.preventDefault();
        if (event.repeat || input.disabled || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
        const button = inputs.get(input) || (!input.readOnly && defaults.get(input.form));
        if (available(button)) button.click();
    }, true);
    window.FormActions = Object.freeze({ bindEnter, bindEnterSubmit, bindExplicitSubmit });
})();
