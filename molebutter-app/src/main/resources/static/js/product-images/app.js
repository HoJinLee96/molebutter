import { copyText, escapeAttribute, escapeHtml, formatWon, sendJson, showToast, sleep, downloadZip } from './util.js';
import { openSizeGuideEditor } from './size-guide-editor.js';
import { ImageOrder } from './image-order.js';
import { enableImageDragging } from './image-drag.js';
import { enableImageUploading, normalizeStorageProductCode } from './upload-dialog.js';

const workspace = document.querySelector('.product-image-workspace');
const lookupForm = document.getElementById('lookupForm');
const lookupButton = document.getElementById('lookupButton');
const brandSelect = document.getElementById('brandSelect');
const productCodeInput = document.getElementById('productCodeInput');
const storageProductCodeInput = document.getElementById('storageProductCodeInput');
const storageProductCodeError = document.getElementById('storageProductCodeError');
const lookupStatus = document.getElementById('lookupStatus');
const resultPanel = document.getElementById('resultPanel');
const imageStrip = document.getElementById('imageStrip');
const notificationGrid = document.getElementById('notificationGrid');
const noticePreviewStatus = document.getElementById('noticePreviewStatus');
const downloadImagesButton = document.getElementById('downloadImagesButton');
const uploadImagesButton = document.getElementById('uploadImagesButton');
const addNoticeImageButton = document.getElementById('addNoticeImageButton');
const addSizeImageButton = document.getElementById('addSizeImageButton');
const sizeTemplateSelect = document.getElementById('sizeTemplateSelect');
// 서버가 내려주는 지원 템플릿 목록(키 → {displayName, widthLabel, depthLabel, heightLabel, twoDimensional}). 최초 조회 때 한 번 받는다.
let sizeTemplates = new Map();
let sizeTemplatesPromise = null;
const sizeLabelInput = document.getElementById('sizeLabelInput');
const dimWidthInput = document.getElementById('dimWidthInput');
const dimDepthInput = document.getElementById('dimDepthInput');
const dimHeightInput = document.getElementById('dimHeightInput');
const editPhotoButton = document.getElementById('editPhotoButton');
const downloadResult = document.getElementById('downloadResult');
const downloadStatus = document.getElementById('downloadStatus');
const downloadArchiveButton = document.getElementById('downloadArchiveButton');
const galleryStage = document.getElementById('galleryStage');
const previousImagePage = document.getElementById('previousImagePage');
const nextImagePage = document.getElementById('nextImagePage');
const trashStrip = document.getElementById('trashStrip');
const trashStage = document.getElementById('trashStage');
const previousTrashPage = document.getElementById('previousTrashPage');
const nextTrashPage = document.getElementById('nextTrashPage');
const restoreAllImages = document.getElementById('restoreAllImages');

let currentProduct = null;
let imageOrder = new ImageOrder();
let imageEntries = new Map();
let nextImageId = 0;
let generationInProgress = false;
let downloadInProgress = false;
let uploadInProgress = false;
let uploadCheckingAvailable = false;
let trashPage = 0;
let trashPageSize = 8;
let lastArchive = null;
let lookupInProgress = false;
let imagePage = 0;
let imagePageSize = 10;
let noticeFields = new Map();
let noticePreviewSequence = 0;
let noticePreviewTimer = null;

// 이미지는 화면에 들어가는 개수만 보여준다. 선택 상태는 페이지와 별도로 보관한다.
const layoutObserver = new ResizeObserver(() => {
    if (!currentProduct || resultPanel.classList.contains('hidden')) return;
    const columns = Math.max(1, Math.floor((galleryStage.clientWidth + 10) / 164));
    const tileSize = (galleryStage.clientWidth - (columns - 1) * 10) / columns;
    const rows = Math.max(1, Math.floor((galleryStage.clientHeight + 10) / (tileSize + 10)));
    const nextSize = columns * rows;
    imageStrip.style.setProperty('--image-columns', columns);
    if (nextSize !== imagePageSize) {
        imagePage = Math.floor(imagePage * imagePageSize / nextSize);
        imagePageSize = nextSize;
        renderImagePage();
    }
    const trashTile = (trashStage.clientWidth - 6) / 2;
    const nextTrashSize = Math.max(2, Math.floor((trashStage.clientHeight + 6) / (trashTile + 6)) * 2);
    if (nextTrashSize !== trashPageSize) {
        trashPage = Math.floor(trashPage * trashPageSize / nextTrashSize);
        trashPageSize = nextTrashSize;
        renderTrashPage();
    }
});
layoutObserver.observe(galleryStage);
layoutObserver.observe(trashStage);

