// ===== 사진 기반 사이즈 이미지 프리뷰/수동 조정 에디터 =====
// at-a-glance coupang-products.js 의 편집기 이식본.
// 확인 시 현재 편집 결과를 독립 이미지로 생성하여 이미지 목록에 추가한다.
import { escapeAttribute, escapeHtml, sendJson, showToast } from './util.js';
import { snapPointToAngle } from './angle-snap.js';
import { IDENTITY_SCENE, displayedPoint, modelPoint, verticalHeight, scaleOutputLayout, comparisonPlacements, setComparisonPlacements } from './size-guide-comparison.js?v=20261021-multi-comparison';

const sizeGuideEditor = {
    modal: null,
    ctx: null,
    layout: null,
    displayName: '',
    dragging: null,
    requestSeq: 0,
    scaleTimer: null,
    committing: false,
    sceneTransform: IDENTITY_SCENE,
    comparisons: null,
    photoView: null
};

const SIZE_GUIDE_DIMENSION_LABELS = { width: '가로', height: '높이', depth: '폭' };

function sgEl(selector) {
    return sizeGuideEditor.modal ? sizeGuideEditor.modal.querySelector(selector) : null;
}

function clamp01(value) {
    return Math.min(1, Math.max(0, value));
}

/**
 * ctx: { productCode, brandCode, templateKey, baseImageUrl, dimensions, sizeLabel,
 *        dimensionLabels?: { width, depth, height }  // 핸들 툴팁 이름(벨트: 총길이/너비)
 *        onConfirm({ templateKey, baseImageUrl, layout, displayName }) }
 */
export async function openSizeGuideEditor(ctx) {
    if (sizeGuideEditor.committing) return;
    const modal = ensureSizeGuideEditorModal();
    sizeGuideEditor.requestSeq += 1;
    clearTimeout(sizeGuideEditor.scaleTimer);
    sizeGuideEditor.ctx = ctx;
    sizeGuideEditor.sceneTransform = IDENTITY_SCENE;
    sizeGuideEditor.photoView = null;
    sizeGuideEditor.layout = ctx.initialLayout ? structuredClone(ctx.initialLayout) : null;
    sizeGuideEditor.dragging = null;
    sizeGuideEditor.focusHandle = null;
    const preview = sgEl('[data-sg-preview]');
    if (preview) preview.removeAttribute('src');
    const overlay = sgEl('[data-sg-overlay]');
    if (overlay) overlay.querySelectorAll('.sg-handle').forEach(node => node.remove());
    const svg = sgEl('[data-sg-svg]');
    if (svg) svg.innerHTML = '';
    if (!modal.open) modal.showModal();
    modal.setAttribute('aria-hidden', 'false');
    setComparisonStatus('');
    loadComparisons(ctx);

    if (sizeGuideEditor.layout) {
        await restoreSizeGuideEditor();
    } else {
        await seedSizeGuideEditor();
    }
}

function closeSizeGuideEditor() {
    if (sizeGuideEditor.committing) return;
    const modal = sizeGuideEditor.modal;
    if (!modal) return;
    modal.close();
    modal.setAttribute('aria-hidden', 'true');

    clearTimeout(sizeGuideEditor.scaleTimer);
    sizeGuideEditor.ctx = null;
    sizeGuideEditor.dragging = null;
    sizeGuideEditor.requestSeq += 1; // 진행 중인 프리뷰 응답 무효화
}

function sizeGuideEditorRequestBody(layout) {
    const ctx = sizeGuideEditor.ctx;
    return {
        brandCode: ctx.brandCode,
        mode: 'PHOTO',
        templateKey: ctx.templateKey,
        baseImageUrl: ctx.baseImageUrl,
        layout: layout ? structuredClone(layout) : null,
        dimensions: ctx.dimensions ? structuredClone(ctx.dimensions) : null,
        sizeLabel: ctx.sizeLabel ?? null
    };
}

function previewUrl() {
    const ctx = sizeGuideEditor.ctx;
    return `/api/product-images/products/${encodeURIComponent(ctx.productCode)}/size-guide/preview`;
}

function invalidatePreview() {
    sizeGuideEditor.focusHandle = null;
    sizeGuideEditor.requestSeq += 1;
    clearTimeout(sizeGuideEditor.scaleTimer);
    toggleSizeGuideSpinner(false);
}

