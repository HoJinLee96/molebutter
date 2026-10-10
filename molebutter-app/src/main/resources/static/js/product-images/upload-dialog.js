import { sendJson, showToast, sleep } from './util.js';

const JOBS = '/api/product-images/products/upload/jobs/';
const STORAGE_KEY = 'product-images.pending-upload';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The request ID and immutable input survive response loss and a page refresh. */
export function enableImageUploading({ canOpen, snapshot, onState }) {
    const button = document.getElementById('uploadImagesButton');
    const dialog = document.getElementById('imageUploadDialog');
    const form = document.getElementById('imageUploadForm');
    const code = document.getElementById('uploadProductCodeInput');
    const preview = document.getElementById('uploadPathPreview');
    const error = document.getElementById('uploadDialogError');
    const resultPanel = document.getElementById('uploadResult');
    const status = document.getElementById('uploadStatus');
    const files = document.getElementById('uploadFiles');
    const check = document.getElementById('checkUploadButton');
    let draft = null;
    let pending = readPending();
    let working = false;
    let retrySameRequest = false;

    function sync() {
        const locked = dialog.open || working || pending?.status === 'RUNNING';
        const checkingAvailable = Boolean(pending && !working && !dialog.open);
        button.textContent = working ? '업로드 중…' : retrySameRequest ? '업로드 다시 시도' : pending ? '업로드 결과 확인' : '업로드';
        check.disabled = working;
        check.classList.toggle('hidden', !pending);
        check.textContent = retrySameRequest ? '같은 요청으로 다시 시도' : '결과 확인';
        onState({ locked, checkingAvailable });
    }

    function storePending() {
        try {
            if (pending) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending));
            else sessionStorage.removeItem(STORAGE_KEY);
        } catch { /* A disabled browser store does not change request identity in this page. */ }
    }

    function showStatus(message, failed = false) {
        resultPanel.classList.remove('hidden');
        status.textContent = message;
        status.classList.toggle('error', failed);
    }

    function renderFiles(items) {
        files.replaceChildren();
        for (const file of items || []) {
            const row = document.createElement('li');
            // Only a public HTTPS URL can become a navigation link.
            let url;
            try { url = new URL(file.url); } catch { url = null; }
            if (url?.protocol === 'https:' && !url.username && !url.password) {
                const link = document.createElement('a');
                link.href = url.href;
                link.textContent = file.fileName || file.key || '이미지';
                link.target = '_blank';
                link.rel = 'noopener noreferrer';
                row.append(link);
            } else row.textContent = file.fileName || file.key || '이미지';
            files.append(row);
        }
    }

    function open() {
        if (pending) { checkPending(); return; }
        if (!canOpen() || document.querySelector('dialog[open]')) return;
        const value = snapshot();
        if (!value?.images.length) {
            showStatus('업로드할 사진이 없습니다. 휴지통에서 복구하거나 이미지를 추가해주세요.', true);
            return;
        }
        draft = structuredClone(value);
        document.getElementById('uploadSourceCode').textContent = draft.productCode;
        document.getElementById('uploadImageCount').textContent = draft.images.length;
        code.value = draft.productCode;
        error.hidden = true;
        updatePreview();
        dialog.showModal();
        sync();
        code.focus();
        code.select();
    }

    function close() { dialog.close(); draft = null; sync(); button.focus({ preventScroll: true }); }
    function updatePreview() {
        const value = code.value.trim().toUpperCase();
        preview.textContent = `molebutter/products/${value || '{상품코드}'}/`;
    }

    button.addEventListener('click', open);
    check.addEventListener('click', checkPending);
    code.addEventListener('input', () => { error.hidden = true; updatePreview(); });
    document.getElementById('cancelUploadButton').addEventListener('click', close);
    document.getElementById('closeUploadDialog').addEventListener('click', close);
    dialog.addEventListener('cancel', () => { draft = null; });
    dialog.addEventListener('close', sync);
    FormActions.bindExplicitSubmit(form, document.getElementById('confirmUploadButton'), async event => {
        event.preventDefault();
        if (!draft || pending || working) return;
        const uploadProductCode = code.value.trim().toUpperCase();
        if (!/^[A-Z0-9_-]{4,40}$/.test(uploadProductCode)) {
            error.textContent = '저장할 상품코드를 영문·숫자·밑줄(_)·하이픈(-) 4~40자로 입력해주세요.';
            error.hidden = false;
            code.focus();
            return;
        }
        pending = { status: 'RUNNING', request: { ...draft, requestId: AppUI.uuid(), uploadProductCode } };
        storePending();
        dialog.close();
        draft = null;
        files.replaceChildren();
        await submitPending();
    });

    async function submitPending() {
        if (!pending || working) return;
        working = true;
        retrySameRequest = false;
        sync();
        showStatus('이미지를 공개 버킷에 업로드하고 있습니다.');
        try {
            let job;
            try {
                job = await sendJson('/api/product-images/products/upload', 'POST', pending.request);
            } catch (failure) {
                // Only the POST's definite rejection proves this operation was not accepted.
                if ((failure.status >= 400 && failure.status < 500)
                        || ['IMAGING_UPLOAD_NOT_CONFIGURED', 'IMAGING_UPLOAD_CHECK_FAILED'].includes(failure.code)) {
                    const rejected = pending.request;
                    pending = null;
                    storePending();
                    showStatus(failure.message, true);
                    if ((failure.status === 409 && failure.code === 'IMAGING_UPLOAD_DUPLICATE')
                            || failure.code === 'IMAGING_UPLOAD_CHECK_FAILED') {
                        // Preflight rejection occurred before any write. Keep the input and immutable selection.
                        const { requestId, uploadProductCode, ...selection } = rejected;
                        draft = selection;
                        document.getElementById('uploadSourceCode').textContent = draft.productCode;
                        document.getElementById('uploadImageCount').textContent = draft.images.length;
                        code.value = uploadProductCode;
                        error.textContent = failure.message;
                        error.hidden = false;
                        updatePreview();
                        dialog.showModal();
                        code.focus();
                        code.select();
                    }
                } else {
                    showStatus('업로드 요청의 응답을 받지 못했습니다. 같은 요청의 결과를 확인하고 있습니다.');
                    await queryPending();
                }
                return;
            }
            // A failed result lookup cannot undo an already accepted write.
            try { await followJob(job); }
            catch (failure) { handleQueryFailure(failure); }
        } finally { working = false; sync(); }
    }

    async function checkPending() {
        if (!pending || working) return;
        if (retrySameRequest) { await submitPending(); return; }
        working = true;
        sync();
        try { await queryPending(); }
        finally { working = false; sync(); }
    }

    async function queryPending() {
        if (!pending) return;
        try {
            await followJob(await sendJson(JOBS + encodeURIComponent(pending.request.requestId)));
        } catch (failure) { handleQueryFailure(failure); }
    }

    function handleQueryFailure(failure) {
        if (failure.status === 404 && failure.code === 'IMAGING_UPLOAD_JOB_NOT_FOUND') {
            retrySameRequest = true;
            pending.status = 'NOT_FOUND';
            storePending();
            showStatus('서버에 이 업로드 기록이 없습니다. 같은 요청으로 다시 시도할 수 있습니다.', true);
        } else {
            showStatus(`업로드 결과 확인이 필요합니다. ${failure.message || '잠시 후 결과 확인을 눌러주세요.'}`, true);
        }
    }

    async function followJob(job) {
        for (let attempt = 0; attempt < 120; attempt += 1) {
            if (!pending || job?.id !== pending.request.requestId || !['RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'].includes(job.status)) {
                throw new Error('업로드 결과를 확인할 수 없습니다.');
            }
            const count = (job.result?.files || []).length;
            const total = job.result?.totalCount ?? pending.request.images.length;
            renderFiles(job.result?.files);
            pending.status = job.status;
            storePending();
            if (job.status === 'SUCCEEDED') {
                showStatus(`${count}개 이미지 업로드 완료: molebutter/products/${job.result?.uploadProductCode || pending.request.uploadProductCode}/`);
                pending = null;
                storePending();
                showToast('이미지를 공개 버킷에 업로드했습니다.');
                return;
            }
            if (job.status === 'FAILED') {
                showStatus(`업로드 실패 · ${count}/${total}개 저장. ${job.error || job.message || '다시 시도해주세요.'}${count ? ' 저장된 파일이 있어 같은 상품코드로 다시 업로드할 수 없습니다. 저장 결과를 확인해주세요.' : ''}`, true);
                pending = null;
                storePending();
                return;
            }
            if (job.status === 'UNKNOWN') {
                showStatus(`업로드 결과 확인 필요 · ${count}/${total}개 저장 확인. ${job.error || job.message || '응답이 유실되어 추가 업로드를 막았습니다.'}`, true);
                return;
            }
            showStatus(`이미지 업로드 중 · ${count}/${total}개 저장`);
            await sleep(1000);
            job = await sendJson(JOBS + encodeURIComponent(pending.request.requestId));
        }
        showStatus('업로드가 계속 진행 중입니다. 결과 확인을 눌러 진행 상태를 확인해주세요.');
    }

    // Reload only asks for the existing job; it never starts a new write automatically.
    if (pending) {
        showStatus('이전에 요청한 업로드 결과를 확인하고 있습니다.');
        queueMicrotask(checkPending);
    }
    sync();
}

function readPending() {
    try {
        const value = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
        if (!UUID.test(value?.request?.requestId || '') || !Array.isArray(value.request.images) || !value.request.images.length) return null;
        return value;
    } catch { return null; }
}