previousImagePage.addEventListener('click', () => { imagePage -= 1; renderImagePage(); });
nextImagePage.addEventListener('click', () => { imagePage += 1; renderImagePage(); });
previousTrashPage.addEventListener('click', () => { trashPage -= 1; renderTrashPage(); });
nextTrashPage.addEventListener('click', () => { trashPage += 1; renderTrashPage(); });
restoreAllImages.addEventListener('click', () => {
    if (workspaceBusy()) return;
    imageOrder.restoreAll();
    renderImageWorkspace();
    showToast('휴지통의 사진을 모두 복구했습니다.');
});

const imageDragging = enableImageDragging({
    root: document.querySelector('.local-media-layout'),
    isLocked: workspaceBusy,
    imageUrl: id => imageEntries.get(id)?.url || '',
    onPageHover: id => document.getElementById(id).click(),
    onDrop: (id, target) => {
        if (target.location === 'trash') discardImage(id);
        else if (imageOrder.insert(id, target.targetId, target.after)) {
            revealImage(id);
            renderImageWorkspace();
            showToast('사진 순서를 변경했습니다. 1번 사진이 대표 이미지입니다.');
        }
    }
});

enableImageUploading({
    canOpen: () => Boolean(currentProduct) && !workspaceBusy(),
    snapshot: () => currentProduct && ({
        uploadProductCode: readStorageProductCode(),
        productCode: currentProduct.productCode,
        brandCode: currentProduct.brandCode || brandSelect.value,
        images: selectedImageItems(imageOrder.order)
    }),
    onState: ({ locked, checkingAvailable }) => {
        uploadInProgress = locked;
        uploadCheckingAvailable = checkingAvailable;
        syncBusyControls();
        renderImageWorkspace();
    }
});

document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || event.defaultPrevented || event.repeat || workspaceBusy()) return;
    if (resultPanel.classList.contains('hidden') || workspace.querySelector('dialog[open]') || document.body.classList.contains('menu-open')) return;
    if (event.target.closest('input, textarea, select, [contenteditable="true"]')) return;
    if (imageOrder.selected !== null) {
        event.preventDefault();
        discardImage(imageOrder.selected);
    }
});

workspace.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-copy-target], [data-copy-value]');
    if (!button) return;
    const target = button.dataset.copyTarget && document.getElementById(button.dataset.copyTarget);
    let value = target ? (target.value ?? target.textContent) : button.dataset.copyValue;
    // 출고가·판매가: 쿠팡 윙 가격 칸에 바로 붙여 넣도록 "원"과 천 단위 쉼표를 뺀 숫자만 복사한다.
    if ('copyDigits' in button.dataset) {
        value = String(value ?? '').replace(/\D/g, '');
        if (!value) {
            showToast('복사할 가격이 없습니다.', true);
            return;
        }
    }
    try {
        await copyText(value ?? '');
        button.classList.add('copied');
        showToast('복사했습니다.');
        window.setTimeout(() => button.classList.remove('copied'), 1600);
    } catch {
        showToast('복사 권한을 확인해주세요.', true);
    }
});

lookupForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (workspaceBusy()) return;
    const productCode = productCodeInput.value.trim().toUpperCase();
    if (!productCode) {
        setStatus('상품코드를 입력해주세요.', true);
        productCodeInput.focus();
        return;
    }

    setLoading(true);
    setStatus('조회 중입니다.');
    try {
        const product = await sendJson(
            `/api/product-images/products/${encodeURIComponent(productCode)}?brand=${encodeURIComponent(brandSelect.value)}`);
        renderProduct(product);
        setStatus('');
        showToast('상품 정보를 불러왔습니다.');
    } catch (error) {
        resultPanel.classList.add('hidden');
        setStatus(error.message, true);
    } finally {
        setLoading(false);
    }
});

FormActions.bindEnterSubmit(lookupForm, document.getElementById('lookupButton'));
storageProductCodeInput.addEventListener('input', () => { storageProductCodeError.hidden = true; });