function setComparisonStatus(message) {
    const status = sgEl('[data-sg-comparison-status]');
    if (status) status.textContent = message;
}

function applySizeGuidePreview(result) {
    sizeGuideEditor.layout = setComparisonPlacements(result.layout, comparisonPlacements(result.layout));
    sizeGuideEditor.sceneTransform = result.sceneTransform || IDENTITY_SCENE;
    sizeGuideEditor.photoView = result.photoView || null;
    sizeGuideEditor.displayName = result.displayName || sizeGuideEditor.displayName;
    const preview = sgEl('[data-sg-preview]');
    if (preview) preview.src = result.imageDataUrl;
    const scale = sgEl('[data-sg-scale]');
    if (scale) {
        scale.min = .01; scale.max = 2.5;
        scale.value = sizeGuideEditor.layout?.photo?.scale ?? 1;
    }
    syncOutputScale();
    syncComparisonSelection();
    renderSizeGuideEditorHandles();
}

function syncOutputScale() {
    const value = sgEl('[data-sg-scale-value]');
    const maximum = sgEl('[data-sg-scale-maximum]');
    if (value) value.textContent = `${Number(sizeGuideEditor.layout?.photo?.scale ?? 1).toFixed(2)}×`;
    if (maximum) maximum.textContent = sizeGuideEditor.photoView ? `현재 배치 최대 ${sizeGuideEditor.photoView.maximumScale.toFixed(2)}×` : '';
}

async function loadComparisons(ctx) {
    const select = sgEl('[data-sg-comparison]');
    const retry = sgEl('[data-sg-comparison-retry]');
    select.disabled = true; retry.hidden = true;
    try {
        const presets = sizeGuideEditor.comparisons || await sendJson('/api/product-images/size-guide/comparisons');
        if (sizeGuideEditor.ctx !== ctx) return;
        if (!Array.isArray(presets) || !presets.length) throw new Error('비교 대상 목록을 불러오지 못했습니다.');
        sizeGuideEditor.comparisons = presets;
        select.innerHTML = '<option value="">추가할 비교 대상 선택</option>' + presets.map(preset =>
            `<option value="${escapeAttribute(preset.id)}">${escapeHtml(preset.name)}</option>`).join('');
        select.disabled = sizeGuideEditor.committing;
        syncComparisonSelection();
    } catch (error) {
        if (sizeGuideEditor.ctx !== ctx) return;
        setComparisonStatus(error.message || '비교 대상 목록을 불러오지 못했습니다.');
        retry.hidden = false;
    }
}

function syncComparisonSelection() {
    const select = sgEl('[data-sg-comparison]');
    if (!select) return;
    const placements = comparisonPlacements(sizeGuideEditor.layout);
    select.value = '';
    for (const option of select.options) option.disabled = placements.some(p => p.presetId === option.value);
    const presetFor = placement => sizeGuideEditor.comparisons?.find(p => p.id === placement.presetId);
    const detail = preset => preset ? `높이 ${preset.heightCm}cm` +
        (preset.widthCm == null ? ' · 가로는 사진 비율' : ` · 가로 ${preset.widthCm}cm`) : '';
    const details = sgEl('[data-sg-comparison-details]');
    if (details) details.textContent = placements.length === 1 ? detail(presetFor(placements[0])) :
        placements.length ? `${placements.length}개 선택 · 높이 점선 기준으로 동일한 실물 비율 적용` : '';
    const list = sgEl('[data-sg-comparison-list]');
    if (list) list.innerHTML = placements.map((placement, index) => {
        const preset = presetFor(placement);
        return `<div class="sg-comparison-item"><span>${escapeHtml(preset?.name || placement.presetId)}<small>${escapeHtml(detail(preset))}</small></span><button type="button" class="btn small" data-sg-comparison-remove="${index}" aria-label="${escapeAttribute((preset?.name || placement.presetId) + ' 비교 제거')}">제거</button></div>`;
    }).join('');
    const clear = sgEl('[data-sg-comparison-clear]');
    if (clear) clear.hidden = placements.length === 0;
}

