'use strict';
const C=require('../molebutter-app/src/main/resources/static/js/coupang-editor-model.js'),fs=require('node:fs');

const {chromium} = require('playwright'), assert = require('node:assert/strict');
const {server} = require('./product-ui-preview.cjs'), {fixture, rules} = require('./coupang-ui-fixture.cjs');
const W = require('../molebutter-app/src/main/resources/static/js/coupang-working-copy-model.js');
const row = (name, exposed, value) => ({name, exposed, value});

async function editor(browser, width, {mode = 'UPDATE', attributes, metadata, permissions = [], failRules = false}, check) {
  const context = await browser.newContext({viewport: {width, height: 1000}});
  const page = await context.newPage(), errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const {doc, source} = fixture(mode);
  doc.options[0].attributes = structuredClone(attributes);
  source.options[0].attributes = structuredClone(attributes);
  source.options[0].attributePermissions = permissions;
  const state = {document: doc, source, revision: 1, changes: [], saves: 0, prepared: null, failRules, acceptConfirm: true};
  page.on('dialog',d=>state.acceptConfirm?d.accept():d.dismiss());
  const entry = () => ({mode, workingCopyId: '42', productId: mode === 'UPDATE' ? '123' : null,
    revision: state.revision, document: W.apply(state.document, state.changes), changes: state.changes,
    sessionId: '77', expiresAt: '2030-01-01T00:00:00Z', observation: mode === 'UPDATE' ? source : null,
    blocked: false, accountKey: 'a'.repeat(64), categoryRules:mode==='CREATE'&&!state.failRules?{...rules,checkedAt:new Date().toISOString(),attributes:metadata}:null});
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
    if (path.endsWith('/rules')) {if(state.failRules)return r.fulfill({status:503,json:{message:'모의 규격 조회 실패'}});return reply({...rules, attributes: metadata});}
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
    const metadataLoaded = mode==='UPDATE' ? page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/rules')) : null;
    await page.goto('http://127.0.0.1:' + server.address().port + '/coupang/working-copies/42/edit');
    await page.locator('[data-field="basic.sellerProductName"]').waitFor();
    if(metadataLoaded)await (await metadataLoaded).finished();
    else await page.locator('#editor-category-status').filter({hasText:failRules?'만료':'조회 완료'}).waitFor();
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
      const toneName='패션 의류/잡화 색상계열',toneMetadata=[{name:toneName,exposed:'NONE',required:'OPTIONAL',groupNumber:'NONE'},{name:'소재',exposed:'NONE',required:'OPTIONAL',groupNumber:'NONE'}];
      for(const mode of ['CREATE','UPDATE'])await editor(browser,width,{mode,metadata:toneMetadata,attributes:[row('색상','EXPOSED','구매 브라운')]},async(page,state)=>{
        const select=page.locator('#editor-search-filter-fields select[aria-label="'+toneName+'"]'),button=page.locator('[data-color-tone-path]');
        assert.equal(await select.count(),1);assert.deepEqual(await select.locator('option').allTextContents(),['선택',...C.colorToneChoices]);assert.equal(await select.inputValue(),'');
        await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent==='임시 저장 완료');assert.deepEqual(state.changes,[]);assert.equal(state.document.options[0].attributes.length,1);
        await page.locator('[data-field="basic.displayProductName"]').fill('닥스 진한 브라운 중지갑');assert.equal(await select.inputValue(),'');await button.click();assert.equal(await select.inputValue(),'브라운 톤');
        await select.selectOption('핑크 톤');await page.locator('[data-field="basic.displayProductName"]').fill('닥스 블랙 지갑');assert.equal(await select.inputValue(),'핑크 톤');state.acceptConfirm=false;await button.click();assert.equal(await select.inputValue(),'핑크 톤');state.acceptConfirm=true;await button.click();assert.equal(await select.inputValue(),'블랙 톤');
        await select.selectOption('');await page.locator('[data-field="basic.displayProductName"]').fill('블랙 화이트 지갑');await button.click();assert.equal(await select.inputValue(),'');assert.match(await page.locator('[id^="color-tone-status-"]').textContent(),/직접 선택/);
        await select.selectOption('브라운 톤');await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent==='임시 저장 완료');assert(state.changes.filter(c=>c.path.startsWith('options.attributes')).every(c=>c.rowKey[1]==='NONE'));assert.equal(state.document.options[0].attributes.filter(a=>a.exposed==='EXPOSED')[0].value,'구매 브라운');assert(!state.document.options[0].attributes.some(a=>a.name==='소재'));
        await page.reload();await select.waitFor();assert.equal(await select.inputValue(),'브라운 톤');await page.locator('#editor-search-filters').scrollIntoViewIfNeeded();fs.mkdirSync('target/ui-check',{recursive:true});await page.screenshot({path:'target/ui-check/coupang-color-tone-'+mode+'-'+width+'.png'});
      });
      await editor(browser,width,{metadata:toneMetadata,attributes:[row(toneName,'NONE','기존 계열')]},async(page,state)=>{
        const select=page.locator('#editor-search-filter-fields select');assert.equal(await select.inputValue(),'기존 계열');await page.locator('#editor-save-working-copy').click();await page.waitForFunction(()=>document.querySelector('#editor-validation').textContent==='임시 저장 완료');assert.deepEqual(state.changes,[]);
      });
      await editor(browser,width,{metadata:toneMetadata,attributes:[],failRules:true},async(page,state)=>{
        await page.locator('#editor-search-filter-status').filter({hasText:'확인하지 못했습니다'}).waitFor();state.failRules=false;await page.locator('#editor-search-filter-retry').click();await page.locator('#editor-search-filter-fields select').waitFor();assert.equal(await page.locator('#editor-search-filter-fields select').inputValue(),'');
      });
      await editor(browser,width,{metadata:[],attributes:[]},async page=>{assert.match(await page.locator('#editor-search-filter-status').textContent(),/API에서 제공하는 검색필터가 없습니다/);});

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
