/** Keep the page stationary until the last modal closes, including dynamically added dialogs. */
(() => {
    const root = document.documentElement;
    const variables = ['--modal-scroll-top', '--modal-scroll-left', '--modal-scroll-padding'];
    let saved = null, suspended = false;

    function lock() {
        if (saved || !document.body) return;
        saved = {
            x: window.scrollX, y: window.scrollY,
            styles: variables.map(name => [name, root.style.getPropertyValue(name), root.style.getPropertyPriority(name)])
        };
        const width = document.body.getBoundingClientRect().width;
        const padding = parseFloat(getComputedStyle(document.body).paddingRight) || 0;
        root.style.setProperty('--modal-scroll-top', `${-saved.y}px`);
        root.style.setProperty('--modal-scroll-left', `${-saved.x}px`);
        root.style.setProperty('--modal-scroll-padding', `${padding}px`);
        root.classList.add('modal-scroll-locked');
        const extra = Math.max(0, document.body.getBoundingClientRect().width - width);
        root.style.setProperty('--modal-scroll-padding', `${padding + extra}px`);
    }
    function unlock() {
        if (!saved) return;
        const previous = saved;
        saved = null;
        root.classList.remove('modal-scroll-locked');
        for (const [name, value, priority] of previous.styles) {
            if (value) root.style.setProperty(name, value, priority);
            else root.style.removeProperty(name);
        }
        window.scrollTo({left: previous.x, top: previous.y, behavior: 'instant'});
    }
    function hasModal() {
        if (document.querySelector('dialog[open]')) return true;
        return [...document.querySelectorAll('[role="dialog"][aria-modal="true"]')]
            .some(node => node.tagName !== 'DIALOG' && !node.hidden && node.getAttribute('aria-hidden') !== 'true' && node.getClientRects().length);
    }
    function sync() { if (suspended) return; if (hasModal()) lock(); else unlock(); }
    // Lock before native dialog focus moves; the observer is also a fallback for older browsers.
    document.addEventListener('beforetoggle', event => {
        if (event.target.tagName === 'DIALOG' && event.newState === 'open') {
            lock();
            queueMicrotask(sync);
        }
    }, true);
    document.addEventListener('close', sync, true);
    new MutationObserver(sync).observe(root, {
        subtree: true, childList: true, attributes: true,
        attributeFilter: ['open', 'role', 'aria-modal', 'aria-hidden', 'hidden', 'class', 'style']
    });
    window.addEventListener('pagehide', () => { suspended = true; unlock(); });
    window.addEventListener('pageshow', () => { suspended = false; sync(); });
    // Navigation state must use the saved page position while the body is fixed.
    window.ModalScroll = Object.freeze({position: () => ({x: saved?.x ?? window.scrollX, y: saved?.y ?? window.scrollY})});
    sync();
})();