function selectComparison() {
    if (!sizeGuideEditor.layout || sizeGuideEditor.committing) return;
    const id = sgEl('[data-sg-comparison]').value;
    if (!id) { removeComparison(null); return; }
    const preset = sizeGuideEditor.comparisons?.find(value => value.id === id);
    const placements = comparisonPlacements(sizeGuideEditor.layout);
    if (!preset || placements.some(value => value.presetId === id)) return;
    try { verticalHeight(sizeGuideEditor.layout); }
    catch (error) { setComparisonStatus(error.message); return; }
    invalidatePreview();
    setComparisonPlacements(sizeGuideEditor.layout, [...placements,
        { presetId: preset.id, version: preset.version, centerXRatio: null, baseYRatio: null }]);
    syncComparisonSelection();
    renderSizeGuideEditorHandles();
    setComparisonStatus('');
    requestSizeGuidePreview();
}
function removeComparison(index) {
    if (!sizeGuideEditor.layout || sizeGuideEditor.committing) return;
    invalidatePreview();
    setComparisonPlacements(sizeGuideEditor.layout, comparisonPlacements(sizeGuideEditor.layout).filter((_, i) => index != null && i !== index));
    sizeGuideEditor.dragging = null;
    syncComparisonSelection();
    renderSizeGuideEditorHandles();
    requestSizeGuidePreview();
}
function comparisonIndex(kind) {
    const match = /^comparison(?::([0-9]+))?$/.exec(kind);
    return match ? Number(match[1] || 0) : null;
}

// 최초 열기/초기화: layout=null 로 요청해 서버가 돌려준 기본 레이아웃으로 핸들을 seed 한다.
async function seedSizeGuideEditor() {
    const ctx = sizeGuideEditor.ctx;
    if (!ctx) return;
    const seq = ++sizeGuideEditor.requestSeq;
    toggleSizeGuideSpinner(true);
    try {
        const result = await sendJson(previewUrl(), 'POST', sizeGuideEditorRequestBody(null));
        if (seq !== sizeGuideEditor.requestSeq) return;
        applySizeGuidePreview(result);
    } catch (error) {
        if (seq === sizeGuideEditor.requestSeq) {
            showToast(error.message || '미리보기를 만들지 못했습니다.', true);
            setComparisonStatus(error.message || '미리보기를 만들지 못했습니다.');
            if (sizeGuideEditor.layout) renderSizeGuideEditorHandles();
        }
    } finally {
        if (seq === sizeGuideEditor.requestSeq) toggleSizeGuideSpinner(false);
    }
}

// 이전 편집 레이아웃 그대로 다시 열기: 현재 layout 으로 프리뷰 + 핸들 렌더.
async function restoreSizeGuideEditor() {
    const seq = ++sizeGuideEditor.requestSeq;
    toggleSizeGuideSpinner(true);
    try {
        const result = await sendJson(previewUrl(), 'POST', sizeGuideEditorRequestBody(sizeGuideEditor.layout));
        if (seq !== sizeGuideEditor.requestSeq) return;
        applySizeGuidePreview(result);
    } catch (error) {
        if (seq === sizeGuideEditor.requestSeq) {
            showToast(error.message || '미리보기를 만들지 못했습니다.', true);
            setComparisonStatus(error.message || '미리보기를 만들지 못했습니다.');
            if (sizeGuideEditor.layout) renderSizeGuideEditorHandles();
        }
    } finally {
        if (seq === sizeGuideEditor.requestSeq) toggleSizeGuideSpinner(false);
    }
}

// 조정 후: 현재 layout 으로 서버 이미지를 다시 받아 미리보기만 갱신(핸들 위치는 유지).
async function requestSizeGuidePreview() {
    const ctx = sizeGuideEditor.ctx;
    if (!ctx || !sizeGuideEditor.layout) return;
    const seq = ++sizeGuideEditor.requestSeq;
    toggleSizeGuideSpinner(true);
    try {
        const result = await sendJson(previewUrl(), 'POST', sizeGuideEditorRequestBody(sizeGuideEditor.layout));
        if (seq !== sizeGuideEditor.requestSeq) return;
        applySizeGuidePreview(result);
        setComparisonStatus('');
    } catch (error) {
        if (seq === sizeGuideEditor.requestSeq) {
            setComparisonStatus(error.message || '미리보기 갱신 실패');
            showToast(error.message || '미리보기 갱신 실패', true);
        }
    } finally {
        if (seq === sizeGuideEditor.requestSeq) toggleSizeGuideSpinner(false);
    }
}

