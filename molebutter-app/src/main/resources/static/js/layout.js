(() => {
    const sidebar = document.getElementById('app-sidebar');
    if (!sidebar) return;
    const openButton = document.getElementById('menu-open'), closeButton = document.getElementById('menu-close');
    const backdrop = document.getElementById('menu-backdrop'), main = document.getElementById('main-content');
    const mobileBar = document.querySelector('.mobile-bar'), mobile = matchMedia('(max-width: 1023px)');
    let opened = false;
    const focusable = () => Array.from(sidebar.querySelectorAll('a[href],button:not([disabled])')).filter(el => el.getClientRects().length);
    function setOpen(value, restore = true) {
        opened = mobile.matches && value;
        sidebar.classList.toggle('is-open', opened);
        sidebar.inert = mobile.matches && !opened;
        if (opened) sidebar.setAttribute('aria-modal', 'true'); else sidebar.removeAttribute('aria-modal');
        if (opened) sidebar.setAttribute('role', 'dialog'); else sidebar.removeAttribute('role');
        backdrop.hidden = !opened;
        openButton.setAttribute('aria-expanded', String(opened));
        document.body.classList.toggle('menu-open', opened);
        if (main) main.inert = opened;
        mobileBar.inert = opened;
        if (opened) closeButton.focus(); else if (restore && mobile.matches) openButton.focus();
    }
    openButton.addEventListener('click', () => setOpen(true));
    closeButton.addEventListener('click', () => setOpen(false));
    backdrop.addEventListener('click', () => setOpen(false));
    mobile.addEventListener('change', () => setOpen(false, false));
    sidebar.addEventListener('keydown', event => {
        if (!opened) return;
        if (event.key === 'Escape') { event.preventDefault(); setOpen(false); }
        if (event.key === 'Tab') {
            const items = focusable(), first = items[0], last = items.at(-1);
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
    });
    setOpen(false, false);
    document.getElementById('signout-btn').addEventListener('click', async event => {
        const button = event.currentTarget;
        if (button.disabled) return;
        button.disabled = true; setError('layout-error', null);
        try { await apiPost('/api/auth/signout'); location.href = '/signin'; }
        catch (error) { setError('layout-error', error.message); button.disabled = false; }
    });
})();
