'use strict';

const {test} = require('node:test'), assert = require('node:assert/strict');
const M = require('../../../molebutter-app/src/main/resources/static/js/coupang-editor-model.js');
const W = require('../../../molebutter-app/src/main/resources/static/js/coupang-working-copy-model.js');
const {fixture, rules} = require('../../../scripts/coupang-ui-fixture.cjs');
const row = (name, exposed, value = '') => ({name, exposed, value});
const rule = (name, exposed, required = 'MANDATORY', groupNumber = 'NONE', dataType = 'STRING') =>
  ({name, exposed, required, groupNumber, dataType});

test('fixed color tone choices and conservative title inference match the server cases',()=>{
  const data=require('../../../molebutter-coupang/src/test/resources/coupang-color-tone-cases.json');
  assert.deepEqual(M.colorToneChoices,data.choices);
  for(const c of data.cases)assert.equal(M.suggestColorTone(...c.titles),c.expected,JSON.stringify(c));
  assert(M.colorToneAttribute(row('패션 의류/잡화 색상계열','NONE')));
  assert(!M.colorToneAttribute(row('패션 의류/잡화 색상계열','EXPOSED')));
});

test('missing search filter inputs appear without inventing purchase rows or save intent',()=>{
  for(const mode of ['CREATE','UPDATE']){
    const {doc,source}=fixture(mode);doc.options[0].attributes=[row('색상','EXPOSED','브라운')];source.options[0].attributes=structuredClone(doc.options[0].attributes);
    const draft=W.inflate({document:doc,observation:mode==='UPDATE'?source:null});
    const metadata={...rules,attributes:[rule('색상','NONE','OPTIONAL'),rule('소재','NONE','OPTIONAL'),rule('미선택 구매옵션','EXPOSED','OPTIONAL')]};
    M.addSearchFields(draft,metadata);M.addSearchFields(draft,metadata);
    assert.equal(draft.options[0].attributes.length,3);assert.equal(draft.options[0].attributes[0].value,'브라운');
    assert.deepEqual(W.flatten(draft),doc);assert.deepEqual(W.changes(doc,W.flatten(draft)),[]);
    const search=draft.options[0].attributes.find(a=>a.exposed==='NONE'&&a.name==='색상');search.value='브라운계열';
    const changes=W.changes(doc,W.flatten(draft));assert.equal(changes.length,1);assert.deepEqual(changes[0].rowKey,['색상','NONE']);
    search.value='';assert.deepEqual(W.changes(doc,W.flatten(draft)),[]);
  }
});

test('metadata fills distinct purchase/search rows in either order without replacing existing inputs', () => {
  for (const reversed of [false, true]) {
    const {native} = fixture('CREATE'), option = native.options[0];
    option.attributes = [row('색상', 'NONE', '검색 입력')];
    const attributes = [rule('색상', 'NONE'), rule('색상', 'EXPOSED')];
    if (reversed) attributes.reverse();
    const metadata = {...rules, attributes};
    M.applyRules(native, metadata);
    M.applyRules(native, metadata);
    assert.deepEqual(option.attributes, [row('색상', 'NONE', '검색 입력'), row('색상', 'EXPOSED')]);
  }
});

test('search rows cannot expose optional purchase columns or select a purchase group choice', () => {
  const option = {attributes: [row('용량', 'NONE', '검색값'), row('중량', 'EXPOSED', '1g')]};
  assert(!M.purchaseColumns({attributes: [rule('용량', 'EXPOSED', 'OPTIONAL')]}, [option]).some(c => c.name === '용량'));
  const column = M.purchaseColumns({attributes: [rule('용량', 'EXPOSED', 'MANDATORY', '1'), rule('중량', 'EXPOSED', 'MANDATORY', '1')]}, [option])[0];
  assert.equal(M.purchaseAttribute(option, column).name, '중량');
  const {native} = fixture('CREATE');
  native.options[0].attributes = [row('용량', 'NONE', '검색값')];
  M.applyRules(native, {...rules, attributes: column.choices});
  assert(native.options[0].attributes.some(a => a.name === '용량' && a.exposed === 'EXPOSED'));
});

test('purchase edit permission and original-row presence ignore same-name search rows', () => {
  const option = {
    attributes: [row('색상', 'NONE', '검색'), row('색상', 'EXPOSED', '구매')],
    originalAttributes: [row('색상', 'NONE', '검색'), row('색상', 'EXPOSED', '구매')],
    attributePermissions: [{name: '색상', exposed: 'NONE', editable: true}, {name: '색상', exposed: 'EXPOSED', editable: false}]
  };
  for (const editable of [null, false, true]) {
    option.attributePermissions[1].editable = editable;
    assert.equal(M.canEditPurchase(option, '색상', true), editable === true);
  }
  option.attributePermissions = [{name: '색상', editable: true}];
  assert.equal(M.canEditPurchase(option, '색상', true), false, 'old permission without exposure must not grant editing');
  option.originalAttributes = [row('색상', 'NONE', '검색')];
  option.attributePermissions = [{name: '색상', exposed: 'NONE', editable: false}];
  assert.equal(M.canEditPurchase(option, '색상', true), true, 'a missing purchase row can still be added');
});

test('validation uses each row exposure for requiredness, data type and error location', () => {
  const {native} = fixture('CREATE');
  const option = native.options[0];
  option.attributes = [row('용량', 'NONE', '검색 문자열'), row('용량', 'EXPOSED', '2ml')];
  const metadata = {...rules, attributes: [rule('용량', 'NONE'), rule('용량', 'EXPOSED', 'MANDATORY', 'NONE', 'NUMBER')]};
  assert.deepEqual(M.validate(native, metadata).filter(e => e.path.includes('.attributes.')), []);
  option.attributes[1].value = '';
  assert(M.validate(native, metadata).some(e => e.path === 'options.0.attributes.1.value' && e.message.includes('입력')));
  option.attributes[1].value = '잘못된 숫자';
  assert(M.validate(native, metadata).some(e => e.path === 'options.0.attributes.1.value' && e.message.includes('숫자')));
});

test('purchase-only intent restores over fresh same-name search values without resending them', () => {
  const {doc} = fixture('UPDATE');
  doc.options[0].attributes = [row('색상', 'NONE', '검색'), row('색상', 'EXPOSED', '블랙')];
  const next = structuredClone(doc);
  next.options[0].attributes[1].value = '화이트';
  const changes = W.changes(doc, next);
  assert.deepEqual(changes, [{path: 'options.attributes.value', optionId: doc.options[0].id, value: '화이트', rowKey: ['색상', 'EXPOSED'], operation: 'SET'}]);
  const fresh = structuredClone(doc);
  fresh.options[0].attributes[0].value = '최신 검색값';
  const restored = W.apply(fresh, changes);
  assert.deepEqual(restored.options[0].attributes, [row('색상', 'NONE', '최신 검색값'), row('색상', 'EXPOSED', '화이트')]);
  assert.deepEqual(W.changes(fresh, restored, changes), changes);
});