downloadImagesButton.addEventListener('click', () => {
    if (!currentProduct || workspaceBusy()) return;
    if (!imageOrder.order.length) {
        setDownloadStatus('다운로드할 사진이 없습니다. 휴지통에서 복구하거나 상품정보 이미지를 추가해주세요.', true);
        return;
    }
    try {
        const request = {
            downloadProductCode: readStorageProductCode(),
            productCode: currentProduct.productCode,
            brandCode: currentProduct.brandCode || brandSelect.value,
            images: selectedImageItems(imageOrder.order),
            includeNoticeImage: false,
            // 이전 서버에도 명시적인 boolean 값을 보낸다.
            includeSizeImage: false
        };
        downloadImages(request, '현재 사진 순서대로 다운로드 중입니다.');
    } catch (error) { setDownloadStatus(error.message, true); }
});

downloadArchiveButton.addEventListener('click', async () => {
    if (!lastArchive || workspaceBusy()) return;
    setDownloadLoading(true);
    try {
        await downloadZip(lastArchive.url, lastArchive.name);
        setDownloadStatus(`${lastArchive.count}개 파일 준비 완료: ${lastArchive.name}`);
    }
    catch (error) { setDownloadStatus(error.message, true); }
    finally { setDownloadLoading(false); }
});

sizeTemplateSelect.addEventListener('change', () => {
    applyDimensionLabels(sizeTemplateSelect.value);
    syncBusyControls();
});

FormActions.bindEnter(sizeLabelInput, addSizeImageButton);
for (const input of [dimWidthInput, dimDepthInput, dimHeightInput]) {
    FormActions.bindEnter(input, addSizeImageButton);
    input.addEventListener('keydown', event => {
        if (event.key === 'ArrowUp' || event.key === 'ArrowDown') event.preventDefault();
    });
}

addSizeImageButton.addEventListener('click', async () => {
    if (!currentProduct || workspaceBusy() || !sizeTemplateSelect.value) return;
    try {
        await generateAndAppendSizeImage({
            mode: 'TEMPLATE', templateKey: sizeTemplateSelect.value,
            baseImageUrl: null, layout: null, dimensions: readManualDimensions(), sizeLabel: sizeLabelInput.value
        });
    } catch (error) {
        setDownloadStatus(error.message, true);
        showToast(error.message, true);
    }
});

editPhotoButton.addEventListener('click', () => {
    if (!currentProduct || workspaceBusy()) return;
    const entry = imageEntries.get(imageOrder.selected);
    const previous = entry?.selection?.mode === 'PHOTO' ? entry.selection : null;
    const baseImageUrl = entry?.kind === 'original' ? entry.url : previous?.baseImageUrl;
    // 자동 분류(sizeGuideTemplateKey)가 없는 상품은 카테고리 목록에서 직접 고른 템플릿으로 편집한다.
    const templateKey = previous?.templateKey || currentProduct.sizeGuideTemplateKey || sizeTemplateSelect.value;
    if (!baseImageUrl || !templateKey) {
        setDownloadStatus(baseImageUrl
            ? '자동 분류되지 않은 상품입니다. 오른쪽 카테고리 목록에서 템플릿을 고른 뒤 다시 눌러주세요.'
            : '편집할 사진을 먼저 선택해주세요.', true);
        if (baseImageUrl) sizeTemplateSelect.focus();
        return;
    }
    let dimensions;
    try {
        dimensions = previous?.dimensions || readManualDimensions();
    } catch (error) {
        setDownloadStatus(error.message, true);
        return;
    }
    const sizeLabel = previous?.sizeLabel ?? sizeLabelInput.value;
    openSizeGuideEditor({
        productCode: currentProduct.productCode, brandCode: currentProduct.brandCode || brandSelect.value,
        templateKey, baseImageUrl, dimensions, sizeLabel, initialLayout: previous?.layout || null,
        dimensionLabels: dimensionLabelsFor(templateKey),
        onConfirm: result => generateAndAppendSizeImage({
            mode: 'PHOTO', templateKey: result.templateKey, baseImageUrl: result.baseImageUrl,
            layout: structuredClone(result.layout), dimensions: structuredClone(dimensions), sizeLabel
        })
    });
});

