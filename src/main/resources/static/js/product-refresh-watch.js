/* 상태 요청은 직렬로 실행하고, 완료된 상품의 상세 결과만 한 번 가져온다. */
const ProductRefreshWatch = {
    create({fetchStatus, onState, onComplete, onError, canPoll = () => true, interval = 2500}) {
        let generation = 0, timer;
        const stop = () => { generation++; clearTimeout(timer); };
        function start(id) {
            stop();
            const token = generation, current = () => token === generation;
            const later = () => { if (current()) timer = setTimeout(poll, interval); };
            async function poll() {
                if (!current()) return;
                if (!canPoll()) { later(); return; }
                try {
                    const state = await fetchStatus(id);
                    if (!current()) return;
                    onState(state);
                    if (state.active === true || state.active == null && ['PENDING', 'CHECKING', 'BLOCKED'].includes(state.status)) { later(); return; }
                    await onComplete(state, current);
                    if (current()) stop();
                } catch (err) {
                    if (!current()) return;
                    onError(err);
                    if ([401, 403, 404].includes(err.status)) stop();
                    else later();
                }
            }
            later();
        }
        return {start, stop};
    }
};
