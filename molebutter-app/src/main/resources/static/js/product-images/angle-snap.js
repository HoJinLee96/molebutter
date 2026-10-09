// ===== 사진 사이즈 편집기 점선 끝점 드래그용 각도 스냅 =====
// 좌표는 1440×940 캔버스 기준 0..1 비율(비등방: X÷1440, Y÷940)이라 각도는 픽셀 공간(dx*1440, dy*940)에서 잰다.
// DOM 의존 없음 — node --test 로 단위 테스트한다.
export const SNAP_STEP_DEG = 45;
export const SNAP_THRESHOLD_DEG = 6;
export const SNAP_MIN_LENGTH_PX = 24;
export const CANVAS_ASPECT = { width: 1440, height: 940 };

export function clamp01(value) {
    return Math.min(1, Math.max(0, value));
}

/**
 * pivot: 고정된 반대쪽 끝점 {x,y} (비율), point: 드래그 중인 끝점 {x,y} (비율, 이미 clamp01 됨).
 * 반환 { x, y, snapped, angleDeg } — snapped=false 면 x,y 는 입력 그대로.
 * 스냅 시 길이(픽셀)를 유지한 채 pivot 에서 스냅 각도 방향으로 투영하고,
 * 캔버스를 벗어나면 각도를 꺾는 대신 길이를 줄인다(가장자리에서도 정확히 수평/수직/45° 유지).
 */
export function snapPointToAngle(pivot, point, options = {}) {
    const {
        aspect = CANVAS_ASPECT,
        stepDeg = SNAP_STEP_DEG,
        thresholdDeg = SNAP_THRESHOLD_DEG,
        minLengthPx = SNAP_MIN_LENGTH_PX
    } = options;
    const unsnapped = { x: point.x, y: point.y, snapped: false, angleDeg: null };
    const dx = (point.x - pivot.x) * aspect.width;
    const dy = (point.y - pivot.y) * aspect.height;
    const length = Math.hypot(dx, dy);
    if (length < minLengthPx) return unsnapped;            // 너무 짧으면 각도가 불안정 → 스냅 안 함
    const angleDeg = Math.atan2(dy, dx) * 180 / Math.PI;   // (-180, 180]
    const nearestDeg = Math.round(angleDeg / stepDeg) * stepDeg; // ±180 경계는 반올림이 자연 처리
    if (Math.abs(angleDeg - nearestDeg) > thresholdDeg) return unsnapped;
    const rad = nearestDeg * Math.PI / 180;
    const ux = roundTiny(Math.cos(rad));                    // cos(90°)=6e-17 → 0 으로 정리해 축에 정확히 맞춤
    const uy = roundTiny(Math.sin(rad));
    const maxLength = Math.min(length,
        rayLimit(pivot.x, ux, aspect.width),
        rayLimit(pivot.y, uy, aspect.height));
    if (maxLength < minLengthPx) return unsnapped;          // pivot 이 가장자리라 바깥으로만 갈 수 있을 때
    return {
        x: clamp01(pivot.x + ux * maxLength / aspect.width),
        y: clamp01(pivot.y + uy * maxLength / aspect.height),
        snapped: true,
        angleDeg: nearestDeg
    };
}

// origin(0..1)에서 축 성분 u 방향으로 나아갈 때 캔버스 경계까지의 픽셀 거리. u=0 이면 제한 없음.
function rayLimit(origin, u, pixels) {
    if (u > 0) return (1 - origin) * pixels / u;
    if (u < 0) return origin * pixels / -u;
    return Infinity;
}

function roundTiny(value) {
    return Math.abs(value) < 1e-9 ? 0 : value;
}
