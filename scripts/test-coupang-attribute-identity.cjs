'use strict';

const {chromium} = require('playwright'), assert = require('node:assert/strict');
const {server} = require('./product-ui-preview.cjs'), {fixture, rules} = require('./coupang-ui-fixture.cjs');
const W = require('../molebutter-app/src/main/resources/static/js/coupang-working-copy-model.js');
const row = (name, exposed, value) => ({name, exposed, value});

async function editor(browser, width, {mode = 'UPDATE', attributes, metadata, permissions = []}, check) {
  const context = await browser.newContext({viewport: {width, height: 1000}});
  const page = await context.newPage(), errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const {doc, source} = fixture(mode);
  doc.options[0].attributes = structuredClone(attributes);
  source.options[0].attributes = structuredClone(attributes);
  source.options[0].attributePermissions = permissions;
  const state = {document: doc, source, revision: 1, changes: [], saves: 0, prepared: null};
  const entry = () => ({mode, workingCopyId: '42', productId: mode === 'UPDATE' ? '123' : null,
    revision: state.revision, document: W.apply(state.document, state.changes), changes: state.changes,
    sessionId: '77', expiresAt: '2030-01-01T00:00:00Z', observation: mode === 'UPDATE' ? source : null,
    blocked: false, accountKey: 'a'.repeat(64)});
  await context.route('https://**/*', r => r.abort());
  await context.route('**/api/coupang/**', async r => {
    const req = r.request(), path = new URL(req.url()).pathname;
    const reply = data => r.fulfill({json: {code: 'OK', data}});
    if (path === '/api/coupang/working-copies/42' && req.method() === 'GET') return reply(entry());
    if (path === '/api/coupang/working-copies/42' && req.method() === 'PUT') {
      const body = req.postDataJSON();
      assert.equal(body.expectedRevision, state.revision);
      state.document = body.document;
      state.changes = body.changes;
      state.saves++;
      state.revision++;
      return reply(entry());
    }
    if (path.endsWith('/rules')) return reply({...rules, attributes: metadata});
    if (path.includes('/shipping-places/')) return reply({items: [], hasNext: false});
    if (path === '/api/coupang/submissions') return reply({items: [], totalPages: 0});
    if (path.endsWith('/prepare')) {
      state.prepared = req.postDataJSON();
      return reply({id: '101', workingCopyId: '42', revision: state.revision, executable: true,
        expiresAt: '2030-01-01T00:00:00Z', target: {mode, issues: [], changes: [], steps: [
          {id: 'write', type: 'UPDATE', optionId: doc.options[0].id, label: '속성 수정'}]}});
    }
    throw Error('Unexpected request: ' + req.method() + ' ' + path);
  });
  try {
    const metadataLoaded = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/rules'));
    await page.goto('http://127.0.0.1:' + server.address().port + '/coupang/working-copies/42/edit');
    await page.locator('[data-field="basic.sellerProductName"]').waitFor();
    await (await metadataLoaded).finished();
    await page.waitForFunction(() => !document.querySelector('#editor-save-working-copy').disabled);
    await check(page, state);
    assert.deepEqual(errors, []);
  } finally { await context.close(); }
}

