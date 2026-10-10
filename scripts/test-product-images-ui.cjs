// Actual Thymeleaf fixtures + local synthetic APIs. No live LFmall or marketplace calls.
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '..');
const resources = path.join(root, 'molebutter-app/src/main/resources');
const fixtureDirectory = path.join(root, 'molebutter-app/target/ui-fixtures');
const httpLan = process.env.MOLEBUTTER_UI_HTTP_LAN === 'true';
const fixtureHost = httpLan ? 'molebutter-http.test' : '127.0.0.1';
const imageSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="780" height="509"><rect width="780" height="509" fill="#f5f6f8"/><rect x="210" y="170" width="350" height="230" rx="30" fill="#64748b"/><path d="M300 170V130a85 85 0 0 1 170 0v40" fill="none" stroke="#64748b" stroke-width="22"/></svg>';
const imageDataUrl = 'data:image/svg+xml;base64,' + Buffer.from(imageSvg).toString('base64');
const zip = Buffer.from('504b0506000000000000000000000000000000000000', 'hex');
const generatedId = index => '00000000-0000-4000-8000-' + String(index).padStart(12, '0');
const storedImages = images => {
    let sizeNumber = 0, noticeNumber = 0;
    return images.map((item, index) => {
        if (!item.generatedImageId) return { folder: 'official', fileName: String(index + 1).padStart(2, '0') + '.png' };
        const notice = Number(item.generatedImageId.split('-').at(-1)) > 1000;
        const number = notice ? ++noticeNumber : ++sizeNumber;
        return { folder: 'processed', fileName: (notice ? '상품정보' : '사이즈') + (number === 1 ? '' : `_${number}`) + '.png' };
    });
};
const normalizedNoticeLabel = label => label.normalize('NFC').replace(/[^0-9A-Za-z가-힣]/g, '').toLowerCase();
const product = {
    productCode: 'TEST-BAG', brandCode: 'DAKS', productName: '합성 테스트 토트백',
    originalPrice: 498000, salePrice: 191600,
    imageUrls: Array.from({ length: 80 }, (_, i) => `/test-image/${i}.svg`),
    sizeLabel: 'FREE', sizeGuideSupported: true, sizeGuideTypeName: '토트백', sizeGuideTemplateKey: 'bag-tote',
    sizeDescription: '30 X 22 X 14(가로X세로X폭)', sizeMeasurements: { 가로: '30', 폭: '14', 높이: '22' },
    sizeDimensions: { width: '30', depth: '14', height: '22' },
    notificationFields: {
        상품코드: 'TEST-BAG', '상 품-코드': 'EXCLUDED-SPACES', ['상품코드'.normalize('NFD')]: 'EXCLUDED-NFD',
        소재: '<b>천연가죽</b> & "합성"', 'A/S 책임자': '제외 항목', 'A / S 책임자와 전화번호': '제외 항목', 빈항목: '', 제조국: '합성 제조국 ' + '전체 내용 확인 '.repeat(20),
        ...Object.fromEntries(Array.from({ length: 30 }, (_, i) => ['정보 '+i, '합성 값 '+i]))
    }
};
const layout = {
    photo: { scale: 1, centerXRatio: .5, centerYRatio: .4 },
    width: { startXRatio: .15, startYRatio: .6, endXRatio: .55, endYRatio: .6, labelXRatio: .35, labelYRatio: .6 },
    height: { startXRatio: .7, startYRatio: .2, endXRatio: .7, endYRatio: .65, labelXRatio: .7, labelYRatio: .425 },
    depth: { startXRatio: .42, startYRatio: .7, endXRatio: .6, endYRatio: .8, labelXRatio: .51, labelYRatio: .75 }
};
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://local');
    if (url.pathname === '/product-images') {
        res.setHeader('Content-Type', 'text/html');
        return res.end(fs.readFileSync(path.join(fixtureDirectory, `product-images-${url.searchParams.get('role') === 'PRODUCT' ? 'PRODUCT' : 'ADMIN'}.html`)));
    }
    if (/^\/(?:js\/(?:product-images\/)?[a-zA-Z0-9._-]+|css\/[a-zA-Z0-9._-]+)$/.test(url.pathname)) {
        const file = path.join(resources, 'static', url.pathname);
        if (fs.existsSync(file)) {
            res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : 'text/css');
            return res.end(fs.readFileSync(file));
        }
    }
    res.statusCode = 404; res.end();
});

