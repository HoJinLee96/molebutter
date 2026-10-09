/** 포인터 기반 정렬: 페이지를 넘겨도 캡처 대상을 유지하고 터치/마우스를 함께 지원한다. */
export function enableImageDragging({ root, isLocked, imageUrl, onDrop, onPageHover }) {
    let drag = null;
    let suppressClickUntil = 0;
    const pageIds = new Set(['previousImagePage', 'nextImagePage', 'previousTrashPage', 'nextTrashPage']);

    function clearMarks() {
        root.querySelectorAll('.drop-before, .drop-after, .drop-zone-active').forEach(element => {
            element.classList.remove('drop-before', 'drop-after', 'drop-zone-active');
        });
    }

    function locateDrop(x, y) {
        const element = document.elementFromPoint(x, y);
        if (!element || !root.contains(element)) return null;
        if (element.closest('#trashPanel')) return { location: 'trash' };
        const card = element.closest('#imageStrip [data-image-id]');
        if (card) {
            const bounds = card.getBoundingClientRect();
            return { location: 'gallery', targetId: Number(card.dataset.imageId), after: x >= bounds.left + bounds.width / 2, card };
        }
        if (element.closest('#galleryStage')) return { location: 'gallery', targetId: null };
        return null;
    }

    function updateTarget() {
        if (!drag?.active) return;
        clearMarks();
        drag.target = locateDrop(drag.x, drag.y);
        if (drag.target?.location === 'trash') root.querySelector('#trashPanel').classList.add('drop-zone-active');
        else if (drag.target?.card && drag.target.targetId !== drag.id) {
            drag.target.card.classList.add(drag.target.after ? 'drop-after' : 'drop-before');
        } else if (drag.target?.targetId === null) root.querySelector('#galleryStage').classList.add('drop-zone-active');

        const hovered = document.elementFromPoint(drag.x, drag.y)?.closest('.page-button');
        const pageId = hovered && pageIds.has(hovered.id) && !hovered.disabled ? hovered.id : null;
        if (pageId === drag.pageId) return;
        clearTimeout(drag.pageTimer);
        drag.pageId = pageId;
        if (pageId) {
            drag.pageTimer = setTimeout(() => {
                if (!drag?.active) return;
                onPageHover(pageId);
                drag.pageId = null;
                updateTarget();
            }, 650);
        }
    }

    function cancel() {
        if (!drag) return;
        const previous = drag;
        drag = null;
        clearTimeout(previous.pageTimer);
        previous.ghost?.remove();
        clearMarks();
        root.classList.remove('image-dragging');
        if (root.hasPointerCapture(previous.pointerId)) root.releasePointerCapture(previous.pointerId);
        if (previous.active) suppressClickUntil = performance.now() + 350;
    }

    root.addEventListener('pointerdown', event => {
        suppressClickUntil = 0;
        if (isLocked() || event.button !== 0 || drag) return;
        const button = event.target.closest('.image-pick-button');
        const card = button?.closest('[data-image-id]');
        if (!card) return;
        drag = { id: Number(card.dataset.imageId), pointerId: event.pointerId,
            startX: event.clientX, startY: event.clientY, x: event.clientX, y: event.clientY, active: false };
    });
    document.addEventListener('pointermove', event => {
        if (!drag || event.pointerId !== drag.pointerId) return;
        drag.x = event.clientX;
        drag.y = event.clientY;
        if (!drag.active) {
            if (Math.hypot(drag.x - drag.startX, drag.y - drag.startY) < 7) return;
            drag.active = true;
            root.setPointerCapture(drag.pointerId);
            root.classList.add('image-dragging');
            drag.ghost = document.createElement('div');
            drag.ghost.className = 'image-drag-ghost';
            const image = document.createElement('img');
            image.src = imageUrl(drag.id);
            image.alt = '';
            drag.ghost.appendChild(image);
            root.closest('.product-image-workspace').appendChild(drag.ghost);
        }
        if (event.cancelable) event.preventDefault();
        drag.ghost.style.transform = `translate(${drag.x + 12}px, ${drag.y + 12}px)`;
        updateTarget();
    }, { passive: false });
    document.addEventListener('pointerup', event => {
        if (!drag || event.pointerId !== drag.pointerId) return;
        const completed = drag.active ? { id: drag.id, target: locateDrop(event.clientX, event.clientY) } : null;
        cancel();
        if (completed?.target && !isLocked()) onDrop(completed.id, completed.target);
    });
    document.addEventListener('pointercancel', cancel);
    root.addEventListener('lostpointercapture', event => {
        // 터치의 암묵적 버튼 캡처를 root로 옮길 때 발생하는 자식 이벤트는 무시한다.
        if (event.target === root && !root.hasPointerCapture(event.pointerId)) cancel();
    });
    window.addEventListener('blur', cancel);
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && drag?.active) {
            event.preventDefault();
            event.stopImmediatePropagation();
            cancel();
        }
    }, true);
    root.addEventListener('dragstart', event => event.preventDefault());
    root.addEventListener('click', event => {
        if (performance.now() < suppressClickUntil) {
            event.preventDefault();
            event.stopImmediatePropagation();
        }
    }, true);
    return { cancel };
}