function debouncedSizeGuidePreview() {
    clearTimeout(sizeGuideEditor.scaleTimer);
    sizeGuideEditor.scaleTimer = setTimeout(requestSizeGuidePreview, 250);
}

// 생성이 성공한 뒤에만 닫는다. 실패하면 조정한 레이아웃을 그대로 유지한다.
async function commitSizeGuideEditor() {
    const ctx = sizeGuideEditor.ctx;
    if (!ctx || !sizeGuideEditor.layout || sizeGuideEditor.committing) return;
    if (sizeGuideEditor.layout.comparison) {
        try { verticalHeight(sizeGuideEditor.layout); }
        catch (error) { setComparisonStatus(error.message); return; }
    }
    const result = {
        templateKey: ctx.templateKey,
        baseImageUrl: ctx.baseImageUrl,
        layout: structuredClone(sizeGuideEditor.layout),
        displayName: sizeGuideEditor.displayName
    };
    sizeGuideEditor.committing = true;
    sizeGuideEditor.requestSeq += 1;
    clearTimeout(sizeGuideEditor.scaleTimer);
    sizeGuideEditor.dragging = null;
    const controls = sizeGuideEditor.modal.querySelectorAll('button, input, select');
    controls.forEach(control => { control.disabled = true; });
    sgEl('[data-sg-confirm]').textContent = '추가 중…';
    toggleSizeGuideSpinner(true);
    try {
        await ctx.onConfirm(result);
        sizeGuideEditor.committing = false;
        closeSizeGuideEditor();
    } catch (error) {
        showToast(error.message || '사이즈 이미지를 추가하지 못했습니다. 다시 시도해주세요.', true);
    } finally {
        sizeGuideEditor.committing = false;
        controls.forEach(control => { control.disabled = false; });
        sgEl('[data-sg-comparison]').disabled = !sizeGuideEditor.comparisons;
        syncComparisonSelection();
        sgEl('[data-sg-confirm]').textContent = '확인';
        toggleSizeGuideSpinner(false);
    }
}

function sizeGuidePoint(kind, point) {
    const layout = sizeGuideEditor.layout;
    if (!layout) return null;
    if (kind === 'photo') return { x: layout.photo.centerXRatio + (sizeGuideEditor.photoView?.subjectOffsetXRatio || 0),
        y: layout.photo.centerYRatio + (sizeGuideEditor.photoView?.subjectOffsetYRatio || 0) };
    if (comparisonIndex(kind) != null) {
        const placement = comparisonPlacements(layout)[comparisonIndex(kind)];
        return placement?.centerXRatio == null ? null : { x: placement.centerXRatio, y: placement.baseYRatio };
    }
    const arrow = layout[kind];
    if (!arrow) return null;
    if (point === 'start') return { x: arrow.startXRatio, y: arrow.startYRatio };
    if (point === 'end') return { x: arrow.endXRatio, y: arrow.endYRatio };
    if (point === 'mid') {
        return { x: (arrow.startXRatio + arrow.endXRatio) / 2, y: (arrow.startYRatio + arrow.endYRatio) / 2 };
    }
    return null;
}

function withCenteredSizeGuideLabel(arrow) {
    return {
        ...arrow,
        labelXRatio: (arrow.startXRatio + arrow.endXRatio) / 2,
        labelYRatio: (arrow.startYRatio + arrow.endYRatio) / 2
    };
}

// 화살표 중간 핸들 드래그: 각도/길이를 유지한 채 양끝을 같은 양만큼 평행이동(경계는 모양 보존하도록 delta clamp).
function moveSizeGuideArrow(kind, midX, midY) {
    const layout = sizeGuideEditor.layout;
    const arrow = layout ? layout[kind] : null;
    if (!arrow) return;
    const oldMidX = (arrow.startXRatio + arrow.endXRatio) / 2;
    const oldMidY = (arrow.startYRatio + arrow.endYRatio) / 2;
    const minX = Math.min(arrow.startXRatio, arrow.endXRatio);
    const maxX = Math.max(arrow.startXRatio, arrow.endXRatio);
    const minY = Math.min(arrow.startYRatio, arrow.endYRatio);
    const maxY = Math.max(arrow.startYRatio, arrow.endYRatio);
    const dx = layout.outputSpace ? midX - oldMidX : Math.min(1 - maxX, Math.max(-minX, midX - oldMidX));
    const dy = layout.outputSpace ? midY - oldMidY : Math.min(1 - maxY, Math.max(-minY, midY - oldMidY));
    layout[kind] = withCenteredSizeGuideLabel({
        ...arrow,
        startXRatio: arrow.startXRatio + dx,
        startYRatio: arrow.startYRatio + dy,
        endXRatio: arrow.endXRatio + dx,
        endYRatio: arrow.endYRatio + dy
    });
}

