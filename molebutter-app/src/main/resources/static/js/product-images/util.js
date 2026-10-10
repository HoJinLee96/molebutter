export function sleep(ms) {
    return new Promise(resolve => window.setTimeout(resolve, ms));
}

export function escapeHtml(value) {
    return AppUI.escape(value);
}

export function escapeAttribute(value) {
    return escapeHtml(String(value));
}

export function formatWon(value) {
    if (value === null || value === undefined) return '-';
    return `${Number(value).toLocaleString('ko-KR')}원`;
}

/** localhost/HTTPS의 클립보드를 우선 사용하고 권한 제한 환경에서도 복사할 수 있게 한다. */
export async function copyText(value) {
    const text = String(value);
    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return;
        } catch {
            // 권한 거부 또는 비보안 접속에서는 사용자 클릭 안에서 대체 복사를 시도한다.
        }
    }
    const previousFocus = document.activeElement;
    const selection = window.getSelection();
    const ranges = Array.from({ length: selection?.rangeCount || 0 }, (_, index) => selection.getRangeAt(index).cloneRange());
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.readOnly = true;
    textarea.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0;';
    // 모달 바깥 요소는 inert이므로 열려 있는 대화상자 안에 붙인다.
    (document.querySelector('dialog[open]') || document.body).appendChild(textarea);
    try {
        textarea.select();
        if (!document.execCommand('copy')) throw new Error('복사 실패');
    } finally {
        textarea.remove();
        previousFocus?.focus({ preventScroll: true });
        selection?.removeAllRanges();
        ranges.forEach(range => selection?.addRange(range));
    }
}

/** Molebutter's helpers supply session refresh, CSRF and operation ids. */
export async function sendJson(url, method = 'GET', body = undefined) {
    if (method === 'GET') return apiGet(url);
    if (method === 'POST') return apiPost(url, body);
    throw new Error('지원하지 않는 요청입니다.');
}

/** Download a private ZIP without opening a server-side folder. */
export async function downloadZip(url, filename, allowRefresh = true) {
    const response = await securedFetch(url, {
        headers: { Accept: 'application/zip, application/json' }, cache: 'no-store'
    });
    if (!response.ok) {
        const payload = await response.json().catch(() => null);
        if (response.status === 401 && payload?.code === 'UNAUTHORIZED') {
            if (allowRefresh && await refreshSession()) return downloadZip(url, filename, false);
            throw new ApiError('세션이 만료되었습니다. 다시 로그인한 후 다운로드해 주세요.', 'UNAUTHORIZED', 401);
        }
        throw new ApiError(payload?.message || 'ZIP 파일을 다운로드하지 못했습니다. 다시 시도해주세요.', payload?.code || 'DOWNLOAD_FAILED', response.status);
    }
    if (!(response.headers.get('Content-Type') || '').toLowerCase().startsWith('application/zip')) {
        throw new Error('ZIP 파일 응답을 받지 못했습니다. 다시 로그인하거나 잠시 후 시도해주세요.');
    }
    const blob = await response.blob();
    const expected = response.headers.get('Content-Length');
    if (expected !== null && blob.size !== Number(expected)) throw new Error('파일 전송이 완료되지 않았습니다. ZIP 다시 다운로드를 눌러주세요.');
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = filename;
    document.querySelector('.product-image-workspace').appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}

let toastElement = null;
let toastTimer = null;

export function showToast(message, isError = false) {
    if (!toastElement) {
        toastElement = document.createElement('div');
        toastElement.className = 'toast';
        document.querySelector('.product-image-workspace').appendChild(toastElement);
        toastElement.setAttribute('role', 'status');
    }
    const workspace = document.querySelector('.product-image-workspace');
    (workspace.querySelector('dialog[open]') || workspace).appendChild(toastElement);
    toastElement.textContent = message;
    toastElement.classList.toggle('error', isError);
    toastElement.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastElement.classList.remove('show'), 2600);
}