async function generateAndAppendSizeImage(selection) {
    if (!currentProduct || workspaceBusy()) return;
    if (!selection.templateKey) throw new Error('제품 카테고리를 선택해주세요.');
    const product = currentProduct;
    generationInProgress = true;
    syncBusyControls();
    try {
        const result = await sendJson(
            `/api/product-images/products/${encodeURIComponent(product.productCode)}/size-guide/images`,
            'POST', { ...selection, brandCode: product.brandCode || brandSelect.value });
        if (currentProduct !== product) throw new Error('조회한 상품이 변경되었습니다. 다시 추가해주세요.');
        if (!result.id || !result.imageDataUrl) throw new Error('사이즈 이미지를 생성하지 못했습니다.');
        const id = nextImageId++;
        imageEntries.set(id, {
            kind: 'generated', imageType: 'size', url: result.imageDataUrl, generatedImageId: result.id,
            selection: structuredClone(selection), displayName: result.displayName
        });
        imageOrder.append(id);
        revealImage(id);
        resetDownloadResult();
        showToast('사이즈 이미지를 순서 맨 뒤에 추가했습니다.');
    } catch (error) {
        if (error.status === 404 || error.status === 405) {
            throw new Error('사이즈 이미지 저장 경로가 현재 서버에 없습니다. 최신 코드로 서버를 다시 시작한 뒤 페이지를 새로고침해주세요.');
        }
        throw error;
    } finally {
        generationInProgress = false;
        syncBusyControls();
        renderImageWorkspace();
    }
}

function workspaceBusy() {
    return lookupInProgress || downloadInProgress || generationInProgress || uploadInProgress;
}

function syncBusyControls() {
    const busy = workspaceBusy();
    downloadImagesButton.disabled = busy;
    uploadImagesButton.disabled = busy && !uploadCheckingAvailable;
    downloadArchiveButton.disabled = busy;
    addNoticeImageButton.disabled = busy;
    addNoticeImageButton.textContent = generationInProgress ? '추가 중…' : '상품정보 이미지 추가';
    notificationGrid.querySelectorAll('textarea').forEach(input => { input.disabled = busy; });
    downloadImagesButton.textContent = downloadInProgress ? '다운로드 중…' : '다운로드';
    lookupButton.disabled = busy;
    brandSelect.disabled = busy;
    productCodeInput.disabled = busy;
    storageProductCodeInput.disabled = busy;
    sizeTemplateSelect.disabled = busy || !sizeTemplateSelect.options.length;
    for (const input of [sizeLabelInput, dimWidthInput, dimDepthInput, dimHeightInput]) input.disabled = busy;
    addSizeImageButton.disabled = busy || !sizeTemplateSelect.value;
    addSizeImageButton.textContent = generationInProgress ? '추가 중…' : '추가';
    imageDragging.cancel();
    updateImageControls();
}

async function waitForImageDownloadJob(job, { maxAttempts = 120, intervalMs = 1000 } = {}) {
    if (!job?.id) return job;
    let current = job;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
        if (current.status === 'SUCCEEDED') return current.result;
        if (current.status === 'FAILED') {
            throw new Error(current.errorMessage || current.message || '이미지 다운로드 작업에 실패했습니다.');
        }
        setDownloadStatus('이미지 다운로드 작업을 처리 중입니다.');
        await sleep(intervalMs);
        current = await sendJson(`/api/product-images/products/download/jobs/${encodeURIComponent(job.id)}`);
    }
    throw new Error('이미지 다운로드 작업 완료 확인 시간이 초과되었습니다.');
}

async function downloadImages(request, progressMessage) {
    setDownloadLoading(true);
    lastArchive = null;
    downloadArchiveButton.classList.add('hidden');
    setDownloadStatus(progressMessage);
    try {
        const job = await sendJson('/api/product-images/products/download', 'POST', request);
        const result = await waitForImageDownloadJob(job);
        if (!job?.id || !result?.downloadName) throw new Error('다운로드 파일 정보를 받지 못했습니다. 다시 시도해주세요.');
        // Use the authenticated archive endpoint for this job; never accept an external URL.
        const archivePath = `/api/product-images/products/download/jobs/${encodeURIComponent(job.id)}/archive`;
        lastArchive = { url: archivePath, name: result.downloadName, count: (result.savedFiles || []).length };
        downloadArchiveButton.classList.remove('hidden');
        const count = (result.savedFiles || []).length;
        setDownloadStatus(`${count}개 파일 준비 완료: ${result.downloadName}`);
        await downloadZip(lastArchive.url, lastArchive.name);
        showToast('ZIP 파일을 다운로드했습니다.');
    } catch (error) {
        setDownloadStatus(error.message, true);
    } finally {
        setDownloadLoading(false);
    }
}

function readStorageProductCode() {
    try {
        const value = normalizeStorageProductCode(storageProductCodeInput.value);
        storageProductCodeInput.value = value;
        storageProductCodeError.hidden = true;
        return value;
    } catch (error) {
        storageProductCodeError.textContent = error.message;
        storageProductCodeError.hidden = false;
        storageProductCodeInput.focus();
        throw error;
    }
}

