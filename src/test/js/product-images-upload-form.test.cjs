const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { environment } = require('./helpers/form-dom.cjs');

const directory = 'molebutter-app/src/main/resources/';
const html = readFileSync(directory + 'templates/product-images.html', 'utf8');
const moduleSource = name => readFileSync(directory + 'static/js/product-images/' + name, 'utf8')
    .replace(/^import[^\n]+\n/gm, '').replace(/^export /gm, '');
const requestId = '11111111-1111-4111-8111-111111111111';
const storageKey = 'product-images.pending-upload';
const product = { productCode: 'HIHO6F861W2', brandCode: 'HAZZYS', imageUrls: ['https://example.test/source.jpg'], notificationFields: {} };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const plain = value => JSON.parse(JSON.stringify(value));

function fixture({ send, pending } = {}) {
    const f = environment(html), requests = [], archives = [], stored = new Map();
    if (pending) stored.set(storageKey, JSON.stringify(pending));
    // The shared event fixture omits layout; provide only the APIs used by this controller.
    const element = Object.getPrototypeOf(f.$('storageProductCodeInput'));
    element.appendChild = function (child) { this.append(child); };
    element.contains = function (node) { return node === this || this.children.some(child => child.contains(node)); };
    Object.defineProperty(element, 'style', { get() { return this._style ??= { setProperty() {} }; } });
    Object.assign(f.context, {
        ResizeObserver: class { observe() {} }, AppUI: { uuid: () => requestId },
        showToast() {}, sleep: () => Promise.resolve(), copyText: async () => {},
        formatWon: value => String(value ?? '-'), escapeAttribute: value => String(value ?? ''), escapeHtml: value => String(value ?? ''),
        enableImageDragging: () => ({ cancel() {} }), openSizeGuideEditor() {},
        downloadZip: async (url, name) => { archives.push({ url, name }); },
        sessionStorage: { getItem: key => stored.get(key) || null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) },
        sendJson: async (url, method = 'GET', body) => {
            requests.push({ url, method, body: body && plain(body) });
            const custom = send?.(url, method, body);
            if (custom !== undefined) return custom;
            if (url === '/api/product-images/size-guide/templates') return [];
            if (url.endsWith('/notice-image/preview')) return { cards: [] };
            if (method === 'GET' && !url.includes('/jobs/')) return structuredClone(product);
            if (url.endsWith('/download')) return { id: 'download-id', status: 'SUCCEEDED', result: {
                downloadName: body.downloadProductCode + '.zip', savedFiles: [body.downloadProductCode + '/official/01.jpg']
            } };
            return { id: requestId, status: 'SUCCEEDED', result: { uploadProductCode: body?.uploadProductCode, files: [] } };
        }
    });
    f.run(moduleSource('image-order.js')); f.run(moduleSource('upload-dialog.js')); f.run(moduleSource('app.js'));
    const lookup = async (code = product.productCode) => {
        f.$('productCodeInput').value = code; await f.$('lookupButton').click(); await f.settle();
    };
    const posts = suffix => requests.filter(request => request.method === 'POST' && request.url.endsWith('/' + suffix));
    return { ...f, requests, archives, stored, lookup, posts };
}

test('storage codes normalize ASCII names and reject path fragments, Unicode and invalid lengths', () => {
    const f = fixture();
    assert.equal(f.context.normalizeStorageProductCode(' hiho861w2 '), 'HIHO861W2');
    assert.equal(f.context.normalizeStorageProductCode('a_0-'), 'A_0-');
    assert.equal(f.context.normalizeStorageProductCode('a'.repeat(40)), 'A'.repeat(40));
    for (const code of ['', 'abc', 'a'.repeat(41), '../bad', 'code/name', '한글상품', 'abßc', 'ab c']) {
        assert.throws(() => f.context.normalizeStorageProductCode(code), /4~40자/);
    }
});

test('lookup success initializes the shared storage code; lookup failure preserves the chosen value', async () => {
    let fail = false;
    const f = fixture({ send: (url, method) => {
        if (method === 'GET' && url.includes('/products/') && !url.includes('/jobs/')) {
            if (fail) throw Error('lookup failed');
            return { ...product, productCode: url.includes('SECOND') ? 'SECOND' : product.productCode };
        }
    } });
    await f.lookup();
    assert.equal(f.$('storageProductCodeInput').value, product.productCode);
    assert.equal(f.$('storageProductCodeInput').form, null);
    f.$('storageProductCodeInput').value = 'CUSTOM'; fail = true; await f.lookup('MISSING');
    assert.equal(f.$('storageProductCodeInput').value, 'CUSTOM');
    fail = false; await f.lookup('SECOND'); assert.equal(f.$('storageProductCodeInput').value, 'SECOND');
});

test('download snapshots the shared code and original source, blocks Enter writes and locks the input until completion', async () => {
    const operation = deferred();
    const f = fixture({ send: url => url.endsWith('/download') ? operation.promise : undefined });
    await f.lookup();
    const code = f.$('storageProductCodeInput'); code.value = ' hiho861w2 ';
    f.key(code); await f.settle(); assert.equal(f.posts('download').length, 0); assert.equal(f.posts('upload').length, 0);
    await f.$('downloadImagesButton').click();
    assert.equal(code.disabled, true); assert.equal(code.value, 'HIHO861W2'); code.value = 'MUTATED';
    assert.deepEqual(f.posts('download')[0].body, {
        productCode: product.productCode, downloadProductCode: 'HIHO861W2', brandCode: 'HAZZYS',
        images: [{ imageIndex: 0, sourceImageUrl: product.imageUrls[0] }], includeNoticeImage: false, includeSizeImage: false
    });
    operation.resolve({ id: 'download-id', status: 'SUCCEEDED', result: {
        downloadName: 'HIHO861W2.zip', savedFiles: ['HIHO861W2/official/01.jpg']
    } }); await f.settle();
    assert.equal(code.disabled, false);
    assert.deepEqual(f.archives, [{ url: '/api/product-images/products/download/jobs/download-id/archive', name: 'HIHO861W2.zip' }]);
    assert.equal(f.$('displayProductCode').textContent, product.productCode);
});