function repositionSizeGuideArrowHandles(kind) {
    const overlay = sgEl('[data-sg-overlay]');
    if (!overlay) return;
    overlay.querySelectorAll(`.sg-handle[data-sg-kind="${kind}"]`).forEach(positionSizeGuideHandle);
}

function setSizeGuidePoint(kind, point, x, y) {
    const layout = sizeGuideEditor.layout;
    if (!layout) return;
    if (kind === 'photo') {
        layout.photo = { ...layout.photo, centerXRatio: x - (sizeGuideEditor.photoView?.subjectOffsetXRatio || 0),
            centerYRatio: y - (sizeGuideEditor.photoView?.subjectOffsetYRatio || 0) };
        layout.outputSpace = true;
        return;
    }
    if (comparisonIndex(kind) != null) {
        setComparisonPlacements(layout, comparisonPlacements(layout).map((placement, index) => index === comparisonIndex(kind) ?
            { ...placement, centerXRatio: x, baseYRatio: y } : placement));
        return;
    }
    if (kind === 'height' && layout.comparison) {
        x = point === 'start' ? layout.height.endXRatio : layout.height.startXRatio;
    }
    const arrow = { ...layout[kind] };
    if (point === 'start') {
        arrow.startXRatio = x;
        arrow.startYRatio = y;
    } else if (point === 'end') {
        arrow.endXRatio = x;
        arrow.endYRatio = y;
    }
    layout[kind] = withCenteredSizeGuideLabel(arrow);
}

function renderSizeGuideEditorHandles() {
    const overlay = sgEl('[data-sg-overlay]');
    const svg = sgEl('[data-sg-svg]');
    const layout = sizeGuideEditor.layout;
    if (!overlay || !svg || !layout) return;
    const arrowKinds = ['width', 'height', 'depth'].filter(kind => layout[kind]);

    svg.innerHTML = arrowKinds.map(kind => {
        const arrow = layout[kind];
        const start = displayedPoint({ x: arrow.startXRatio, y: arrow.startYRatio }, sizeGuideEditor.sceneTransform);
        const end = displayedPoint({ x: arrow.endXRatio, y: arrow.endYRatio }, sizeGuideEditor.sceneTransform);
        return `<line data-sg-line="${kind}" class="sg-svg-line" vector-effect="non-scaling-stroke"
            x1="${start.x}" y1="${start.y}" x2="${end.x}" y2="${end.y}"></line>`;
    }).join('');

    const handles = [sizeGuideHandleHtml('photo', 'move', '◆', 'sg-handle-photo', '상품 이미지 이동')];
    comparisonPlacements(layout).forEach((placement, index) => {
        if (placement.centerXRatio == null) return;
        const name = sizeGuideEditor.comparisons?.find(p => p.id === placement.presetId)?.name || placement.presetId;
        handles.push(sizeGuideHandleHtml(index ? `comparison:${index}` : 'comparison', 'move', '◆', 'sg-handle-comparison', `${name} 비교 대상 이동`));
    });
    arrowKinds.forEach(kind => {
        const labels = sizeGuideEditor.ctx?.dimensionLabels || SIZE_GUIDE_DIMENSION_LABELS;
        const name = labels[kind] || SIZE_GUIDE_DIMENSION_LABELS[kind] || kind;
        handles.push(sizeGuideHandleHtml(kind, 'start', '', 'sg-handle-dot', `${name} 화살표 시작`));
        handles.push(sizeGuideHandleHtml(kind, 'end', '', 'sg-handle-dot', `${name} 화살표 끝`));
        handles.push(sizeGuideHandleHtml(kind, 'mid', '', 'sg-handle-mid', `${name} 화살표 통째 이동`));
    });
    overlay.querySelectorAll('.sg-handle').forEach(node => node.remove());
    overlay.insertAdjacentHTML('beforeend', handles.join(''));
    overlay.querySelectorAll('.sg-handle').forEach(positionSizeGuideHandle);
    const focus = sizeGuideEditor.focusHandle;
    if (focus) {
        overlay.querySelector(`[data-sg-kind="${focus.kind}"][data-sg-point="${focus.point}"]`)?.focus({ preventScroll: true });
        sizeGuideEditor.focusHandle = null;
    }
}

