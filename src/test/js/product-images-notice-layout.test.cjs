const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

const source = readFileSync('molebutter-app/src/main/resources/static/js/product-images/app.js', 'utf8');
const layoutSource = source.slice(source.indexOf('function applyNoticeLayout('), source.indexOf("\naddNoticeImageButton.addEventListener"));

function fixture(fields, sendJson = async () => ({ cards: [] })) {
    const document = { activeElement: null };
    const page = { parentElement: null, scrollTop: 600, scrollLeft: 3 };
    const stage = { parentElement: page, scrollTop: 120, scrollLeft: 2 };
    const moves = [], resized = [];
    const items = Object.entries(fields).map(([label, value]) => {
        const classes = new Set();
        const input = {
            value, selectionStart: 0, selectionEnd: 0, selectionDirection: 'none', focusCalls: 0,
            focus(options) { assert.equal(options.preventScroll, true); this.focusCalls++; document.activeElement = this; },
            setSelectionRange(start, end, direction) { this.selectionStart = start; this.selectionEnd = end; this.selectionDirection = direction; }
        };
        return {
            dataset: { noticeLabel: label }, input, parentElement: null,
            classList: { toggle(name, enabled) { enabled ? classes.add(name) : classes.delete(name); }, contains(name) { return classes.has(name); } },
            querySelector(selector) { assert.equal(selector, 'textarea'); return input; }
        };
    });
    const grid = {
        children: [...items], parentElement: stage,
        querySelectorAll(selector) { assert.equal(selector, '.info-item'); return [...this.children]; },
        contains(node) { return this.children.some(item => item === node || item.input === node); },
        insertBefore(item, before) {
            moves.push(item.dataset.noticeLabel);
            if (document.activeElement === item.input) document.activeElement = null;
            this.children.splice(this.children.indexOf(item), 1);
            this.children.splice(before ? this.children.indexOf(before) : this.children.length, 0, item);
            stage.scrollTop = 0; stage.scrollLeft = 0; page.scrollTop = 0; page.scrollLeft = 0;
        }
    };
    items.forEach(item => { item.parentElement = grid; });
    const product = { productCode: 'TEST' }, status = { textContent: '' };
    const context = vm.createContext({ document, notificationGrid: grid, noticeFields: new Map(Object.entries(fields)),
        resizeNoticeInput: input => resized.push(input), currentProduct: product, noticePreviewSequence: 1,
        sendJson, noticeRequest: () => ({ fields }), noticePreviewStatus: status });
    vm.runInContext(layoutSource, context);
    return { context, product, status, grid, items, moves, resized, document, stage, page,
        order: () => grid.children.map(item => item.dataset.noticeLabel),
        apply: cards => context.applyNoticeLayout(cards), refresh: sequence => context.refreshNoticeLayout(product, sequence) };
}

test('notice editor follows renderer card order, retains exact existing inputs and places omitted blank fields last', () => {
    const f = fixture({ 소재: '가죽', 빈항목: '', 치수: '10 x 20', 제조국: '한국', 종류: '가방', '제조사(공식수입/병행수입)': 'LF' });
    const cards = [
        { label: '소재', fullWidth: false }, { label: '치수', fullWidth: false },
        { label: '제조사(공식수입/병행수입)', fullWidth: false }, { label: '종류', fullWidth: false },
        { label: '제조국', fullWidth: true }
    ];
    f.apply(cards);
    assert.deepEqual(f.order(), ['소재', '치수', '제조사(공식수입/병행수입)', '종류', '제조국', '빈항목']);
    for (const item of f.items) assert.equal(f.grid.children.find(node => node.dataset.noticeLabel === item.dataset.noticeLabel), item);
    assert.equal(f.items.find(item => item.dataset.noticeLabel === '빈항목').input.value, '');
    assert.equal(f.items.find(item => item.dataset.noticeLabel === '제조국').classList.contains('full-width'), true);
    assert.equal(f.resized.length, f.items.length);
    const moved = f.moves.length;
    f.apply(cards);
    assert.equal(f.moves.length, moved, 'identical server order must not move any nodes');
});

test('moving an actively edited card preserves focus, selection direction, edited value and ancestor scrolling', () => {
    const f = fixture({ 제조국: '한국', 종류: '가방', 제조사: 'LF', 소재: '가죽' });
    const input = f.items.find(item => item.dataset.noticeLabel === '제조사').input;
    input.value = '편집 중인 제조사'; input.setSelectionRange(2, 6, 'backward'); f.document.activeElement = input;
    f.apply([{ label: '제조사', fullWidth: false }, { label: '종류', fullWidth: false }, { label: '소재', fullWidth: true }, { label: '제조국', fullWidth: true }]);
    assert.equal(f.document.activeElement, input); assert.equal(input.focusCalls, 1);
    assert.deepEqual([input.selectionStart, input.selectionEnd, input.selectionDirection], [2, 6, 'backward']);
    assert.equal(input.value, '편집 중인 제조사');
    assert.deepEqual([f.stage.scrollTop, f.stage.scrollLeft, f.page.scrollTop, f.page.scrollLeft], [120, 2, 600, 3]);
    f.apply([{ label: '제조사' }, { label: '종류' }, { label: '소재' }, { label: '제조국' }]);
    assert.equal(input.focusCalls, 1, 'unchanged layout must not refocus the input');
});

test('empty layouts retain editable fields and duplicate or unknown card labels cannot drop or duplicate nodes', () => {
    const f = fixture({ 소재: '', 제조사: '', 종류: '' });
    f.apply([{ label: '종류' }, { label: '알 수 없는 항목' }, { label: '종류' }]);
    assert.deepEqual(f.order(), ['종류', '소재', '제조사']);
    f.apply([]);
    assert.deepEqual(f.order(), ['소재', '제조사', '종류']);
    assert.equal(f.grid.children.length, 3);
});

test('stale edit or product preview responses cannot reorder the current inputs', async () => {
    const pending = [];
    const f = fixture({ 제조국: '한국', 제조사: 'LF' }, () => new Promise(resolve => pending.push(resolve)));
    const staleEdit = f.refresh(1); f.context.noticePreviewSequence = 2;
    pending.shift()({ cards: [{ label: '제조사' }, { label: '제조국' }] }); await staleEdit;
    assert.deepEqual(f.order(), ['제조국', '제조사']);
    const staleProduct = f.refresh(2); f.context.currentProduct = { productCode: 'TEST' };
    pending.shift()({ cards: [{ label: '제조사' }, { label: '제조국' }] }); await staleProduct;
    assert.equal(f.moves.length, 0);
});

test('preview failure retains input order, values and the active selection', async () => {
    const f = fixture({ 소재: '편집값', 제조사: 'LF' }, async () => { throw new Error('미리보기 실패'); });
    const input = f.items[0].input; f.document.activeElement = input; input.setSelectionRange(1, 2, 'forward');
    await f.refresh(1);
    assert.deepEqual(f.order(), ['소재', '제조사']); assert.equal(input.value, '편집값');
    assert.equal(f.document.activeElement, input); assert.equal(input.selectionStart, 1); assert.equal(input.selectionEnd, 2);
    assert.equal(f.status.textContent, '미리보기 실패'); assert.equal(f.moves.length, 0);
});