function selectedImageItems(ids) {
    return ids.map(id => {
        const entry = imageEntries.get(id);
        return entry.kind === 'original' ? {
            imageIndex: entry.imageIndex, sourceImageUrl: currentProduct.imageUrls[entry.imageIndex]
        } : { generatedImageId: entry.generatedImageId };
    });
}

// 빈 칸은 조회 당시의 파싱값으로 채워, 생성 이미지의 재편집에서도 같은 치수를 유지한다.
function readManualDimensions() {
    const width = dimWidthInput.value.trim();
    const depth = dimDepthInput.value.trim();
    const height = dimHeightInput.value.trim();
    const labels = dimensionLabelsFor(currentProduct?.sizeGuideTemplateKey || sizeTemplateSelect.value);
    for (const [label, value] of [[labels.width, width], [labels.depth, depth], [labels.height, height]]) {
        if (value && (!/^\d+(?:\.\d+)?$/.test(value) || !Number.isFinite(Number(value)) || Number(value) <= 0)) {
            throw new Error(`${label}에는 0보다 큰 숫자를 입력해주세요.`);
        }
    }
    const fallback = currentProduct?.sizeDimensions || {};
    return {
        width: width || dimValue(fallback.width) || null,
        depth: depth || dimValue(fallback.depth) || null,
        height: height || dimValue(fallback.height) || null
    };
}

function setLoading(isLoading) {
    lookupInProgress = isLoading;
    syncBusyControls();
    renderImageWorkspace();
    lookupButton.textContent = isLoading ? '조회 중…' : '조회';
}

function setStatus(message, isError = false) {
    lookupStatus.textContent = message;
    lookupStatus.classList.toggle('error', isError);
}

function setDownloadStatus(message, isError = false) {
    downloadResult.classList.toggle('hidden', !message);
    downloadStatus.textContent = message;
    downloadStatus.title = message;
    downloadStatus.classList.toggle('error', isError);
    if (isError && !lastArchive) downloadArchiveButton.classList.add('hidden');
}

function resetDownloadResult() {
    lastArchive = null;
    setDownloadStatus('');
    downloadArchiveButton.classList.add('hidden');
}

function setDownloadLoading(isLoading) {
    downloadInProgress = isLoading;
    syncBusyControls();
    renderImageWorkspace();
}

function renderProduct(product) {
    currentProduct = product;
    storageProductCodeInput.value = product.productCode || '';
    storageProductCodeError.hidden = true;
    imageDragging.cancel();
    imageOrder = new ImageOrder((product.imageUrls || []).length);
    imageEntries = new Map((product.imageUrls || []).map((url, imageIndex) => [imageIndex, { kind: 'original', url, imageIndex }]));
    nextImageId = imageEntries.size;
    trashPage = 0;
    resultPanel.classList.remove('hidden');
    resetDownloadResult();
    document.getElementById('productName').textContent = product.productName || product.productCode || '-';
    document.getElementById('productName').title = document.getElementById('productName').textContent;
    document.getElementById('productMeta').textContent = `${(product.imageUrls || []).length}개 이미지`;
    document.getElementById('displayProductCode').textContent = product.productCode || '-';
    document.getElementById('originalPrice').textContent = formatWon(product.originalPrice);
    document.getElementById('salePrice').textContent = formatWon(product.salePrice);
    syncSizeGuideInputs(product);
    populateSizeTemplateSelect(product);

    imagePage = 0;
    renderImageWorkspace();
    renderNoticeFields(product);
}

function syncSizeGuideInputs(product) {
    // 치수 프리필: 파싱값("-"는 빈칸)
    const dims = product.sizeDimensions || {};
    sizeLabelInput.value = product.sizeLabel ?? '';
    dimWidthInput.value = dimValue(dims.width);
    dimDepthInput.value = dimValue(dims.depth);
    dimHeightInput.value = dimValue(dims.height);
}

function dimValue(value) {
    return value && value !== '-' ? value : '';
}

async function loadSizeTemplates() {
    if (!sizeTemplatesPromise) {
        sizeTemplatesPromise = sendJson('/api/product-images/size-guide/templates')
            .then(list => { sizeTemplates = new Map((list || []).map(item => [item.templateKey, item])); })
            .catch(error => { sizeTemplatesPromise = null; throw error; });
    }
    return sizeTemplatesPromise;
}