function sizeGuideHandleHtml(kind, point, text, className, title) {
    return `<div class="sg-handle ${className}" role="button" tabindex="0" aria-label="${escapeAttribute(title)}" data-sg-kind="${kind}" data-sg-point="${point}" title="${escapeAttribute(title)}">${escapeHtml(text)}</div>`;
}

function positionSizeGuideHandle(element) {
    const point = sizeGuidePoint(element.dataset.sgKind, element.dataset.sgPoint);
    if (!point) return;
    const displayed = displayedPoint(point, sizeGuideEditor.sceneTransform);
    element.style.left = `${displayed.x * 100}%`;
    element.style.top = `${displayed.y * 100}%`;
}

function updateSizeGuideLine(kind) {
    const arrow = sizeGuideEditor.layout ? sizeGuideEditor.layout[kind] : null;
    const line = sgEl(`[data-sg-line="${kind}"]`);
    if (!arrow || !line) return;
    const start = displayedPoint({ x: arrow.startXRatio, y: arrow.startYRatio }, sizeGuideEditor.sceneTransform);
    const end = displayedPoint({ x: arrow.endXRatio, y: arrow.endYRatio }, sizeGuideEditor.sceneTransform);
    line.setAttribute('x1', start.x); line.setAttribute('y1', start.y);
    line.setAttribute('x2', end.x); line.setAttribute('y2', end.y);
}

function onSizeGuideHandleKey(event) {
    const steps = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    const delta = steps[event.key];
    const handle = event.target.closest('.sg-handle');
    if (!delta || !handle || sizeGuideEditor.committing) return;
    const point = sizeGuidePoint(handle.dataset.sgKind, handle.dataset.sgPoint);
    if (!point) return;
    event.preventDefault();
    invalidatePreview();
    const step = event.shiftKey ? 10 : 2;
    const t = sizeGuideEditor.sceneTransform;
    let x = point.x + delta[0] * step / (1440 * t.scale);
    let y = point.y + delta[1] * step / (940 * t.scale);
    if (handle.dataset.sgKind === 'photo' || (!sizeGuideEditor.layout.outputSpace && comparisonIndex(handle.dataset.sgKind) == null)) { x = clamp01(x); y = clamp01(y); }
    if (handle.dataset.sgPoint === 'mid') moveSizeGuideArrow(handle.dataset.sgKind, x, y);
    else setSizeGuidePoint(handle.dataset.sgKind, handle.dataset.sgPoint, x, y);
    // Keep focus while a changed preview arrives and replaces the handles.
    sizeGuideEditor.focusHandle = { kind: handle.dataset.sgKind, point: handle.dataset.sgPoint };
    repositionSizeGuideArrowHandles(handle.dataset.sgKind);
    updateSizeGuideLine(handle.dataset.sgKind);
    debouncedSizeGuidePreview();
}

function onSizeGuidePointerDown(event) {
    if (sizeGuideEditor.committing) return;
    const handle = event.target.closest('.sg-handle');
    if (!handle) return;
    event.preventDefault();
    invalidatePreview();
    sizeGuideEditor.dragging = { kind: handle.dataset.sgKind, point: handle.dataset.sgPoint, el: handle };
    handle.classList.add('dragging');
    try {
        handle.setPointerCapture(event.pointerId);
    } catch (error) {
        /* setPointerCapture 미지원 환경 무시 */
    }
}

