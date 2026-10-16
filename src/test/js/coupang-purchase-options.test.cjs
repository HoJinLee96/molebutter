"use strict";

const {
    test
  } = require('node:test'),
  assert = require('node:assert/strict');
const M = require('../../../molebutter-app/src/main/resources/static/js/coupang-editor-model.js');
const attr = (name, required = 'MANDATORY', groupNumber = 'NONE', exposed = 'EXPOSED') => ({
  name,
  required,
  groupNumber,
  exposed
});
test('purchase columns prioritize mandatory color and size, omit identities and unused optional/search fields', () => {
  const rules = {
    attributes: [attr('사이즈'), attr('용량'), attr('색상'), attr('향', 'OPTIONAL'), attr('모델', 'OPTIONAL'), attr('검색', 'MANDATORY', 'NONE', 'NONE'), attr('Manufacturer Part Number')]
  };
  assert.deepEqual(M.purchaseColumns(rules, [{
    attributes: [{
      name: '향',
      value: '무향',
      exposed: 'EXPOSED'
    }]
  }]).map(r => r.name), ['색상', '사이즈', '용량', '향']);
  assert.deepEqual(M.purchaseColumns({
    attributes: []
  }, []), []);
});
test('group columns choose the actual attribute instead of splitting existing itemName', () => {
  const rules = {
    attributes: [attr('용량', 'MANDATORY', '1'), attr('중량', 'MANDATORY', '1')]
  };
  const o = {
    itemName: 'FREE 브라운',
    attributes: [{
      name: '중량',
      value: '0g',
      exposed: 'EXPOSED'
    }]
  };
  const columns = M.purchaseColumns(rules, [o]);
  assert.equal(columns.length, 1);
  assert.equal(M.purchaseAttribute(o, columns[0]).name, '중량');
  assert.equal(M.registrationOptionName(o), '0g');
});
test('existing value editing requires explicit trusted true, but missing attributes can be added', () => {
  const o = {
    attributes: [{
      name: '색상',
      exposed: 'EXPOSED',
      value: ''
    }],
    originalAttributes: [{
      name: '색상',
      exposed: 'EXPOSED',
      value: ''
    }],
    attributePermissions: [{
      name: '색상',
      exposed: 'EXPOSED',
      editable: null
    }]
  };
  assert.equal(M.canEditPurchase(o, '색상', true), false);
  o.attributePermissions[0].editable = false;
  assert.equal(M.canEditPurchase(o, '색상', true), false);
  o.attributePermissions[0].editable = true;
  assert.equal(M.canEditPurchase(o, '색상', true), true);
  assert.equal(M.canEditPurchase(o, '사이즈', true), true);
  assert.equal(M.canEditPurchase(o, '색상', false), true);
});
test('existing optional attributes stay visible even when their stored value is empty', () => {
  const rules = {
    attributes: [attr('선택항목', 'OPTIONAL')]
  };
  assert.equal(M.purchaseColumns(rules, [{
    attributes: [{
      name: '선택항목',
      value: '',
      exposed: 'EXPOSED'
    }]
  }]).length, 0);
  assert.equal(M.purchaseColumns(rules, [{
    attributes: [{
      name: '선택항목',
      value: '',
      exposed: 'EXPOSED'
    }],
    originalAttributes: [{
      name: '선택항목',
      exposed: 'EXPOSED',
      value: ''
    }]
  }]).length, 1);
  assert.equal(M.purchaseColumns(rules, [{
    attributes: [{
      name: '선택항목',
      value: '',
      exposed: 'EXPOSED'
    }],
    attributePermissions: [{
      name: '선택항목',
      exposed: 'EXPOSED',
      editable: null
    }]
  }]).length, 1);
});