// 자동 분류된 템플릿을 기본 선택하고, 분류가 없으면 빈 항목을 두어 사용자가 직접 고르게 한다.
async function populateSizeTemplateSelect(product) {
    try {
        await loadSizeTemplates();
    } catch (error) {
        sizeTemplates = new Map();
    }
    if (currentProduct !== product) return;
    const options = ['<option value="">카테고리 직접 선택</option>'];
    for (const [key, item] of sizeTemplates) {
        options.push(`<option value="${escapeAttribute(key)}">${escapeHtml(item.displayName)}</option>`);
    }
    sizeTemplateSelect.innerHTML = options.join('');
    sizeTemplateSelect.value = sizeTemplates.has(product.sizeGuideTemplateKey) ? product.sizeGuideTemplateKey : '';
    applyDimensionLabels(sizeTemplateSelect.value);
    syncBusyControls();
}

function dimensionLabelsFor(templateKey) {
    const item = sizeTemplates.get(templateKey);
    return item
        ? { width: item.widthLabel, depth: item.depthLabel, height: item.heightLabel, twoDimensional: item.twoDimensional }
        : { width: '가로', depth: '폭', height: '높이', twoDimensional: false };
}

// 벨트처럼 치수 이름이 다른 템플릿을 고르면 입력란 이름도 같이 바꾼다(폭을 쓰지 않는 템플릿은 폭 입력 잠금).
function applyDimensionLabels(templateKey) {
    const labels = dimensionLabelsFor(templateKey);
    for (const [input, label] of [[dimWidthInput, labels.width], [dimDepthInput, labels.depth], [dimHeightInput, labels.height]]) {
        input.placeholder = label;
        input.setAttribute('aria-label', label);
        const labelNode = document.querySelector(`label[for="${input.id}"]`);
        if (labelNode) labelNode.textContent = label;
    }
    dimDepthInput.readOnly = labels.twoDimensional;
    dimDepthInput.title = labels.twoDimensional ? `${sizeTemplates.get(templateKey)?.displayName || ''} 템플릿은 폭을 쓰지 않습니다.` : '';
}

function renderImageWorkspace() {
    renderImagePage();
    renderTrashPage();
}

function updatePagination(page, pages, previous, next, statusId) {
    previous.disabled = page <= 0;
    next.disabled = page >= pages - 1;
    document.getElementById(statusId).textContent = `${page + 1} / ${pages}`;
}

function renderImagePage() {
    const pages = Math.max(1, Math.ceil(imageOrder.order.length / imagePageSize));
    imagePage = Math.max(0, Math.min(imagePage, pages - 1));
    updatePagination(imagePage, pages, previousImagePage, nextImagePage, 'imagePageStatus');
    imageStrip.innerHTML = '';
    updateImageControls();
    if (!imageOrder.order.length) {
        imageStrip.innerHTML = '<p class="image-empty">다운로드할 사진이 없습니다.<br>휴지통에서 복구하면 다시 표시됩니다.</p>';
        return;
    }
    imageOrder.order.slice(imagePage * imagePageSize, (imagePage + 1) * imagePageSize).forEach((id, offset) => {
        const number = imagePage * imagePageSize + offset + 1;
        const card = document.createElement('div');
        card.className = `image-card ${id === imageOrder.selected ? 'selected' : ''} ${number === 1 ? 'primary-image' : ''}`;
        card.dataset.imageId = id;
        card.innerHTML = `
            <button class="image-pick-button" type="button" aria-label="사진 ${number} 선택" aria-pressed="${id === imageOrder.selected}" ${workspaceBusy() ? 'disabled' : ''}>
                <img src="${escapeAttribute(imageEntries.get(id).url)}" alt="상품 사진 ${number}" draggable="false" loading="lazy">
            </button>
            ${number === 1 ? '<span class="primary-badge">대표</span>' : ''}
            ${imageEntries.get(id).kind === 'generated' ? `<span class="generated-image-badge">${imageEntries.get(id).imageType === 'notice' ? '상품정보' : '사이즈'}</span>` : ''}
            <span class="image-index">${String(number).padStart(2, '0')}</span>
            <button class="image-remove-button" type="button" aria-label="사진 ${number} 휴지통 이동" ${workspaceBusy() ? 'disabled' : ''}>×</button>
        `;
        card.querySelector('.image-pick-button').addEventListener('click', () => {
            if (workspaceBusy()) return;
            imageOrder.select(id);
            imageStrip.querySelectorAll('.image-card').forEach(item => {
                const selected = Number(item.dataset.imageId) === id;
                item.classList.toggle('selected', selected);
                item.querySelector('.image-pick-button').setAttribute('aria-pressed', String(selected));
            });
            updateImageControls();
        });
        card.querySelector('.image-remove-button').addEventListener('click', () => discardImage(id));
        imageStrip.appendChild(card);
    });
}

