/**
 * 공통 fetch 헬퍼. ApiResponse{status, code, message, data} 규약을 전제로 한다.
 *
 * 세션 만료 처리: 401 중에서 code가 UNAUTHORIZED(= access token 없음/만료)면
 * refresh로 한 번 조용히 갱신한 뒤 원래 요청을 재시도하고, 그래도 안 되면 로그인 화면으로 보낸다.
 * 로그인 실패(SIGNIN_FAILED) 같은 다른 401은 code가 달라 재시도 대상이 아니다.
 *
 * 실패(HTTP 4xx/5xx)는 서버 message를 담은 ApiError로 throw — 각 페이지는 catch에서 e.message만 보여주면 된다.
 */
class ApiError extends Error {
    constructor(message, code, status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

let csrfPromise;
let refreshPromise;

function csrfToken() {
    if (!csrfPromise) {
        csrfPromise = fetch('/api/auth/csrf', { cache: 'no-store', credentials: 'same-origin' })
            .then(async res => {
                if (!res.ok) throw new ApiError('보안 토큰을 가져오지 못했습니다.', 'CSRF_FETCH_FAILED', res.status);
                return (await res.json()).data;
            }).catch(error => { csrfPromise = null; throw error; });
    }
    return csrfPromise;
}

async function securedFetch(url, options, retryCsrf = true) {
    const request = { ...options, credentials: 'same-origin', headers: new Headers(options?.headers) };
    const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes((request.method ?? 'GET').toUpperCase());
    if (unsafe) {
        const csrf = await csrfToken();
        request.headers.set(csrf.headerName, csrf.token);
    }
    const response = await fetch(url, request);
    if (unsafe && retryCsrf && response.status === 403) {
        const error = await response.clone().json().catch(() => null);
        if (error?.code === 'INVALID_CSRF_TOKEN') {
            csrfPromise = null;
            return securedFetch(url, options, false);
        }
    }
    return response;
}

// 같은 페이지의 병렬 요청은 하나의 refresh 요청을 공유한다.
function refreshSession() {
    if (!refreshPromise) {
        refreshPromise = securedFetch('/api/auth/refresh', { method: 'POST' })
            .then(res => res.ok).finally(() => { refreshPromise = null; });
    }
    return refreshPromise;
}

async function apiRequest(url, options, allowRefresh = true) {
    const res = await securedFetch(url, options);
    const payload = await res.json().catch(() => null);
    if (res.status === 401 && payload?.code === 'UNAUTHORIZED' && allowRefresh) {
        if (await refreshSession()) return apiRequest(url, options, false);
        location.href = '/signin';
        throw new ApiError('세션이 만료되었습니다. 다시 로그인해 주세요.', 'UNAUTHORIZED', 401);
    }
    if (!res.ok) {
        throw new ApiError(payload?.message ?? '요청에 실패했습니다. 잠시 후 다시 시도해 주세요.',
            payload?.code ?? 'UNKNOWN', res.status);
    }
    return payload?.data ?? null;
}

async function apiGet(url) {
    return apiRequest(url, { headers: { 'Accept': 'application/json' } });
}

async function apiPost(url, body) {
    return apiRequest(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body ?? {}),
    });
}

/** id 요소에 에러 메시지 표시(빈 값이면 숨김). */
function setError(id, message) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = message ?? '';
    el.hidden = !message;
}

/** 재발송 대기 시간은 버튼과 분리해 표시한다. 반환 함수로 타이머와 안내를 정리한다. */
function startCooldown(button, seconds, hint) {
    let remain = seconds;
    button.disabled = true;
    hint.hidden = false;
    hint.textContent = `${remain}초 후 인증번호를 재발송할 수 있습니다.`;
    const stop = () => {
        clearInterval(timer);
        hint.hidden = true;
        hint.textContent = '';
        button.disabled = false;
    };
    const timer = setInterval(() => {
        remain -= 1;
        if (remain <= 0) {
            stop();
        } else {
            hint.textContent = `${remain}초 후 인증번호를 재발송할 수 있습니다.`;
        }
    }, 1000);
    return stop;
}

/** CSV만 저장한다. 인증 갱신과 JSON 오류 처리 후 파일을 생성한다. */
async function apiDownload(url, filename, allowRefresh = true) {
    const res = await securedFetch(url, { headers: { Accept: 'text/csv, application/json' }, cache: 'no-store' });
    if (!res.ok) {
        const payload = await res.json().catch(() => null);
        if (res.status === 401 && payload?.code === 'UNAUTHORIZED') {
            if (allowRefresh && await refreshSession()) return apiDownload(url, filename, false);
            throw new ApiError('세션이 만료되었습니다. 다시 로그인한 후 다운로드해 주세요.', 'UNAUTHORIZED', 401);
        }
        throw new ApiError(payload?.message ?? '다운로드하지 못했습니다. 잠시 후 다시 시도해 주세요.', payload?.code ?? 'DOWNLOAD_FAILED', res.status);
    }
    if (!(res.headers.get('Content-Type') ?? '').toLowerCase().startsWith('text/csv')) {
        throw new ApiError('CSV 파일 응답을 받지 못했습니다. 다시 로그인하거나 잠시 후 시도해 주세요.', 'INVALID_DOWNLOAD', res.status);
    }
    const blob = await res.blob();
    const expected = res.headers.get('Content-Length');
    if (expected !== null && blob.size !== Number(expected)) {
        throw new ApiError('파일 전송이 완료되지 않았습니다. 다시 다운로드해 주세요.', 'INCOMPLETE_DOWNLOAD', res.status);
    }
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl; link.download = filename;
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}