function onSizeGuidePointerMove(event) {
    const drag = sizeGuideEditor.dragging;
    const overlay = sgEl('[data-sg-overlay]');
    if (!drag || !overlay) return;
    const rect = overlay.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const point = modelPoint({ x: (event.clientX - rect.left) / rect.width, y: (event.clientY - rect.top) / rect.height }, sizeGuideEditor.sceneTransform);
    const bounded = drag.kind === 'photo' || (!sizeGuideEditor.layout.outputSpace && comparisonIndex(drag.kind) == null);
    let x = bounded ? clamp01(point.x) : point.x;
    let y = bounded ? clamp01(point.y) : point.y;
    if (drag.point === 'mid') {
        // 중간 핸들: 화살표 전체를 평행이동(양끝 점 + 중간 핸들 모두 재배치, 선 갱신).
        moveSizeGuideArrow(drag.kind, x, y);
        repositionSizeGuideArrowHandles(drag.kind);
        updateSizeGuideLine(drag.kind);
        return;
    }
    const isEndpoint = drag.kind !== 'photo' && (drag.point === 'start' || drag.point === 'end');
    let snapped = false;
    if (drag.kind === 'height' && sizeGuideEditor.layout.comparison && isEndpoint) {
        x = sizeGuidePoint('height', drag.point === 'start' ? 'end' : 'start').x;
        snapped = true;
    } else if (isEndpoint && !event.altKey) {
        // 끝점 드래그: 반대쪽 끝점을 축으로 수평·수직·45° 근처에서 자석처럼 붙는다. Alt 를 누르면 자유 각도.
        const pivot = sizeGuidePoint(drag.kind, drag.point === 'start' ? 'end' : 'start');
        if (pivot) ({ x, y, snapped } = snapPointToAngle(pivot, { x, y }));
    }
    setSizeGuidePoint(drag.kind, drag.point, x, y);
    positionSizeGuideHandle(drag.el);
    if (isEndpoint) {
        updateSizeGuideLine(drag.kind);
        repositionSizeGuideArrowHandles(drag.kind);
        setSizeGuideSnapCue(drag, snapped);
    }
}

function setSizeGuideSnapCue(drag, snapped) {
    drag.el.classList.toggle('snapped', snapped);
    const line = sgEl(`[data-sg-line="${drag.kind}"]`);
    if (line) line.classList.toggle('snapped', snapped);
}

function onSizeGuidePointerUp() {
    const drag = sizeGuideEditor.dragging;
    if (!drag) return;
    drag.el.classList.remove('dragging');
    setSizeGuideSnapCue(drag, false);
    sizeGuideEditor.dragging = null;
    requestSizeGuidePreview();
}

function toggleSizeGuideSpinner(on) {
    const spinner = sgEl('[data-sg-spinner]');
    if (spinner) spinner.hidden = !on;
}

function ensureSizeGuideEditorModal() {
    if (sizeGuideEditor.modal) return sizeGuideEditor.modal;
    const wrapper = document.createElement('div');
    wrapper.innerHTML = sizeGuideEditorModalHtml();
    const modal = wrapper.firstElementChild;
    document.querySelector('.product-image-workspace').appendChild(modal);
    sizeGuideEditor.modal = modal;

    modal.querySelector('[data-sg-close]')?.addEventListener('click', closeSizeGuideEditor);
    modal.querySelector('[data-sg-cancel]')?.addEventListener('click', closeSizeGuideEditor);
    FormActions.bindExplicitSubmit(modal.querySelector('[data-sg-form]'), modal.querySelector('[data-sg-confirm]'), commitSizeGuideEditor);
    modal.querySelector('[data-sg-reset]')?.addEventListener('click', () => { invalidatePreview(); seedSizeGuideEditor(); });
    modal.querySelector('[data-sg-comparison]')?.addEventListener('change', selectComparison);
    modal.querySelector('[data-sg-comparison-retry]')?.addEventListener('click', () => loadComparisons(sizeGuideEditor.ctx));
    modal.addEventListener('click', event => {
        const remove = event.target.closest('[data-sg-comparison-remove]');
        if (remove) removeComparison(Number(remove.dataset.sgComparisonRemove));
        if (event.target.closest('[data-sg-comparison-clear]')) removeComparison(null);
        if (event.target === modal) closeSizeGuideEditor();
    });
    const scale = modal.querySelector('[data-sg-scale]');
    scale?.addEventListener('input', () => {
        if (!sizeGuideEditor.layout) return;
        invalidatePreview();
        const previous = sizeGuideEditor.layout.photo.scale;
        sizeGuideEditor.layout = scaleOutputLayout(sizeGuideEditor.layout, Number(scale.value), sizeGuideEditor.photoView?.maximumScale ?? 2.5);
        if (sizeGuideEditor.photoView) {
            const factor = sizeGuideEditor.layout.photo.scale / previous;
            sizeGuideEditor.photoView.subjectOffsetXRatio *= factor;
            sizeGuideEditor.photoView.subjectOffsetYRatio *= factor;
        }
        scale.value = sizeGuideEditor.layout.photo.scale;
        syncOutputScale();
        renderSizeGuideEditorHandles();
        debouncedSizeGuidePreview();
    });
    const overlay = modal.querySelector('[data-sg-overlay]');
    overlay.addEventListener('keydown', onSizeGuideHandleKey);
    overlay.addEventListener('pointerdown', onSizeGuidePointerDown);
    overlay.addEventListener('pointermove', onSizeGuidePointerMove);
    overlay.addEventListener('pointerup', onSizeGuidePointerUp);
    overlay.addEventListener('pointercancel', onSizeGuidePointerUp);
    modal.addEventListener('cancel', event => { event.preventDefault(); closeSizeGuideEditor(); });
    return modal;
}

