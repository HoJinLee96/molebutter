const { test, before } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let math;
before(async () => math = await import('data:text/javascript;base64,' + fs.readFileSync('molebutter-app/src/main/resources/static/js/product-images/size-guide-comparison.js').toString('base64')));
const arrow = (x1,y1,x2,y2) => ({ startXRatio:x1,startYRatio:y1,endXRatio:x2,endYRatio:y2,labelXRatio:(x1+x2)/2,labelYRatio:(y1+y2)/2 });
const layout = () => ({ photo:{scale:1,centerXRatio:.5,centerYRatio:.5},width:arrow(.4,.6,.6,.6),height:arrow(.6,.4,.6,.6),depth:null,
    comparison:{presetId:'iphone-16',version:1,centerXRatio:.8,baseYRatio:.6} });
const near = (a,b) => assert.ok(Math.abs(a-b)<1e-12, `${a} ~= ${b}`);
test('scene coordinates round trip without CSS viewport or device pixel assumptions', () => {
    for (const scene of [math.IDENTITY_SCENE, {scale:.125,offsetXRatio:.1,offsetYRatio:.4}]) {
        const point = {x:1.3,y:.75}; const restored = math.modelPoint(math.displayedPoint(point,scene),scene);
        near(restored.x,point.x); near(restored.y,point.y);
    }
});
test('vertical calibration keeps Y span while normalizing a tilted old height', () => {
    const next=layout(); next.height.startXRatio=.4; math.verticalHeight(next);
    assert.equal(next.height.startXRatio,.5); assert.equal(next.height.endXRatio,.5);
    assert.equal(next.height.startYRatio,.4); assert.equal(next.height.endYRatio,.6);
    next.height.endYRatio=.4; assert.throws(()=>math.verticalHeight(next),/높이/);
});
test('photo zoom changes every endpoint and comparison placement by exactly the same ratio', () => {
    const original=layout(), next=math.scaleOutputLayout(original,2);
    assert.equal(next.photo.scale,2); near(next.height.endYRatio-next.height.startYRatio,.4);
    near(next.width.endXRatio-next.width.startXRatio,.4); near(next.comparison.centerXRatio,1.1);
    near(next.comparison.baseYRatio,.7); assert.deepEqual(original,layout());
    const restored=math.scaleOutputLayout(next,1); near(restored.height.startYRatio,original.height.startYRatio);
    near(restored.comparison.centerXRatio,original.comparison.centerXRatio);
});
test('output coordinates preserve proportions beyond old model edges instead of clipping individual points', () => {
    const original=layout(); original.width.startXRatio=.1;
    const next=math.scaleOutputLayout(original,2.5);
    near(next.photo.scale,2.5); near(next.width.startXRatio,-.5);
    near(next.height.endYRatio-next.height.startYRatio,.5);
    assert.equal(next.outputSpace,true);
});
test('soft limit clamps final scale while the slider range stays fixed across comparison selection', () => {
    const original=layout(), limit=.8;
    const next=math.scaleOutputLayout(original,2.5,limit);
    near(next.photo.scale,limit);
    next.comparison=null;
    const without=math.scaleOutputLayout(next,.4);
    near(without.photo.scale,.4);
    near(without.height.endYRatio-without.height.startYRatio,.08);
});

test('multiple comparisons all zoom together without mutating input and explicit empty arrays clear legacy selection', () => {
    const original=layout();
    math.setComparisonPlacements(original,[original.comparison,{presetId:'samdasoo-500',version:2,centerXRatio:.7,baseYRatio:.6},
        {presetId:'galaxy-s26',version:2,centerXRatio:null,baseYRatio:null}]);
    const before=structuredClone(original), next=math.scaleOutputLayout(original,2);
    assert.equal(next.comparisons.length,3);
    near(next.comparisons[1].centerXRatio,.9);near(next.comparisons[1].baseYRatio,.7);
    assert.equal(next.comparisons[2].centerXRatio,null);assert.equal(next.comparisons[2].baseYRatio,null);
    assert.deepEqual(next.comparison,next.comparisons[0]);assert.deepEqual(original,before);
    assert.deepEqual(math.comparisonPlacements({...original,comparisons:[]}),[]);
    math.setComparisonPlacements(next,[]);assert.equal(next.comparison,null);assert.deepEqual(next.comparisons,[]);
});
