// Model coordinates stay independent of the preview's fit-to-canvas camera.
export const IDENTITY_SCENE = Object.freeze({ scale: 1, offsetXRatio: 0, offsetYRatio: 0 });
export function comparisonPlacements(layout) {
    return Array.isArray(layout?.comparisons) ? layout.comparisons : layout?.comparison ? [layout.comparison] : [];
}
export function setComparisonPlacements(layout, placements) {
    layout.comparisons = placements;
    layout.comparison = placements[0] || null;
    return layout;
}
export function displayedPoint(point, transform = IDENTITY_SCENE) {
    return { x: point.x * transform.scale + transform.offsetXRatio, y: point.y * transform.scale + transform.offsetYRatio };
}
export function modelPoint(point, transform = IDENTITY_SCENE) {
    return { x: (point.x - transform.offsetXRatio) / transform.scale, y: (point.y - transform.offsetYRatio) / transform.scale };
}
export function verticalHeight(layout) {
    const arrow = layout.height;
    if (!arrow || !Number.isFinite(arrow.startYRatio) || !Number.isFinite(arrow.endYRatio)
            || Math.abs(arrow.endYRatio - arrow.startYRatio) < Number.EPSILON) {
        throw new Error('비교 대상을 표시하려면 높이 점선의 길이를 맞춰주세요.');
    }
    const x = (arrow.startXRatio + arrow.endXRatio) / 2;
    layout.height = { ...arrow, startXRatio: x, endXRatio: x, labelXRatio: x,
        labelYRatio: (arrow.startYRatio + arrow.endYRatio) / 2 };
    return layout;
}
// The layout is the final output space. There is no extra camera scale behind this value.
export function scaleOutputLayout(layout, requestedScale, maximumScale = 2.5) {
    const next = structuredClone(layout);
    const photo = next.photo;
    const target = Math.max(.01, Math.min(2.5, maximumScale, requestedScale));
    const factor = target / photo.scale;
    for (const kind of ['width', 'height', 'depth']) {
        const arrow = next[kind];
        if (!arrow) continue;
        for (const edge of ['start', 'end', 'label']) {
            arrow[edge + 'XRatio'] = photo.centerXRatio + (arrow[edge + 'XRatio'] - photo.centerXRatio) * factor;
            arrow[edge + 'YRatio'] = photo.centerYRatio + (arrow[edge + 'YRatio'] - photo.centerYRatio) * factor;
        }
    }
    const placements = comparisonPlacements(next);
    for (const comparison of placements) if (comparison.centerXRatio != null) {
        comparison.centerXRatio = photo.centerXRatio + (comparison.centerXRatio - photo.centerXRatio) * factor;
        comparison.baseYRatio = photo.centerYRatio + (comparison.baseYRatio - photo.centerYRatio) * factor;
    }
    if (Array.isArray(next.comparisons)) setComparisonPlacements(next, placements);
    photo.scale = target;
    next.outputSpace = true;
    return next;
}
