const assert = require('node:assert/strict');
const { test, before } = require('node:test');
let ImageOrder;
before(async () => ({ ImageOrder } = await import('data:text/javascript;base64,' + require('node:fs').readFileSync(require('node:path').resolve(__dirname, '../../../molebutter-app/src/main/resources/static/js/product-images/image-order.js')).toString('base64'))));

test('click selects an editing target without changing representative or download order', () => {
    const images = new ImageOrder(4);
    images.select(2);
    assert.equal(images.selected, 2);
    assert.deepEqual(images.order, [0, 1, 2, 3]);
});
test('generated images append after remaining items and use the same reorder/trash/restore model', () => {
    const images = new ImageOrder(3);
    images.discard(1);
    assert.equal(images.append(3), true);
    assert.deepEqual(images.order, [0, 2, 3]);
    assert.equal(images.append(1), false);
    assert.equal(images.append(3), false);
    images.move(3, 0);
    images.discard(3);
    images.restore(3);
    assert.deepEqual(images.order, [3, 0, 2]);
    images.restore(1);
    assert.deepEqual(images.order, [3, 0, 1, 2]);
});
test('reordering in either direction uses stable source IDs', () => {
    const images = new ImageOrder(5);
    images.insert(4, 0);
    assert.deepEqual(images.order, [4, 0, 1, 2, 3]);
    images.insert(4, 2, true);
    assert.deepEqual(images.order, [0, 1, 2, 4, 3]);
    images.move(3, 0);
    assert.deepEqual(images.order, [3, 0, 1, 2, 4]);
});
test('discard clears selection, changes representative when needed, and restores the old position', () => {
    const images = new ImageOrder(4);
    images.select(0);
    images.discard(0);
    assert.deepEqual(images.order, [1, 2, 3]);
    assert.equal(images.selected, null);
    images.restore(0);
    assert.deepEqual(images.order, [0, 1, 2, 3]);
});
test('restore all respects prior order after several adjacent removals', () => {
    const images = new ImageOrder(5);
    images.discard(0);
    images.discard(2);
    images.discard(1);
    images.restoreAll();
    assert.deepEqual(images.order, [0, 1, 2, 3, 4]);
    assert.deepEqual(images.trash, []);
});
test('dragging out of trash restores directly to the chosen position', () => {
    const images = new ImageOrder(4);
    images.discard(3);
    images.insert(3, 0);
    assert.deepEqual(images.order, [3, 0, 1, 2]);
    assert.deepEqual(images.trash, []);
});
test('all images can be trashed and restored without changing original IDs', () => {
    const images = new ImageOrder(4);
    [0, 1, 2, 3].forEach(id => images.discard(id));
    assert.deepEqual(images.order, []);
    images.restoreAll();
    assert.deepEqual(images.order, [0, 1, 2, 3]);
});
test('restoring after neighbors were also removed or moved remains duplicate-free', () => {
    const images = new ImageOrder(4);
    images.discard(1);
    images.move(2, 0);
    images.restore(1);
    assert.deepEqual(images.order, [1, 2, 0, 3]);
    assert.equal(images.restore(1), false);
    assert.equal(images.discard(99), false);
    assert.equal(images.insert(99, 0), false);
    assert.equal(images.insert(0, 99), false);
    assert.equal(images.insert(0, 0), false);
    assert.equal(new Set(images.order).size, 4);
});