test('invalid shared code prevents download and upload without changing the selected images', async () => {
    const f = fixture(); await f.lookup(); f.$('storageProductCodeInput').value = '../bad';
    await f.$('downloadImagesButton').click(); await f.$('uploadImagesButton').click();
    assert.equal(f.posts('download').length, 0); assert.equal(f.posts('upload').length, 0);
    assert.equal(f.$('imageUploadDialog').open, false); assert.match(f.$('storageProductCodeError').textContent, /4~40자/);
    assert.equal(f.$('storageProductCodeInput').value, '../bad'); assert.equal(f.$('selectionCount').textContent, '1개 다운로드');
});

test('upload confirmation shows the immutable shared code and both classified paths without a second input', async () => {
    const f = fixture(); await f.lookup();
    const code = f.$('storageProductCodeInput'); code.value = 'hiho861w2'; await f.$('uploadImagesButton').click();
    assert.equal(f.$('uploadProductCodeInput'), null); assert.equal(f.$('uploadSelectedCode').textContent, 'HIHO861W2');
    assert.equal(f.$('uploadOfficialPathPreview').textContent, 'molebutter/products/HIHO861W2/official/');
    assert.equal(f.$('uploadProcessedPathPreview').textContent, 'molebutter/products/HIHO861W2/processed/');
    assert.equal(code.disabled, true); code.value = 'MUTATED';
    f.key(code); await f.$('imageUploadForm').fire('submit'); assert.equal(f.posts('upload').length, 0);
    const enter = await f.$('confirmUploadButton').fire('keydown', { key: 'Enter' });
    assert.equal(enter.defaultPrevented, undefined); assert.equal(f.posts('upload').length, 0);
    const space = await f.$('confirmUploadButton').fire('keydown', { key: ' ' });
    assert.equal(space.defaultPrevented, undefined);
    await f.$('confirmUploadButton').click(); assert.equal(f.posts('upload').length, 1);
    assert.equal(f.posts('upload')[0].body.uploadProductCode, 'HIHO861W2');
    assert.equal(f.posts('upload')[0].body.productCode, product.productCode);
    assert.equal(f.posts('upload')[0].body.requestId, requestId); assert.equal(code.disabled, false);
});

for (const failure of [
    { status: 409, code: 'IMAGING_UPLOAD_DUPLICATE', message: 'duplicate folder' },
    { status: 503, code: 'IMAGING_UPLOAD_CHECK_FAILED', message: 'preflight failed' }
]) test(`${failure.code} preserves the code and selection; changing folders requires cancel and a new snapshot`, async () => {
    let reject = true;
    const f = fixture({ send: url => {
        if (url.endsWith('/upload') && reject) throw Object.assign(Error(failure.message), failure);
    } });
    await f.lookup(); const code = f.$('storageProductCodeInput'); code.value = 'HIHO861W2';
    await f.$('uploadImagesButton').click(); await f.$('confirmUploadButton').click();
    assert.equal(f.$('imageUploadDialog').open, true); assert.equal(code.value, 'HIHO861W2'); assert.equal(code.disabled, true);
    assert.equal(f.$('uploadSelectedCode').textContent, 'HIHO861W2'); assert.equal(f.$('uploadDialogError').textContent, failure.message);
    assert.equal(f.$('selectionCount').textContent, '1개 다운로드'); assert.equal(f.stored.has(storageKey), false);
    f.key(code); await f.$('imageUploadForm').fire('submit'); await f.settle(); assert.equal(f.posts('upload').length, 1);
    assert.equal(f.requests.filter(request => request.url.includes('/upload/jobs/')).length, 0);
    await f.$('cancelUploadButton').click(); assert.equal(code.disabled, false); code.value = 'OTHER-CODE'; reject = false;
    await f.$('uploadImagesButton').click(); await f.$('confirmUploadButton').click();
    assert.equal(f.posts('upload')[1].body.uploadProductCode, 'OTHER-CODE');
    assert.deepEqual(f.posts('upload')[1].body.images, f.posts('upload')[0].body.images);
});

test('restored uncertain upload only queries the existing request and preserves its frozen code for explicit retry', async () => {
    const pending = { status: 'RUNNING', request: {
        requestId, productCode: product.productCode, uploadProductCode: 'FROZEN-CODE', brandCode: product.brandCode,
        images: [{ imageIndex: 0, sourceImageUrl: product.imageUrls[0] }]
    } };
    const f = fixture({ pending, send: url => {
        if (url.includes('/upload/jobs/')) throw Object.assign(Error('not found'), { status: 404, code: 'IMAGING_UPLOAD_JOB_NOT_FOUND' });
    } }); await f.settle();
    assert.equal(f.posts('upload').length, 0); assert.equal(f.requests[0].url, '/api/product-images/products/upload/jobs/' + requestId);
    f.$('storageProductCodeInput').value = 'UNRELATED'; await f.$('checkUploadButton').click();
    assert.equal(f.posts('upload').length, 1); assert.deepEqual(f.posts('upload')[0].body, pending.request);
    assert.equal(f.stored.has(storageKey), false);
});