(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  try {
    for (const width of [1440, 390]) {
      for (const editable of [null, false, true]) for (const reversed of [false, true]) {
        const attributes = [row('색상', 'NONE', '검색 색상'), row('색상', 'EXPOSED', '구매 색상')];
        const metadata = [{name: '색상', exposed: 'NONE', required: 'OPTIONAL', groupNumber: 'NONE'},
          {name: '색상', exposed: 'EXPOSED', required: 'MANDATORY', groupNumber: 'NONE'}];
        const permissions = [{name: '색상', exposed: 'NONE', editable: true}, {name: '색상', exposed: 'EXPOSED', editable}];
        if (reversed) { attributes.reverse(); metadata.reverse(); permissions.reverse(); }
        await editor(browser, width, {attributes, metadata, permissions}, async (page, state) => {
          const purchase = page.locator('#editor-option-list [data-field][aria-label="색상"]');
          const search = page.locator('#editor-search-filter-fields [data-field][aria-label="색상"]');
          await page.waitForFunction(() => document.querySelector('#editor-option-list [data-field][aria-label="색상"]').getAttribute('aria-required') === 'true');
          assert.equal(await purchase.inputValue(), '구매 색상');
          assert.equal(await search.inputValue(), '검색 색상');
          assert.equal(await purchase.isDisabled(), editable !== true);
          assert.equal(await search.isDisabled(), false);
          assert.equal(await purchase.getAttribute('aria-required'), 'true');
          assert.equal(await search.getAttribute('aria-required'), null);
          if (editable !== true) {
            await page.locator('#editor-save-working-copy').click();
            await page.waitForFunction(() => document.querySelector('#editor-validation').textContent === '임시 저장 완료');
            assert.deepEqual(state.changes, []);
            return;
          }
          await purchase.fill('화이트');
          await purchase.press('Enter');
          assert.equal(state.saves, 0);
          await page.locator('#editor-save-working-copy').focus();
          await page.keyboard.press('Space');
          await page.waitForFunction(() => document.querySelector('#editor-validation').textContent === '임시 저장 완료');
          const expected = [{path: 'options.attributes.value', optionId: state.document.options[0].id,
            value: '화이트', rowKey: ['색상', 'EXPOSED'], operation: 'SET'}];
          assert.deepEqual(state.changes, expected);
          state.document.options[0].attributes.find(a => a.exposed === 'NONE').value = '외부 최신 검색';
          state.source.options[0].attributes.find(a => a.exposed === 'NONE').value = '외부 최신 검색';
          await page.reload();
          await purchase.waitFor();
          assert.equal(await purchase.inputValue(), '화이트');
          assert.equal(await search.inputValue(), '외부 최신 검색');
          await page.locator('#coupang-save').click();
          await page.locator('#coupang-submission-dialog[open]').waitFor();
          assert.deepEqual(state.prepared.changes, expected);
          assert.equal(state.document.options[0].attributes.find(a => a.exposed === 'NONE').value, '외부 최신 검색');
          assert(!('document' in state.prepared), 'preparation uses the saved server working copy');
        });
      }
      const metadata = [{name: '용량', exposed: 'NONE', required: 'OPTIONAL', groupNumber: 'NONE'},
        {name: '중량', exposed: 'NONE', required: 'OPTIONAL', groupNumber: 'NONE'},
        {name: '용량', exposed: 'EXPOSED', required: 'MANDATORY', groupNumber: '1'},
        {name: '중량', exposed: 'EXPOSED', required: 'MANDATORY', groupNumber: '1'}];
      await editor(browser, width, {mode: 'CREATE', metadata,
        attributes: [row('용량', 'NONE', '검색 용량'), row('중량', 'NONE', '검색 중량'), row('중량', 'EXPOSED', '1g')]}, async (page, state) => {
        const group = page.locator('[data-purchase-group]');
        assert.equal(await group.inputValue(), '중량');
        await group.selectOption('용량');
        await page.locator('#editor-option-list [data-field][aria-label="용량"]').fill('2ml');
        await page.locator('#editor-save-working-copy').click();
        await page.waitForFunction(() => document.querySelector('#editor-validation').textContent === '임시 저장 완료');
        assert.deepEqual(state.document.options[0].attributes, [row('용량', 'NONE', '검색 용량'),
          row('중량', 'NONE', '검색 중량'), row('용량', 'EXPOSED', '2ml')]);
        assert(state.changes.filter(c => c.path.startsWith('options.attributes')).every(c => c.rowKey[1] === 'EXPOSED'));
      });
      await editor(browser, width, {metadata,
        attributes: [row('용량', 'NONE', '검색 용량'), row('중량', 'NONE', '검색 중량')],
        permissions: [{name: '용량', exposed: 'NONE', editable: false}]}, async (page, state) => {
        const group = page.locator('[data-purchase-group]');
        await group.waitFor();
        assert.equal(await group.isDisabled(), false, 'search rows do not lock a missing purchase group');
        await page.locator('#editor-save-working-copy').click();
        await page.waitForFunction(() => document.querySelector('#editor-validation').textContent === '임시 저장 완료');
        assert.deepEqual(state.changes, [], 'rendered purchase placeholders do not create intent');
        await group.selectOption('중량');
        await page.locator('#editor-option-list [data-field][aria-label="중량"]').fill('1g');
        await page.locator('#editor-save-working-copy').click();
        await page.waitForFunction(() => document.querySelector('#editor-validation').textContent === '임시 저장 완료');
        assert.deepEqual(state.document.options[0].attributes, [row('용량', 'NONE', '검색 용량'),
          row('중량', 'NONE', '검색 중량'), row('중량', 'EXPOSED', '1g')]);
        assert.equal(state.changes.length, 1);
        assert.deepEqual(state.changes[0].rowKey, ['중량', 'EXPOSED']);
      });
      console.log('Purchase/search identity, permissions, save/re-entry/preview and group preservation passed: ' + width);
    }
  } finally { await browser.close(); server.close(); }
})().catch(e => { console.error(e); server.close(); process.exitCode = 1; });
