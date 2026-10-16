/* V46 migrates owned browser keys without clearing drafts, pending writes, authentication or procurement. */
(() => {
    'use strict';
    const marker = 'molebutter.coupang.schema.v46';
    const renamed = key => key === 'molebutter.coupang.navigation'
        ? 'molebutter.coupang.products.navigation.v46'
        : key === 'molebutter.marketplace.drafts.navigation.v45'
            ? 'molebutter.coupang.drafts.navigation.v46'
            : key.startsWith('molebutter.marketplace.')
                ? key.replace('molebutter.marketplace.', 'molebutter.coupang.') : null;
    for (const name of ['sessionStorage', 'localStorage']) {
        try {
            const storage = globalThis[name];
            if (storage.getItem(marker) === '1') continue;
            const keys = [];
            for (let i = 0; i < storage.length; i++) keys.push(storage.key(i));
            let complete = true;
            for (const key of keys) {
                const destination = key && renamed(key);
                if (!destination) continue;
                const value = storage.getItem(key), existing = storage.getItem(destination);
                if (existing !== null && existing !== value) { complete = false; continue; }
                if (existing === null) storage.setItem(destination, value);
                if (storage.getItem(destination) === value) storage.removeItem(key);
                else complete = false;
            }
            if (complete) storage.setItem(marker, '1');
        } catch { /* Disabled storage must not block the editor or erase its input. */ }
    }
})();