function sizeGuideEditorModalHtml() {
    return `
        <dialog class="sg-editor-modal" id="sizeGuideEditorModal" role="dialog" aria-modal="true" aria-hidden="true" aria-label="사진 사이즈 이미지 편집">
            <div class="sg-editor-content">
                <div class="sg-editor-head">
                    <strong>사진 사이즈 이미지 편집</strong>
                    <button type="button" class="modal-close" data-sg-close aria-label="닫기" title="닫기">×</button>
                </div>
                <div class="sg-editor-body">
                    <div class="sg-editor-stage" data-sg-stage>
                        <img class="sg-editor-preview" data-sg-preview alt="사이즈 이미지 미리보기" draggable="false">
                        <div class="sg-editor-overlay" data-sg-overlay>
                            <svg class="sg-editor-svg" viewBox="0 0 1 1" preserveAspectRatio="none" data-sg-svg aria-hidden="true"></svg>
                        </div>
                        <div class="sg-editor-spinner" data-sg-spinner hidden><div class="spinner"></div></div>
                    </div>
                    <div class="sg-editor-side">
                        <label class="sg-editor-scale">
                            <span>비교 대상 추가 (여러 개 선택 가능)</span>
                            <select data-sg-comparison aria-label="비교 대상"><option value="">추가할 비교 대상 선택</option></select>
                        </label>
                        <div class="sg-comparison-list" data-sg-comparison-list></div>
                        <button type="button" class="btn small" data-sg-comparison-clear hidden>비교 대상 전체 제거</button>
                        <p class="sg-editor-comparison-details" data-sg-comparison-details></p>
                        <p class="sg-editor-comparison-status" data-sg-comparison-status role="status"></p>
                        <button type="button" class="btn" data-sg-comparison-retry hidden>비교 대상 다시 조회</button>
                        <label class="sg-editor-scale">
                            <span>상품 이미지 크기 <output data-sg-scale-value>1.00×</output></span>
                            <input type="range" min="0.01" max="2.5" step="any" value="1" data-sg-scale>
                            <small data-sg-scale-maximum></small>
                        </label>
                        <p class="sg-editor-hint">
                            · 점선 양끝(<b>●</b>)으로 길이와 각도를 조절하고, 가운데 핸들로 점선을 이동하세요.<br>
                            · 끝점을 끌 때 수평·수직·45° 근처에서는 각도가 자동으로 맞춰집니다(초록색 표시). <b>Alt</b>를 누른 채 끌면 해제됩니다.<br>
                            · 배율은 최종 이미지의 상품 크기입니다. 비교 대상과 점선도 같은 비율로 조절하며, 공간이 부족할 때만 배율을 낮춥니다.<br>
                            · 치수 숫자는 점선에서 간격을 두고 상품 반대쪽에 자동 배치됩니다.<br>
                            · 비교 대상은 여러 개를 추가하고 각각의 <b>◆</b> 핸들로 이동·제거할 수 있습니다.<br>
                            · 상품 이미지는 가운데 핸들(<b>◆</b>)을 끌어 이동, 위 슬라이더로 크기를 조절합니다.<br>
                            · <b>확인</b>을 누르면 편집한 사이즈 이미지가 이미지 순서 맨 뒤에 추가됩니다.
                        </p>
                        <form class="sg-editor-actions" data-sg-form>
                            <button type="button" class="btn" data-sg-reset>초기화</button>
                            <button type="button" class="btn" data-sg-cancel>취소</button>
                            <button type="button" class="btn primary" data-sg-confirm>확인</button>
                        </form>
                    </div>
                </div>
            </div>
        </dialog>
    `;
}
