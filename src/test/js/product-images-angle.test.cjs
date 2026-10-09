// Run with: node --test src/test/js/product-images-angle.test.cjs (no browser, no server)
const assert = require('node:assert/strict');
const { test, before } = require('node:test');
let snap;
before(async () => (snap = await import('data:text/javascript;base64,' + require('node:fs').readFileSync(require('node:path').resolve(__dirname, '../../../molebutter-app/src/main/resources/static/js/product-images/angle-snap.js')).toString('base64'))));

const W = 1440, H = 940;
const deg = value => value * Math.PI / 180;
const near = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 1e-9, message || `${actual} ≈ ${expected}`);
// pivot 에서 픽셀 길이·각도만큼 떨어진 점을 비율 좌표로 만든다.
const at = (pivot, lengthPx, angleDeg) => ({
    x: pivot.x + lengthPx * Math.cos(deg(angleDeg)) / W,
    y: pivot.y + lengthPx * Math.sin(deg(angleDeg)) / H
});

test('snaps to horizontal within threshold and keeps pixel length', () => {
    const pivot = { x: 0.2, y: 0.5 };
    const result = snap.snapPointToAngle(pivot, at(pivot, 400, 3));
    assert.equal(result.snapped, true);
    assert.equal(result.angleDeg, 0);
    near(result.y, 0.5);
    near(result.x, 0.2 + 400 / W);
});

test('vertical snap zeroes the x delta exactly', () => {
    const pivot = { x: 0.5, y: 0.2 };
    const result = snap.snapPointToAngle(pivot, at(pivot, 300, 92));
    assert.equal(result.angleDeg, 90);
    assert.equal(result.x, 0.5);
    near(result.y, 0.2 + 300 / H);
});

test('does not snap outside the threshold and returns the input unchanged', () => {
    const pivot = { x: 0.2, y: 0.5 };
    const point = at(pivot, 400, 10);
    assert.deepEqual(snap.snapPointToAngle(pivot, point), { x: point.x, y: point.y, snapped: false, angleDeg: null });
});

test('angle is measured in pixel space, not ratio space', () => {
    // 비율 공간 45°(dx=dy=0.2)는 픽셀로 288×188 → 약 33° → 스냅 안 됨
    assert.equal(snap.snapPointToAngle({ x: 0.3, y: 0.3 }, { x: 0.5, y: 0.5 }).snapped, false);
    const result = snap.snapPointToAngle({ x: 0.3, y: 0.3 }, { x: 0.3 + 200 / W, y: 0.3 + 200 / H });
    assert.equal(result.angleDeg, 45);
    near((result.x - 0.3) * W, (result.y - 0.3) * H, 'snapped 45° has equal pixel dx/dy');
});

test('clips length at the canvas edge instead of bending the angle', () => {
    const pivot = { x: 0.9, y: 0.5 };
    const result = snap.snapPointToAngle(pivot, { x: 1, y: 0.5 - 10 / H }); // 경계에서 clamp 된 입력, 약 -4°
    assert.equal(result.snapped, true);
    near(result.x, 1);
    assert.equal(result.y, 0.5);
});

test('handles the ±180° boundary', () => {
    const pivot = { x: 0.8, y: 0.5 };
    assert.equal(snap.snapPointToAngle(pivot, at(pivot, 300, 178)).angleDeg, 180);
    const negative = snap.snapPointToAngle(pivot, at(pivot, 300, -178));
    assert.equal(negative.angleDeg, -180);
    assert.equal(negative.y, 0.5);
    near(negative.x, 0.8 - 300 / W);
});

test('too-short segments and outward edge pivots are left alone', () => {
    assert.equal(snap.snapPointToAngle({ x: 0.5, y: 0.5 }, { x: 0.5 + 10 / W, y: 0.5 }).snapped, false);
    assert.equal(snap.snapPointToAngle({ x: 1, y: 0.5 }, { x: 1, y: 0.5 + 1 / H }).snapped, false);
});

test('snap can be tuned through options', () => {
    const pivot = { x: 0.2, y: 0.5 };
    const point = at(pivot, 400, 10);
    assert.equal(snap.snapPointToAngle(pivot, point, { thresholdDeg: 12 }).angleDeg, 0);
    assert.equal(snap.snapPointToAngle(pivot, point, { stepDeg: 10 }).angleDeg, 10);
});