function updateImageControls() {
    const position = imageOrder.order.indexOf(imageOrder.selected);
    const selected = position >= 0;
    document.getElementById('selectionCount').textContent = `${imageOrder.order.length}개 다운로드`;
    const entry = imageEntries.get(imageOrder.selected);
    editPhotoButton.disabled = workspaceBusy() || !selected || (entry?.kind !== 'original' && entry?.selection?.mode !== 'PHOTO');
}

function renderTrashPage() {
    const pages = Math.max(1, Math.ceil(imageOrder.trash.length / trashPageSize));
    trashPage = Math.max(0, Math.min(trashPage, pages - 1));
    updatePagination(trashPage, pages, previousTrashPage, nextTrashPage, 'trashPageStatus');
    document.getElementById('trashCount').textContent = imageOrder.trash.length;
    restoreAllImages.disabled = workspaceBusy() || !imageOrder.trash.length;
    trashStrip.innerHTML = '';
    if (!imageOrder.trash.length) {
        trashStrip.innerHTML = '<p class="trash-empty">비어 있습니다<br>사진을 끌어 놓으세요</p>';
        return;
    }
    imageOrder.trash.slice(trashPage * trashPageSize, (trashPage + 1) * trashPageSize).forEach(({ id }) => {
        const card = document.createElement('div');
        card.className = 'image-card trash-card';
        card.dataset.imageId = id;
        card.innerHTML = `
            <button class="image-pick-button" type="button" aria-label="${imageEntries.get(id).kind === 'original' ? `원본 사진 ${id + 1}` : imageEntries.get(id).imageType === 'notice' ? '상품정보 이미지' : '사이즈 이미지'} 복구" ${workspaceBusy() ? 'disabled' : ''}>
                <img src="${escapeAttribute(imageEntries.get(id).url)}" alt="휴지통 사진 ${id + 1}" draggable="false" loading="lazy">
                <span class="restore-label">복구</span>
            </button>
        `;
        card.querySelector('button').addEventListener('click', () => {
            if (workspaceBusy() || !imageOrder.restore(id)) return;
            revealImage(id);
            renderImageWorkspace();
            showToast('사진을 복구했습니다.');
        });
        trashStrip.appendChild(card);
    });
}

function discardImage(id) {
    if (workspaceBusy() || !imageOrder.discard(id)) return;
    trashPage = Math.floor((imageOrder.trash.length - 1) / trashPageSize);
    renderImageWorkspace();
    showToast('휴지통으로 이동했습니다. 사진을 누르거나 끌어 다시 복구할 수 있습니다.');
}

function revealImage(id) {
    const position = imageOrder.order.indexOf(id);
    if (position >= 0) imagePage = Math.floor(position / imagePageSize);
}

// 입력 노드를 유지하므로, 레이아웃 응답 중에도 수정 내용이 보존된다.
function renderNoticeFields(product) {
    window.clearTimeout(noticePreviewTimer);
    const sequence = ++noticePreviewSequence;
    noticeFields = new Map(Object.entries(product.notificationFields || {})
        .filter(([label]) => {
            const normalized = label.normalize('NFC').replace(/[^0-9A-Za-z가-힣]/g, '').toLowerCase();
            return normalized !== '상품코드' && !normalized.includes('as책임자');
        })
        .map(([label, value]) => [label, String(value ?? '')]));
    notificationGrid.replaceChildren();
    noticePreviewStatus.textContent = '';
    for (const [label, value] of noticeFields) {
        const item = document.createElement('label');
        item.className = 'info-item';
        item.dataset.noticeLabel = label;
        const title = document.createElement('span');
        title.className = 'info-label';
        title.textContent = label;
        const input = document.createElement('textarea');
        input.className = 'notice-value';
        input.setAttribute('aria-label', label);
        input.rows = 2;
        input.value = value;
        input.disabled = workspaceBusy();
        input.addEventListener('input', () => {
            noticeFields.set(label, input.value);
            resizeNoticeInput(input);
            noticePreviewStatus.textContent = '';
            window.clearTimeout(noticePreviewTimer);
            // 편집 즉시 이전 응답을 무효화한다. 디바운스 대기 중 도착한 응답도 적용하지 않는다.
            const nextSequence = ++noticePreviewSequence;
            noticePreviewTimer = window.setTimeout(() => refreshNoticeLayout(product, nextSequence), 250);
        });
        item.append(title, input);
        notificationGrid.append(item);
        resizeNoticeInput(input);
    }
    if (!noticeFields.size) notificationGrid.textContent = '표시할 정보가 없습니다.';
    refreshNoticeLayout(product, sequence);
}