(async () => {
    for (const role of ['ADMIN', 'PRODUCT']) assert(fs.existsSync(path.join(fixtureDirectory, `product-images-${role}.html`)), 'Generate rendered fixtures with LayoutViewTest first');
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://${fixtureHost}:${server.address().port}`;
    const browser = await chromium.launch({ headless: true, channel: 'chrome', args: httpLan ? ['--host-resolver-rules=MAP molebutter-http.test 127.0.0.1', '--no-proxy-server'] : [] });
    try {
        for (const width of [2560, 1440, 390]) {
            const context = await browser.newContext({ viewport: { width, height: 900 }, acceptDownloads: true });
            const page = await context.newPage();
            const waitMock = async predicate => {
                for (let attempt = 0; !predicate() && attempt < 150; attempt++) await page.waitForTimeout(20);
                assert(predicate(), 'mock request was not observed');
            };
            const errors = [], unexpected = [], previews = [], generations = [], noticePreviews = [], noticeGenerations = [], downloads = [], calls = [];
            let fixture = structuredClone(product), expiredLookup = true, expiredArchive = true;
            let lookedUpImageUrls = [], pendingNoticePreview = null, releaseNoticeGeneration = null, releaseArchive = null;
            let failNoticePreview = false, failNoticeGeneration = false, holdNoticeGeneration = false, holdArchive = false, browserDownloads = 0;
            page.on('download', () => { browserDownloads++; });
            let failGeneration = false, refreshCount = 0, csrfCount = 0, polls = 0, jobNumber = 0;
            let uploadMode = 'success', uploadPosts = [], uploadJobs = new Map(), releaseUpload = null;
            let lostUploadOnce = false, failUploadPollOnce = false, uploadGetCount = 0;
            page.on('pageerror', error => errors.push(error.message));
            await page.addInitScript(() => {
                window.testClipboard = '';
                Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async value => { window.testClipboard = value; } } });
            });
            await page.route('**/*', async route => {
                const request = route.request(), url = new URL(request.url()), p = url.pathname;
                if (url.origin !== base) { unexpected.push(request.url()); return route.abort(); }
                if (p.startsWith('/test-image/')) return route.fulfill({ contentType: 'image/svg+xml', body: imageSvg });
                if (!p.startsWith('/api/')) return route.continue();
                calls.push({ path: p, method: request.method() });
                const ok = data => route.fulfill({ json: { code: 'OK', data } });
                if (p === '/api/auth/csrf') { csrfCount++; return ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' }); }
                if (request.method() === 'POST') {
                    assert.equal(request.headers()['x-csrf-token'], 'synthetic-csrf');
                    if (!p.startsWith('/api/auth/')) assert.match(request.headers()['x-operation-id'], /^[0-9a-f-]{36}$/i);
                }
                if (p === '/api/auth/refresh') { refreshCount++; return ok(null); }
                if (p === '/api/notifications/summary') return ok({ unreadCount: 0, unread: 0, items: [] });
                if (p === '/api/notifications') return ok({ items: [], nextCursor: null });
                if (p === '/api/product-images/size-guide/templates') return ok([
                    { templateKey: 'bag-tote', displayName: '토트백', widthLabel: '가로', depthLabel: '폭', heightLabel: '높이', twoDimensional: false },
                    { templateKey: 'belt', displayName: '벨트', widthLabel: '총길이', depthLabel: '폭(잠금)', heightLabel: '너비', twoDimensional: true }
                ]);
                if (p.endsWith('/size-guide/preview')) {
                    const body = request.postDataJSON(); previews.push(body);
                    return ok({ displayName: '토트백', imageDataUrl, layout: body.layout || layout });
                }
                if (p.endsWith('/size-guide/images')) {
                    const body = request.postDataJSON(); generations.push(body);
                    if (failGeneration) return route.fulfill({ status: 500, json: { code: 'IMAGE_FAILED', message: '합성 생성 실패' } });
                    return ok({ id: generatedId(generations.length), displayName: '토트백', imageDataUrl });
                }
                if (p.endsWith('/notice-image/preview')) {
                    const body = request.postDataJSON(); noticePreviews.push(body);
                    assert.equal(body.brandCode, 'DAKS');
                    assert.equal(Object.keys(body.fields).some(label => /A.*S.*책임자/i.test(label)), false);
                    assert.equal(Object.keys(body.fields).some(label => normalizedNoticeLabel(label) === '상품코드'), false);
                    const entries = Object.entries(body.fields).filter(([, value]) => value.trim());
                    const cards = entries.map(([label, value], index) => ({
                        label, value,
                        fullWidth: value.length > 70 || entries[index + 1]?.[1].length > 70 || index === entries.length - 1
                    }));
                    // Synthetic decisions prove the browser consumes server layout rather than measuring text itself.
                    const first = cards.find(card => card.label === '소재');
                    if (first && ['짧지만 전체폭', '지연 응답'].includes(first.value)) first.fullWidth = true;
                    if (first && ['새 입력', '새 조회'].includes(first.value)) first.fullWidth = false;
                    if (body.fields.소재 === '지연 응답') {
                        return new Promise(resolve => { pendingNoticePreview = async () => { await ok({ cards }); resolve(); }; });
                    }
                    if (failNoticePreview) return route.fulfill({ status: 500, json: { code: 'PREVIEW_FAILED', message: '합성 미리보기 실패' } });
                    return ok({ cards });
                }
                if (p.endsWith('/notice-image/images')) {
                    const body = request.postDataJSON(); noticeGenerations.push(body);
                    assert.equal(Object.keys(body.fields).some(label => normalizedNoticeLabel(label) === '상품코드'), false);
                    if (holdNoticeGeneration) await new Promise(resolve => { releaseNoticeGeneration = resolve; });
                    if (failNoticeGeneration) return route.fulfill({ status: 500, json: { code: 'NOTICE_FAILED', message: '합성 상품정보 생성 실패' } });
                    const snapshot = Buffer.from(imageSvg.replace('#64748b', '#31527a') + '<!--' + Buffer.from(JSON.stringify(body.fields)).toString('base64') + '-->').toString('base64');
                    return ok({ id: generatedId(1000 + noticeGenerations.length), displayName: '상품정보', imageDataUrl: 'data:image/svg+xml;base64,' + snapshot });
                }
                if (p === '/api/product-images/products/upload') {
                    const body = request.postDataJSON();
                    uploadPosts.push(body);
                    assert.match(body.requestId, /^[0-9a-f-]{36}$/i);
                    assert.equal(body.productCode, 'TEST-BAG');
                    assert.equal(body.brandCode, 'DAKS');
                    for (const item of body.images) {
                        if (item.imageIndex !== undefined) assert.equal(item.sourceImageUrl, lookedUpImageUrls[item.imageIndex]);
                        else assert.deepEqual(Object.keys(item), ['generatedImageId']);
                    }
                    if (uploadMode === 'forbidden') return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: '합성 업로드 권한 없음' } });
                    if (uploadMode === 'duplicate') return route.fulfill({ status: 409, json: { code: 'IMAGING_UPLOAD_DUPLICATE', message: '이미 업로드된 상품입니다. 저장할 상품코드를 변경해주세요.' } });
                    if (uploadMode === 'check-failed') return route.fulfill({ status: 503, json: { code: 'IMAGING_UPLOAD_CHECK_FAILED', message: '기존 업로드 확인에 실패했습니다. 잠시 후 다시 시도해주세요.' } });
                    if (uploadMode === 'csrf' && !lostUploadOnce) {
                        lostUploadOnce = true;
                        return route.fulfill({ status: 403, json: { code: 'INVALID_CSRF_TOKEN', message: '합성 CSRF 만료' } });
                    }
                    if (uploadMode === 'refresh' && !lostUploadOnce) {
                        lostUploadOnce = true;
                        return route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED', message: '합성 세션 만료' } });
                    }
                    if (uploadMode === 'lost-not-recorded' && !lostUploadOnce) {
                        lostUploadOnce = true;
                        return route.abort('connectionreset');
                    }
                    const result = { uploadProductCode: body.uploadProductCode, totalCount: body.images.length, files: storedImages(body.images).map(({ folder, fileName }) => {
                        const key = `products/${body.uploadProductCode}/${folder}/${fileName}`;
                        return { fileName, key, url: 'https://assets.molebutter.link/' + key.split('/').map(encodeURIComponent).join('/') };
                    }) };
                    if (['failed', 'unknown'].includes(uploadMode)) result.files = result.files.slice(0, 1);
                    const job = { id: body.requestId, status: uploadMode === 'failed' ? 'FAILED' : uploadMode === 'unknown' ? 'UNKNOWN' : 'SUCCEEDED', result,
                        message: '합성 상태 안내', error: uploadMode === 'failed' ? '합성 부분 실패' : uploadMode === 'unknown' ? '합성 응답 유실' : null };
                    uploadJobs.set(body.requestId, job);
                    if (uploadMode === 'lost-accepted') return route.abort('connectionreset');
                    if (['hold', 'poll-error', 'poll-forbidden'].includes(uploadMode)) {
                        if (uploadMode === 'poll-error') failUploadPollOnce = true;
                        return ok({ id: body.requestId, status: 'RUNNING', result: { ...result, files: [] } });
                    }
                    return ok(job);
                }
                if (p.startsWith('/api/product-images/products/upload/jobs/')) {
                    uploadGetCount++;
                    const id = p.split('/').at(-1);
                    if (!uploadJobs.has(id)) return route.fulfill({ status: 404, json: { code: 'IMAGING_UPLOAD_JOB_NOT_FOUND', message: '합성 미기록' } });
                    if (uploadMode === 'poll-forbidden') return route.fulfill({ status: 403, json: { code: 'FORBIDDEN', message: '합성 결과 조회 권한 없음' } });
                    if (uploadMode === 'hold') await new Promise(resolve => { releaseUpload = resolve; });
                    if (failUploadPollOnce || uploadMode === 'poll-error') {
                        failUploadPollOnce = false;
                        return route.fulfill({ status: 503, json: { code: 'SYNTHETIC_POLL_FAILED', message: '합성 결과 조회 실패' } });
                    }
                    return ok(uploadJobs.get(id));
                }
                if (p === '/api/product-images/products/download') {
                    const body = request.postDataJSON();
                    assert.equal(body.productCode, 'TEST-BAG');
                    assert.match(body.downloadProductCode, /^[A-Z0-9_-]{4,40}$/);
                    for (const item of body.images) {
                        if (item.imageIndex !== undefined) assert.equal(item.sourceImageUrl, lookedUpImageUrls[item.imageIndex]);
                        else assert.deepEqual(Object.keys(item), ['generatedImageId']);
                    }
                    downloads.push(body); jobNumber++; polls = 0;
                    return ok({ id: 'job-' + jobNumber, status: 'RUNNING', result: null });
                }
                if (/\/products\/download\/jobs\/job-\d+\/archive$/.test(p)) {
                    if (expiredArchive) { expiredArchive = false; return route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED' } }); }
                    if (holdArchive) await new Promise(resolve => { releaseArchive = resolve; });
                    const code = downloads[Number(p.match(/job-(\d+)/)[1]) - 1].downloadProductCode;
                    return route.fulfill({ contentType: 'application/zip', headers: { 'Content-Length': String(zip.length), 'Content-Disposition': `attachment; filename="${code}.zip"` }, body: zip });
                }
                if (/\/products\/download\/jobs\/job-\d+$/.test(p)) {
                    polls++;
                    const body = downloads[Number(p.match(/job-(\d+)/)[1]) - 1];
                    return ok({ id: p.split('/').at(-1), status: 'SUCCEEDED', result: {
                        downloadName: body.downloadProductCode + '.zip',
                        savedFiles: storedImages(body.images).map(({ folder, fileName }) => `${body.downloadProductCode}/${folder}/${fileName}`)
                    } });
                }
                if (p === '/api/product-images/products/TEST-BAG') {
                    assert.equal(url.searchParams.get('brand'), 'DAKS');
                    if (expiredLookup) { expiredLookup = false; return route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED' } }); }
                    lookedUpImageUrls = [...fixture.imageUrls];
                    return ok(fixture);
                }
                unexpected.push(p); return route.abort();
            });
            await page.goto(base + '/product-images' + (width === 390 ? '?role=PRODUCT' : ''));
            assert.equal(await page.evaluate(() => isSecureContext), !httpLan);
            if (width === 390) {
                await page.locator('#menu-open').click();
                await page.locator('#app-sidebar').getByRole('link', { name: '상품 정보 도구', exact: true }).waitFor();
                await page.locator('#menu-close').click();
            } else assert.equal(await page.locator('#app-sidebar').isVisible(), true);
            const lookup = async () => {
                await page.locator('#productCodeInput').fill(' test-bag ');
                await page.locator('#lookupButton').click();
                await page.waitForFunction(() => !document.querySelector('#lookupButton').disabled && !document.querySelector('#sizeTemplateSelect').disabled && !document.querySelector('#resultPanel').classList.contains('hidden'));
                await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
                await page.locator('#notificationGrid .info-item.full-width').first().waitFor();
                assert.equal(await page.locator('#storageProductCodeInput').inputValue(), 'TEST-BAG');
            };
            const checkUiConventions = async () => {
                await page.mouse.move(0, 0);
                await page.waitForTimeout(200); // Let the shared hover transition finish before comparing its resting style.
                const metrics = await page.evaluate(() => {
                    const main = document.querySelector('#main-content');
                    const title = main.querySelector('h1');
                    const lookupButton = document.querySelector('#lookupButton');
                    const lookupPanel = lookupButton.closest('.panel');
                    const result = document.querySelector('#resultPanel');
                    const mainStyle = getComputedStyle(main), mainRect = main.getBoundingClientRect();
                    const rect = element => {
                        const { left, right, top, bottom, width, height } = element.getBoundingClientRect();
                        return { left, right, top, bottom, width, height };
                    };
                    const keys = ['backgroundColor', 'color', 'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth', 'borderRadius', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'fontSize', 'fontWeight', 'minHeight'];
                    const styles = element => Object.fromEntries(keys.map(key => [key, getComputedStyle(element)[key]]));
                    // Resolve the shared rules outside the feature scope, including inherited font/token values.
                    const referenceButton = document.createElement('button');
                    referenceButton.className = 'btn primary';
                    referenceButton.hidden = true;
                    const referenceInput = document.createElement('input');
                    referenceInput.type = 'text';
                    referenceInput.hidden = true;
                    const referenceToolbar = document.createElement('div');
                    referenceToolbar.className = 'toolbar';
                    referenceToolbar.hidden = true;
                    const referenceToolbarInput = document.createElement('input');
                    referenceToolbarInput.type = 'text';
                    referenceToolbar.appendChild(referenceToolbarInput);
                    document.body.append(referenceButton, referenceInput, referenceToolbar);
                    const sharedButton = styles(referenceButton), sharedInput = styles(referenceInput), sharedToolbarInput = styles(referenceToolbarInput);
                    referenceButton.remove(); referenceInput.remove(); referenceToolbar.remove();
                    const details = document.querySelector('.details-panel');
                    const sizeSection = document.querySelector('#sizeGuideRow');
                    const noticeSection = document.querySelector('.notice-section');
                    return {
                        viewport: innerWidth, documentWidth: document.documentElement.scrollWidth,
                        contentLeft: mainRect.left + parseFloat(mainStyle.paddingLeft),
                        contentWidth: main.clientWidth - parseFloat(mainStyle.paddingLeft) - parseFloat(mainStyle.paddingRight),
                        title: rect(title), lookupPanel: lookupPanel ? rect(lookupPanel) : null, result: rect(result),
                        resultIsPanel: result.classList.contains('panel'),
                        primaryButtons: ['lookupButton', 'downloadImagesButton'].map(id => {
                            const button = document.getElementById(id);
                            return { id, primary: button.matches('.btn.primary'), styles: styles(button) };
                        }),
                        inputs: ['brandSelect', 'productCodeInput', 'storageProductCodeInput', 'dimWidthInput', 'dimDepthInput', 'dimHeightInput', 'sizeLabelInput'].map(id => {
                            const input = document.getElementById(id);
                            return { id, styles: styles(input) };
                        }),
                        storageCode: rect(document.querySelector('#storageProductCodeInput')),
                        storageButtons: ['downloadImagesButton', 'uploadImagesButton'].map(id => ({ id, ...rect(document.getElementById(id)) })),
                        details: rect(details),
                        sizeControls: ['sizeLabelInput', 'dimWidthInput', 'dimDepthInput', 'dimHeightInput', 'editPhotoButton', 'sizeTemplateSelect', 'addSizeImageButton'].map(id => {
                            const control = document.getElementById(id);
                            return { id, insideDetails: details.contains(control), insideSizeSection: sizeSection.contains(control), rect: rect(control) };
                        }),
                        sizeBeforeNotice: sizeSection.getBoundingClientRect().bottom <= noticeSection.getBoundingClientRect().top,
                        noticeButtonInsideSection: noticeSection.contains(document.querySelector('#addNoticeImageButton')),
                        editingControlsInDownloadArea: document.querySelectorAll('.download-actions #sizeGuideRow, .download-actions #addNoticeImageButton').length,
                        sharedButton, sharedInput, sharedToolbarInput
                    };
                });
                const close = (actual, expected, message) => assert(Math.abs(actual - expected) <= 1, `${width}px ${message}: ${actual} != ${expected}`);
                assert(metrics.lookupPanel, `${width}px lookup controls need the shared panel`);
                assert(metrics.resultIsPanel, `${width}px result needs the shared panel`);
                close(metrics.title.left, metrics.contentLeft, 'title/page alignment');
                close(metrics.lookupPanel.left, metrics.title.left, 'lookup/title alignment');
                close(metrics.result.left, metrics.title.left, 'result/title alignment');
                close(metrics.lookupPanel.width, metrics.contentWidth, 'lookup/page width');
                close(metrics.result.width, metrics.contentWidth, 'result/page width');
                for (const button of metrics.primaryButtons) {
                    assert(button.primary, `${width}px ${button.id} needs .btn.primary`);
                    const { minHeight: actualMinHeight, ...actual } = button.styles;
                    const { minHeight: sharedMinHeight, ...expected } = metrics.sharedButton;
                    assert.deepEqual(actual, expected, `${width}px ${button.id} shared button styles`);
                }
                const inputKeys = ['backgroundColor', 'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth', 'borderRadius', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'fontSize', 'fontWeight', 'minHeight'];
                for (const input of metrics.inputs) {
                    const shared = ['brandSelect', 'productCodeInput'].includes(input.id) ? metrics.sharedToolbarInput : metrics.sharedInput;
                    for (const key of inputKeys) assert.equal(input.styles[key], shared[key], `${width}px ${input.id} shared input ${key}`);
                }
                if (width >= 1440) {
                    for (const button of metrics.storageButtons) {
                        assert(button.left >= metrics.storageCode.right,
                            `${width}px ${button.id} must be beside the shared storage code`);
                        assert(Math.min(button.bottom, metrics.storageCode.bottom) > Math.max(button.top, metrics.storageCode.top),
                            `${width}px ${button.id} and storage code must share a row: ${JSON.stringify({ input: metrics.storageCode, button })}`);
                    }
                }
                for (const control of metrics.sizeControls) {
                    assert(control.insideDetails && control.insideSizeSection, `${width}px ${control.id} must be in the sidebar size section`);
                    assert(control.rect.left >= metrics.details.left - 1 && control.rect.right <= metrics.details.right + 1,
                        `${width}px ${control.id} exceeds the sidebar: ${JSON.stringify(control.rect)}`);
                }
                assert(metrics.sizeBeforeNotice, `${width}px size controls must precede product information`);
                assert(metrics.noticeButtonInsideSection, `${width}px notice image add button must be in product information`);
                assert.equal(metrics.editingControlsInDownloadArea, 0, `${width}px editing controls must leave the download area`);
                assert(metrics.documentWidth <= metrics.viewport + 1, `${width}px horizontal overflow: ${JSON.stringify(metrics)}`);
            };
            await page.locator('#lookupButton').click();
            assert.equal(await page.locator('#lookupStatus').textContent(), '상품코드를 입력해주세요.');
            await lookup(); assert.equal(refreshCount, 1); assert.equal(csrfCount, 1);
            assert.equal(await page.locator('.gallery-hint').count(), 0);
            assert.equal(await page.locator('#downloadImagesButton').textContent(), '다운로드');
            assert.equal(await page.locator('#uploadImagesButton').textContent(), '업로드');
            await checkUiConventions();
            const galleryRows = await page.locator('#imageStrip .image-card').evaluateAll(nodes => new Set(nodes.map(node => Math.round(node.getBoundingClientRect().top))).size);
            assert(galleryRows >= 2, `${width}px initial gallery should use at least two thumbnail rows, got ${galleryRows}`);
            fs.mkdirSync(path.join(root, 'target/ui-check'), { recursive: true });
            await page.screenshot({ path: path.join(root, `target/ui-check/product-images-layout-${width}.png`), fullPage: true });
            assert.equal(await page.locator('#sizeTemplateSelect').inputValue(), 'bag-tote');
            assert.equal(await page.locator('#notificationGrid b').count(), 0);
            const checkNoticeHeight = async () => {
                const heights = await page.locator('#notificationGrid textarea').evaluateAll(nodes => nodes.map(node => ({ label: node.getAttribute('aria-label'), client: node.clientHeight, scroll: node.scrollHeight })));
                for (const item of heights) assert(item.scroll <= item.client + 1, `${width}px clipped notice text: ${JSON.stringify(item)}`);
                assert.equal(await page.locator('#notificationGrid .info-item').first().evaluate(node => getComputedStyle(node).backgroundColor === 'rgb(255, 255, 255)'), false);
                const compact = await page.locator('#notificationGrid .info-item').first().evaluate(node => ({
                    labelFont: getComputedStyle(node.querySelector('.info-label')).fontSize,
                    valueFont: getComputedStyle(node.querySelector('textarea')).fontSize,
                    padding: getComputedStyle(node).padding,
                    cardGap: getComputedStyle(node).gap,
                    gridGap: getComputedStyle(node.parentElement).gap,
                    minimumInputHeight: getComputedStyle(node.querySelector('textarea')).minHeight
                }));
                assert.deepEqual(compact, { labelFont: '10px', valueFont: '12px', padding: '6px', cardGap: '3px', gridGap: '6px', minimumInputHeight: '44px' });
            };
            await checkNoticeHeight();
            // Width changes preserve the same input and recompute its height without clipping the long value.
            await page.getByRole('textbox', { name: '소재', exact: true }).evaluate(node => { window.resizeNoticeInput = node; node.focus(); });
            await page.setViewportSize({ width: width === 390 ? 1440 : 390, height: 900 });
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await checkNoticeHeight();
            assert.equal(await page.getByRole('textbox', { name: '소재', exact: true }).evaluate(node => node === window.resizeNoticeInput && document.activeElement === node), true);
            await page.setViewportSize({ width, height: 900 });
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await checkNoticeHeight();

            await page.getByRole('button', { name: '상품명 복사', exact: true }).click();
            assert.equal(await page.evaluate(() => window.testClipboard), product.productName);
            await page.getByRole('button', { name: '판매가 복사', exact: true }).click();
            assert.equal(await page.evaluate(() => window.testClipboard), '191600');
            // Stable editable nodes, renderer layout, retained blank inputs and excluded A/S cards.
            const material = page.getByRole('textbox', { name: '소재', exact: true });
            const noticeCard = label => page.locator('#notificationGrid .info-item').filter({ has: page.getByRole('textbox', { name: label, exact: true }) });
            assert.equal(await page.locator('#includeNoticeImage, #sizeInfoGrid, #infoPageStatus, #notificationGrid button, .value-dialog').count(), 0);
            assert.equal(await page.locator('#notificationGrid textarea').count(), 33);
            const fieldLabels = await page.locator('#notificationGrid textarea').evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-label')));
            assert.equal(fieldLabels.some(label => normalizedNoticeLabel(label) === '상품코드'), false);
            assert.equal(await page.locator('#displayProductCode').textContent(), 'TEST-BAG');
            assert.equal(await page.getByRole('textbox', { name: 'A/S 책임자', exact: true }).count(), 0);
            assert.equal(await material.inputValue(), product.notificationFields.소재);
            assert.equal(await page.getByRole('textbox', { name: '빈항목', exact: true }).inputValue(), '');
            assert.equal(await page.locator('#sizeLabelInput').inputValue(), 'FREE');
            const noticeMetrics = await page.locator('#noticeStage').evaluate(node => ({ height: node.clientHeight, scrollHeight: node.scrollHeight, overflow: getComputedStyle(node).overflowY }));
            assert(noticeMetrics.height <= 384 && noticeMetrics.scrollHeight > noticeMetrics.height);
            assert.equal(noticeMetrics.overflow, 'auto');
            await material.evaluate(node => { window.testNoticeInput = node; });
            assert.equal(await noticeCard('제조국').evaluate(node => node.classList.contains('full-width')), true);
            assert.equal(await noticeCard('정보 0').evaluate(node => node.classList.contains('full-width')), false);
            await material.fill('짧지만 전체폭');
            await page.waitForFunction(() => document.querySelector('#notificationGrid textarea[aria-label="소재"]').closest('.info-item').classList.contains('full-width'));
            await page.waitForFunction(() => document.querySelector('#notificationGrid textarea[aria-label="소재"]').value === '짧지만 전체폭');
            await page.waitForTimeout(350);
            assert.equal(noticePreviews.at(-1).fields.소재, '짧지만 전체폭');
            await checkNoticeHeight();
            assert.equal(await material.evaluate(node => node === window.testNoticeInput && document.activeElement === node && node.selectionStart === node.value.length), true);
            await material.fill('');
            await page.waitForFunction(() => !document.querySelector('#notificationGrid textarea[aria-label="소재"]').closest('.info-item').classList.contains('full-width'));
            assert.equal(await material.inputValue(), ''); assert.equal(await material.isVisible(), true);
            failNoticePreview = true; await material.fill('미리보기 실패값');
            await page.waitForFunction(() => document.querySelector('#noticePreviewStatus').textContent.includes('합성 미리보기 실패'));
            assert.equal(await material.inputValue(), '미리보기 실패값');
            failNoticePreview = false;
            await material.fill('지연 응답');
            await waitMock(() => pendingNoticePreview);
            const staleEditResponse = pendingNoticePreview; pendingNoticePreview = null;
            await material.fill('새 입력');
            await page.waitForTimeout(350); assert.equal(noticePreviews.at(-1).fields.소재, '새 입력');
            await staleEditResponse(); await page.waitForTimeout(50);
            assert.equal(await noticeCard('소재').evaluate(node => node.classList.contains('full-width')), false);
            assert.equal(await material.inputValue(), '새 입력');
            // A pending response for the old product cannot mutate a newly looked-up product, even with the same code.
            await material.fill('지연 응답');
            await waitMock(() => pendingNoticePreview);
            const staleProductResponse = pendingNoticePreview; pendingNoticePreview = null;
            fixture = { ...structuredClone(product), notificationFields: { ...product.notificationFields, 소재: '새 조회' } };
            await lookup(); await staleProductResponse(); await page.waitForTimeout(50);
            assert.equal(await material.inputValue(), '새 조회');
            assert.equal(await noticeCard('소재').evaluate(node => node.classList.contains('full-width')), false);
            assert.equal(await material.evaluate(node => node === window.testNoticeInput), false);

            const card = id => page.locator(`#imageStrip [data-image-id="${id}"]`);
            const order = () => page.locator('#imageStrip .image-card').evaluateAll(nodes => nodes.map(node => Number(node.dataset.imageId)));
            const revealCard = async id => {
                if (await card(id).count()) return;
                while (!await page.locator('#previousImagePage').isDisabled()) await page.locator('#previousImagePage').click();
                while (!await card(id).count() && !await page.locator('#nextImagePage').isDisabled()) await page.locator('#nextImagePage').click();
                assert.equal(await card(id).count(), 1, `${width}px image ${id} must be available on a page`);
            };
            const drag = async (source, destination, fraction = .25) => {
                await source.scrollIntoViewIfNeeded(); await destination.scrollIntoViewIfNeeded();
                const from = await source.boundingBox(), to = await destination.boundingBox();
                await page.mouse.move(from.x + from.width/2, from.y + from.height/2); await page.mouse.down();
                await page.mouse.move(to.x + to.width*fraction, to.y + to.height/2, { steps: 15 }); await page.mouse.up();
            };
            await card(1).locator('.image-pick-button').click();
            assert.equal(await page.locator('.primary-image').getAttribute('data-image-id'), '0');
            await drag(card(1).locator('.image-pick-button'), card(0));
            assert.equal(await page.locator('.primary-image').getAttribute('data-image-id'), '1');
            await card(0).locator('.image-remove-button').click();
            assert.equal(await page.locator('#trashCount').textContent(), '1');
            await page.locator('#trashStrip [data-image-id="0"] button').click();
            assert.deepEqual((await order()).slice(0, 2), [1, 0]);
            await card(0).locator('.image-pick-button').click(); await page.keyboard.press('Escape');
            assert.equal(await page.locator('#trashCount').textContent(), '1');
            await page.locator('#restoreAllImages').click(); assert.deepEqual((await order()).slice(0, 2), [1, 0]);
            await page.locator('#nextImagePage').click(); assert.match(await page.locator('#imagePageStatus').textContent(), /^2 \/ /);
            await page.locator('#previousImagePage').click();
            // Desktop pointer capture must survive a page switch while dragging.
            if (width === 1440) {
                const from = await card(0).boundingBox(), pager = await page.locator('#nextImagePage').boundingBox();
                await page.mouse.move(from.x + from.width/2, from.y + from.height/2); await page.mouse.down();
                await page.mouse.move(pager.x + pager.width/2, pager.y + pager.height/2, { steps: 15 });
                await page.waitForFunction(() => document.querySelector('#imagePageStatus').textContent.startsWith('2 /'));
                const target = page.locator('#imageStrip .image-card').first();
                const targetId = Number(await target.getAttribute('data-image-id')), to = await target.boundingBox();
                await page.mouse.move(to.x + to.width*.75, to.y + to.height/2, { steps: 10 }); await page.mouse.up();
                assert((await order()).includes(0));
                assert.equal((await order()).indexOf(0), (await order()).indexOf(targetId)+1);
            }

            // Reset to a compact fixture to verify both generated-image modes and immutable edits.
            fixture = { ...structuredClone(product), imageUrls: product.imageUrls.slice(0, 3) };
            await lookup();
            const compactPanelHeight = await page.locator('#resultPanel').evaluate(element => element.getBoundingClientRect().height);
            await page.setViewportSize({ width, height: 1200 });
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            const tallerPanelHeight = await page.locator('#resultPanel').evaluate(element => element.getBoundingClientRect().height);
            assert(Math.abs(tallerPanelHeight - compactPanelHeight) <= 1, `${width}px result panel grows with viewport height: ${compactPanelHeight} -> ${tallerPanelHeight}`);
            await page.setViewportSize({ width, height: 900 });
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await checkUiConventions();
            await page.locator('#sizeLabelInput').fill('M (수정)');
            await revealCard(2);
            await card(2).locator('.image-pick-button').click(); await page.locator('#editPhotoButton').click();
            await page.locator('[data-sg-kind="width"][data-sg-point="end"]').waitFor();
            assert.equal(await page.locator('[data-sg-confirm]').evaluate(element => element.matches('.btn.primary')), true, `${width}px editor confirmation needs .btn.primary`);
            await page.locator('#uploadImagesButton').evaluate(button => button.click());
            assert.equal(await page.locator('#imageUploadDialog').evaluate(dialog => dialog.open), false);
            assert.equal(uploadPosts.length, 0);
            assert.equal(previews.at(-1).baseImageUrl, '/test-image/2.svg');
            assert.equal(previews.at(-1).sizeLabel, 'M (수정)');
            const stage = await page.locator('[data-sg-overlay]').boundingBox();
            const end = await page.locator('[data-sg-kind="width"][data-sg-point="end"]').boundingBox();
            await page.mouse.move(end.x+end.width/2, end.y+end.height/2); await page.mouse.down();
            await page.mouse.move(stage.x + stage.width*.57, stage.y + stage.height*.61, { steps: 8 });
            assert.equal(await page.locator('[data-sg-line="width"]').getAttribute('class'), 'sg-svg-line snapped');
            await page.mouse.up();
            assert.equal(Number(await page.locator('[data-sg-line="width"]').getAttribute('y2')), .6);
            fs.mkdirSync(path.join(root, 'target/ui-check'), { recursive: true });
            await page.screenshot({ path: path.join(root, `target/ui-check/product-images-editor-${width}.png`) });
            failGeneration = true; await page.locator('[data-sg-confirm]').click();
            await page.waitForFunction(() => document.querySelector('[data-sg-confirm]').textContent === '확인');
            assert.equal(await page.locator('#sizeGuideEditorModal').evaluate(el => el.open), true);
            assert.equal(Number(await page.locator('[data-sg-line="width"]').getAttribute('y2')), .6);
            failGeneration = false; await page.locator('[data-sg-confirm]').click();
            await page.locator('#sizeGuideEditorModal').waitFor({ state: 'hidden' });
            assert.match(await page.locator('#selectionCount').textContent(), /^4개/);
            const photoRequest = structuredClone(generations.at(-1));
            assert.equal(photoRequest.mode, 'PHOTO'); assert.equal(photoRequest.baseImageUrl, '/test-image/2.svg');
            assert.deepEqual(photoRequest.dimensions, { width: '30', depth: '14', height: '22' });
            assert.equal(photoRequest.sizeLabel, 'M (수정)');
            await page.locator('#dimWidthInput').fill('99');
            await page.locator('#sizeLabelInput').fill('L');
            await card(3).locator('.image-pick-button').click(); await page.locator('#editPhotoButton').click();
            await page.locator('[data-sg-kind="width"][data-sg-point="end"]').waitFor();
            assert.deepEqual(previews.at(-1).dimensions, photoRequest.dimensions);
            assert.deepEqual(previews.at(-1).layout, photoRequest.layout);
            assert.equal(previews.at(-1).sizeLabel, 'M (수정)');
            await page.keyboard.press('Escape'); assert.equal(await page.locator('#trashCount').textContent(), '0');
            await page.locator('#addSizeImageButton').click();
            await page.waitForFunction(() => document.querySelector('#selectionCount').textContent.startsWith('5개'));
            assert.equal(generations.at(-1).mode, 'TEMPLATE'); assert.equal(generations.at(-1).dimensions.width, '99'); assert.equal(generations.at(-1).sizeLabel, 'L');
            await card(4).locator('.image-pick-button').click(); assert.equal(await page.locator('#editPhotoButton').isDisabled(), true);
            await revealCard(0);
            await card(0).locator('.image-remove-button').click();
            await revealCard(4);
            await card(4).locator('.image-remove-button').click();
            await material.fill('상품정보 생성 당시 값');
            await page.waitForTimeout(350);
            failNoticeGeneration = true;
            await page.locator('#addNoticeImageButton').click();
            await page.waitForFunction(() => !document.querySelector('#addNoticeImageButton').disabled);
            assert.equal(await material.inputValue(), '상품정보 생성 당시 값');
            assert.equal(await card(5).count(), 0);
            failNoticeGeneration = false; holdNoticeGeneration = true;
            const beforeAddDownloads = browserDownloads;
            await page.locator('#addNoticeImageButton').click();
            await waitMock(() => releaseNoticeGeneration);
            assert.equal(await material.isDisabled(), true);
            assert.equal(await page.locator('#sizeLabelInput').isDisabled(), true);
            assert.equal(await page.locator('#downloadImagesButton').isDisabled(), true);
            releaseNoticeGeneration(); holdNoticeGeneration = false;
            await page.waitForFunction(() => !document.querySelector('#addNoticeImageButton').disabled);
            await revealCard(5);
            assert.equal(await card(5).locator('.generated-image-badge').textContent(), '상품정보');
            assert.equal(browserDownloads, beforeAddDownloads); assert.equal(downloads.length, 0);
            assert.equal(noticeGenerations.at(-1).fields.소재, '상품정보 생성 당시 값');
            const noticeSnapshot = await card(5).locator('img').getAttribute('src');
            await material.fill('이미지 추가 이후 변경값'); await page.waitForTimeout(350);
            assert.equal(await card(5).locator('img').getAttribute('src'), noticeSnapshot);
            assert.equal(noticeGenerations.at(-1).fields.소재, '상품정보 생성 당시 값');
            await card(5).locator('.image-pick-button').click(); assert.equal(await page.locator('#editPhotoButton').isDisabled(), true);
            await revealCard(1); await revealCard(5);
            await drag(card(5).locator('.image-pick-button'), card(1));
            assert.equal(await page.locator('.primary-image').getAttribute('data-image-id'), '5');
            await card(5).locator('.image-remove-button').click();
            await page.locator('#trashStrip [data-image-id="5"] button').click();
            assert.equal(await page.locator('.primary-image').getAttribute('data-image-id'), '5');
            assert.equal(await card(5).locator('img').getAttribute('src'), noticeSnapshot);
            holdArchive = true;
            await page.locator('#storageProductCodeInput').fill(' test-download ');
            const beforeDownloadEnter = downloads.length;
            await page.locator('#storageProductCodeInput').press('Enter');
            assert.equal(downloads.length, beforeDownloadEnter);
            const downloadEvent = page.waitForEvent('download'); await page.locator('#downloadImagesButton').click();
            await waitMock(() => releaseArchive);
            assert.equal(await material.isDisabled(), true);
            assert.equal(await page.locator('#sizeLabelInput').isDisabled(), true);
            assert.equal(await page.locator('#storageProductCodeInput').isDisabled(), true);
            releaseArchive(); holdArchive = false;
            const download = await downloadEvent; assert.equal(download.suggestedFilename(), 'TEST-DOWNLOAD.zip');
            await page.waitForFunction(() => !document.querySelector('#downloadImagesButton').disabled);
            assert.deepEqual(downloads.at(-1), {
                productCode: 'TEST-BAG', downloadProductCode: 'TEST-DOWNLOAD', brandCode: 'DAKS', images: [{ generatedImageId: generatedId(1002) }, { imageIndex: 1, sourceImageUrl: '/test-image/1.svg' }, { imageIndex: 2, sourceImageUrl: '/test-image/2.svg' }, { generatedImageId: generatedId(2) }], includeNoticeImage: false, includeSizeImage: false
            });
            assert.equal(polls, 1); assert.equal(refreshCount, 2); assert.equal(csrfCount, 1);
            const again = page.waitForEvent('download'); await page.locator('#downloadArchiveButton').click();
            assert.equal((await again).suggestedFilename(), 'TEST-DOWNLOAD.zip');
            assert.equal(downloads.length, 1); assert.equal(calls.some(call => call.path.includes('open-folder')), false);

            // Upload the same immutable mixed selection under a different folder, preserving source data.
            const openUpload = async folder => {
                await page.locator('#storageProductCodeInput').fill(folder);
                await page.locator('#uploadImagesButton').click();
                await page.locator('#uploadSelectedCode').waitFor();
                assert.equal(await page.locator('#uploadSelectedCode').textContent(), folder.trim().toUpperCase());
                assert.equal(await page.locator('#storageProductCodeInput').isDisabled(), true);
                await page.locator('#confirmUploadButton').click();
            };
            const waitUploadDone = () => page.waitForFunction(() => !document.querySelector('#uploadImagesButton').disabled && document.querySelector('#uploadStatus').textContent.includes('업로드 완료'));
            const beforeUploadOrder = await order();
            uploadMode = 'hold';
            await page.locator('#storageProductCodeInput').fill('../bad');
            await page.locator('#uploadImagesButton').click();
            assert.match(await page.locator('#storageProductCodeError').textContent(), /4~40자/);
            assert.equal(await page.locator('#imageUploadDialog').evaluate(dialog => dialog.open), false);
            assert.equal(uploadPosts.length, 0);
            await page.locator('#storageProductCodeInput').fill(' test-folder ');
            const postsBeforeEnter = uploadPosts.length;
            await page.locator('#storageProductCodeInput').press('Enter');
            assert.equal(uploadPosts.length, postsBeforeEnter);
            await page.locator('#uploadImagesButton').click();
            assert.equal(await page.locator('#uploadSourceCode').textContent(), 'TEST-BAG');
            assert.equal(await page.locator('#uploadImageCount').textContent(), '4');
            assert.equal(await page.locator('#uploadProductCodeInput').count(), 0);
            assert.equal(await page.locator('#uploadSelectedCode').textContent(), 'TEST-FOLDER');
            assert.equal(await page.locator('#uploadOfficialPathPreview').textContent(), 'molebutter/products/TEST-FOLDER/official/');
            assert.equal(await page.locator('#uploadProcessedPathPreview').textContent(), 'molebutter/products/TEST-FOLDER/processed/');
            await page.locator('#imageUploadForm').evaluate(form => form.requestSubmit());
            assert.equal(uploadPosts.length, postsBeforeEnter);
            const dialogMetrics = await page.locator('#imageUploadDialog').evaluate(dialog => {
                const r = dialog.getBoundingClientRect();
                return { shared: dialog.classList.contains('attendance-dialog'), left: r.left, right: r.right, width: r.width, viewport: innerWidth };
            });
            assert(dialogMetrics.shared && dialogMetrics.left >= 0 && dialogMetrics.right <= dialogMetrics.viewport + 1);
            await page.screenshot({ path: path.join(root, `target/ui-check/product-images-upload-dialog-${width}.png`) });
            // Keyboard activation of the explicitly focused button is allowed.
            await page.locator('#confirmUploadButton').press('Enter');
            await waitMock(() => releaseUpload);
            assert.equal(await page.locator('#lookupButton').isDisabled(), true);
            assert.equal(await page.locator('#storageProductCodeInput').isDisabled(), true);
            assert.equal(await page.locator('#downloadImagesButton').isDisabled(), true);
            assert.equal(await material.isDisabled(), true);
            assert.equal(await page.locator('#sizeLabelInput').isDisabled(), true);
            assert.equal(await page.locator('#addNoticeImageButton').isDisabled(), true);
            assert.equal(await page.locator('#addSizeImageButton').isDisabled(), true);
            assert.equal(await page.locator('#imageStrip .image-pick-button:not(:disabled), #imageStrip .image-remove-button:not(:disabled)').count(), 0);
            await page.locator('#uploadImagesButton').evaluate(button => button.click());
            await page.locator('#imageUploadForm').evaluate(form => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
            assert.equal(uploadPosts.length, 1);
            assert.deepEqual(uploadPosts[0].images, downloads[0].images);
            assert.equal(uploadPosts[0].productCode, 'TEST-BAG');
            assert.equal(uploadPosts[0].uploadProductCode, 'TEST-FOLDER');
            releaseUpload(); releaseUpload = null; uploadMode = 'success';
            await waitUploadDone();
            assert.equal(await page.locator('#displayProductCode').textContent(), 'TEST-BAG');
            assert.equal(await page.locator('#sizeLabelInput').inputValue(), 'L');
            assert.equal(await material.inputValue(), '이미지 추가 이후 변경값');
            assert.deepEqual(await order(), beforeUploadOrder);
            assert.equal(await page.locator('#uploadFiles a').count(), 4);
            assert.match(await page.locator('#uploadFiles a').first().getAttribute('href'), /products\/TEST-FOLDER\//);
            assert.deepEqual(await page.locator('#uploadFiles a').allTextContents(), ['상품정보.png', '02.png', '03.png', '사이즈.png']);
            const noticeUrl = new URL(await page.locator('#uploadFiles a').first().getAttribute('href'));
            assert.equal(decodeURIComponent(noticeUrl.pathname), '/products/TEST-FOLDER/processed/상품정보.png');
            assert.equal(await page.evaluate(() => sessionStorage.getItem('product-images.pending-upload')), null);

            // A duplicate folder keeps its immutable code and selected images until the dialog is cancelled.
            uploadMode = 'duplicate';
            const beforeDuplicate = uploadPosts.length;
            await openUpload('TEST-FOLDER');
            await page.waitForFunction(() => document.querySelector('#imageUploadDialog').open && !document.querySelector('#uploadDialogError').hidden);
            assert.equal(uploadPosts.length, beforeDuplicate + 1);
            assert.match(await page.locator('#uploadDialogError').textContent(), /이미 업로드된 상품/);
            assert.equal(await page.locator('#storageProductCodeInput').inputValue(), 'TEST-FOLDER');
            assert.equal(await page.locator('#storageProductCodeInput').isDisabled(), true);
            assert.equal(await page.locator('#uploadSelectedCode').textContent(), 'TEST-FOLDER');
            assert.equal(await page.locator('#uploadProcessedPathPreview').textContent(), 'molebutter/products/TEST-FOLDER/processed/');
            assert.equal(await page.evaluate(() => sessionStorage.getItem('product-images.pending-upload')), null);
            assert.equal(await material.inputValue(), '이미지 추가 이후 변경값');
            assert.equal(await page.locator('#sizeLabelInput').inputValue(), 'L');
            assert.deepEqual(await order(), beforeUploadOrder);
            uploadMode = 'success';
            await page.locator('#cancelUploadButton').click();
            await openUpload('OTHER-FOLDER');
            await waitUploadDone();
            assert.equal(uploadPosts.length, beforeDuplicate + 2);
            assert.notEqual(uploadPosts.at(-1).requestId, uploadPosts.at(-2).requestId);
            assert.equal(uploadPosts.at(-1).uploadProductCode, 'OTHER-FOLDER');
            assert.deepEqual(uploadPosts.at(-1).images, uploadPosts.at(-2).images);
            assert.equal(decodeURIComponent(new URL(await page.locator('#uploadFiles a').first().getAttribute('href')).pathname), '/products/OTHER-FOLDER/processed/상품정보.png');
            assert.deepEqual(await order(), beforeUploadOrder);

            // A known preflight outage accepts no write and must not enter UNKNOWN or automatically repeat POST.
            uploadMode = 'check-failed';
            const beforeCheckFailure = uploadPosts.length, beforeCheckFailureGets = uploadGetCount;
            await openUpload('CHECK-FOLDER');
            await page.waitForFunction(() => document.querySelector('#imageUploadDialog').open && !document.querySelector('#uploadDialogError').hidden);
            assert.match(await page.locator('#uploadDialogError').textContent(), /기존 업로드 확인에 실패/);
            assert.equal(await page.locator('#storageProductCodeInput').inputValue(), 'CHECK-FOLDER');
            assert.equal(await page.locator('#storageProductCodeInput').isDisabled(), true);
            assert.equal(await page.locator('#uploadSelectedCode').textContent(), 'CHECK-FOLDER');
            assert.equal(await page.locator('#uploadSourceCode').textContent(), 'TEST-BAG');
            assert.equal(await page.locator('#uploadImageCount').textContent(), '4');
            assert.equal(await page.evaluate(() => sessionStorage.getItem('product-images.pending-upload')), null);
            assert.equal(await page.locator('#checkUploadButton').isVisible(), false);
            assert.equal(await material.inputValue(), '이미지 추가 이후 변경값');
            assert.equal(await page.locator('#sizeLabelInput').inputValue(), 'L');
            assert.deepEqual(await order(), beforeUploadOrder);
            await page.waitForTimeout(250);
            assert.equal(uploadPosts.length, beforeCheckFailure + 1);
            assert.equal(uploadGetCount, beforeCheckFailureGets);
            uploadMode = 'success';
            await page.locator('#confirmUploadButton').click();
            await waitUploadDone();
            assert.equal(uploadPosts.length, beforeCheckFailure + 2);
            assert.notEqual(uploadPosts.at(-1).requestId, uploadPosts.at(-2).requestId);
            assert.equal(uploadPosts.at(-1).uploadProductCode, 'CHECK-FOLDER');
            assert.deepEqual(uploadPosts.at(-1).images, uploadPosts.at(-2).images);
            assert.deepEqual(await order(), beforeUploadOrder);

            uploadMode = 'lost-accepted';
            const beforeLost = uploadPosts.length, beforeLostGets = uploadGetCount;
            await openUpload('ACCEPTED'); await waitUploadDone();
            assert.equal(uploadPosts.length, beforeLost + 1);
            assert(uploadGetCount > beforeLostGets);

            uploadMode = 'lost-not-recorded'; lostUploadOnce = false;
            await openUpload('NO-RECORD');
            await page.waitForFunction(() => document.querySelector('#checkUploadButton').textContent === '같은 요청으로 다시 시도');
            const lostRequest = structuredClone(uploadPosts.at(-1));
            const beforeSameRetry = uploadPosts.length;
            await page.locator('#checkUploadButton').click(); await waitUploadDone();
            assert.equal(uploadPosts.length, beforeSameRetry + 1);
            assert.deepEqual(uploadPosts.at(-1), lostRequest);

            uploadMode = 'poll-error';
            await openUpload('POLL-ERROR');
            await page.waitForFunction(() => !document.querySelector('#checkUploadButton').disabled && document.querySelector('#uploadStatus').textContent.includes('합성 결과 조회 실패'));
            const beforePollRetry = uploadPosts.length;
            assert.equal(await page.locator('#lookupButton').isDisabled(), true);
            assert.deepEqual(await order(), beforeUploadOrder);
            uploadMode = 'success';
            await page.locator('#checkUploadButton').click(); await waitUploadDone();
            assert.equal(uploadPosts.length, beforePollRetry);

            uploadMode = 'poll-forbidden';
            await openUpload('POLL-PERMISSION');
            await page.waitForFunction(() => !document.querySelector('#checkUploadButton').disabled && document.querySelector('#uploadStatus').textContent.includes('합성 결과 조회 권한 없음'));
            const beforePermissionRetry = uploadPosts.length;
            const acceptedPermissionId = uploadPosts.at(-1).requestId;
            assert.equal(JSON.parse(await page.evaluate(() => sessionStorage.getItem('product-images.pending-upload'))).request.requestId, acceptedPermissionId);
            assert.equal(await page.locator('#lookupButton').isDisabled(), true);
            uploadMode = 'success';
            await page.locator('#checkUploadButton').click(); await waitUploadDone();
            assert.equal(uploadPosts.length, beforePermissionRetry);
            assert.equal(await page.evaluate(() => sessionStorage.getItem('product-images.pending-upload')), null);

            uploadMode = 'forbidden';
            await openUpload('FORBIDDEN');
            await page.waitForFunction(() => document.querySelector('#uploadStatus').textContent === '합성 업로드 권한 없음' && !document.querySelector('#lookupButton').disabled);
            assert.deepEqual(await order(), beforeUploadOrder);
            assert.equal(await material.inputValue(), '이미지 추가 이후 변경값');

            for (const mode of ['csrf', 'refresh']) {
                uploadMode = mode; lostUploadOnce = false;
                const beforeAuth = uploadPosts.length;
                await openUpload(mode.toUpperCase()); await waitUploadDone();
                assert.equal(uploadPosts.length, beforeAuth + 2);
                assert.deepEqual(uploadPosts.at(-1), uploadPosts.at(-2));
            }
            assert.equal(csrfCount, 2);
            assert.equal(refreshCount, 3);
            uploadMode = 'failed';
            await openUpload('PARTIAL');
            await page.waitForFunction(() => document.querySelector('#uploadStatus').textContent.includes('합성 부분 실패') && !document.querySelector('#lookupButton').disabled);
            assert.match(await page.locator('#uploadStatus').textContent(), /1\/4개 저장.*같은 상품코드로 다시 업로드할 수 없습니다/);
            assert.equal(await page.locator('#uploadFiles a').count(), 1);
            assert.deepEqual(await order(), beforeUploadOrder);
            await page.locator('#restoreAllImages').click();
            // Restore-generated items keep their snapshot IDs; an empty selection produces no request.
            while (await page.locator('#imageStrip .image-remove-button').count()) await page.locator('#imageStrip .image-remove-button').first().click();
            const beforeEmpty = downloads.length; await page.locator('#downloadImagesButton').click();
            assert.match(await page.locator('#downloadStatus').textContent(), /다운로드할 사진이 없습니다/); assert.equal(downloads.length, beforeEmpty);
            await page.locator('#restoreAllImages').click();
            const retryAfterError = page.waitForEvent('download');
            await page.locator('#downloadArchiveButton').click();
            await retryAfterError;
            assert.match(await page.locator('#downloadStatus').textContent(), /파일 준비 완료: TEST-DOWNLOAD.zip/);
            // An uncertain write exposes partial links and only checks the existing ID, including after reload.
            uploadMode = 'unknown';
            await openUpload('UNCERTAIN');
            await page.waitForFunction(() => document.querySelector('#uploadStatus').textContent.includes('합성 응답 유실') && !document.querySelector('#lookupButton').disabled);
            const beforeUnknown = uploadPosts.length, unknownId = uploadPosts.at(-1).requestId;
            assert.equal(await page.locator('#uploadFiles a').count(), 1);
            await page.locator('#uploadImagesButton').click();
            await page.waitForFunction(() => !document.querySelector('#checkUploadButton').disabled);
            assert.equal(uploadPosts.length, beforeUnknown);
            assert.equal(await page.locator('#imageUploadDialog').evaluate(dialog => dialog.open), false);
            await page.reload();
            await page.waitForFunction(() => document.querySelector('#uploadStatus').textContent.includes('합성 응답 유실') && !document.querySelector('#checkUploadButton').disabled);
            assert.equal(uploadPosts.length, beforeUnknown);
            assert.equal(JSON.parse(await page.evaluate(() => sessionStorage.getItem('product-images.pending-upload'))).request.requestId, unknownId);
            await lookup();
            const dimensions = await page.evaluate(() => ({ viewport: innerWidth, page: document.documentElement.scrollWidth }));
            assert(dimensions.page <= dimensions.viewport+1, 'horizontal overflow: '+JSON.stringify(dimensions));
            assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
            await page.locator('h1').click();
            fs.mkdirSync(path.join(root, 'target/ui-check'), { recursive: true });
            await page.screenshot({ path: path.join(root, `target/ui-check/product-images-${width}.png`), fullPage: true });
            console.log(`PASS product information tool ${width}px: shared UI/mobile modal, mixed snapshots/order/trash, shared storage code, official/processed archive and public paths, immutable upload confirmation/Enter blocking, duplicate folder rejection/cancel/new code, preflight outage/manual retry, upload locks/duplicate click, stable-ID lost-response/reload recovery, partial failure/UNKNOWN links, auth refresh/CSRF`);
            await context.close();
        }
    } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; server.close(); });