function resizeNoticeInput(input) {
    input.style.height = 'auto';
    input.style.height = `${Math.max(44, input.scrollHeight + 2)}px`;
}

window.addEventListener('resize', () => {
    notificationGrid.querySelectorAll('textarea').forEach(resizeNoticeInput);
});

function noticeRequest(product) {
    return { brandCode: product.brandCode || brandSelect.value, fields: Object.fromEntries(noticeFields) };
}

function applyNoticeLayout(cards) {
    const items = [...notificationGrid.querySelectorAll('.info-item')];
    const byLabel = new Map(items.map(item => [item.dataset.noticeLabel, item]));
    const ordered = [], seen = new Set();
    const add = label => {
        const item = byLabel.get(label);
        if (item && !seen.has(item)) { seen.add(item); ordered.push(item); }
    };
    cards.forEach(card => add(card.label));
    // 빈값은 이미지에 나오지 않아도 다시 입력할 수 있도록 원래 순서로 뒤에 남긴다.
    noticeFields.forEach((value, label) => add(label));
    const focused = notificationGrid.contains(document.activeElement) ? document.activeElement : null;
    const selection = focused ? [focused.selectionStart, focused.selectionEnd, focused.selectionDirection] : null;
    const scrolls = [];
    for (let node = notificationGrid.parentElement; node; node = node.parentElement) {
        scrolls.push([node, node.scrollTop, node.scrollLeft]);
    }
    ordered.forEach((item, index) => {
        if (notificationGrid.children[index] !== item) {
            notificationGrid.insertBefore(item, notificationGrid.children[index] || null);
        }
    });
    const widths = new Map(cards.map(card => [card.label, card.fullWidth === true]));
    items.forEach(item => {
        item.classList.toggle('full-width', widths.get(item.dataset.noticeLabel) === true);
        resizeNoticeInput(item.querySelector('textarea'));
    });
    if (focused) {
        if (document.activeElement !== focused) focused.focus({ preventScroll: true });
        focused.setSelectionRange(...selection);
    }
    scrolls.forEach(([node, top, left]) => { node.scrollTop = top; node.scrollLeft = left; });
}

async function refreshNoticeLayout(product, sequence) {
    if (currentProduct !== product || sequence !== noticePreviewSequence) return;
    try {
        const result = await sendJson(
            `/api/product-images/products/${encodeURIComponent(product.productCode)}/notice-image/preview`,
            'POST', noticeRequest(product));
        if (currentProduct !== product || sequence !== noticePreviewSequence) return;
        applyNoticeLayout(result.cards || []);
        noticePreviewStatus.textContent = '';
    } catch (error) {
        if (currentProduct !== product || sequence !== noticePreviewSequence) return;
        noticePreviewStatus.textContent = error.message;
    }
}

addNoticeImageButton.addEventListener('click', async () => {
    if (!currentProduct || workspaceBusy()) return;
    const product = currentProduct;
    const request = noticeRequest(product);
    generationInProgress = true;
    syncBusyControls();
    try {
        const result = await sendJson(
            `/api/product-images/products/${encodeURIComponent(product.productCode)}/notice-image/images`,
            'POST', request);
        if (currentProduct !== product) throw new Error('조회한 상품이 변경되었습니다. 다시 추가해주세요.');
        if (!result.id || !result.imageDataUrl) throw new Error('상품정보 이미지를 생성하지 못했습니다.');
        const id = nextImageId++;
        imageEntries.set(id, {
            kind: 'generated', imageType: 'notice', url: result.imageDataUrl, generatedImageId: result.id,
            noticeFields: structuredClone(request.fields), displayName: result.displayName
        });
        imageOrder.append(id);
        revealImage(id);
        resetDownloadResult();
        showToast('상품정보 이미지를 순서 맨 뒤에 추가했습니다.');
    } catch (error) {
        setDownloadStatus(error.message, true);
        showToast(error.message, true);
    } finally {
        generationInProgress = false;
        syncBusyControls();
        renderImageWorkspace();
    }
});
