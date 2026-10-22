"use strict";

const {
    test
  } = require('node:test'),
  assert = require('node:assert/strict');
const M = require('../../../molebutter-app/src/main/resources/static/js/coupang-editor-model.js');
test('creation rules use a matching category and a ten minute server observation window', () => {
  const now=Date.parse('2026-10-21T01:00:00Z'),snapshot={categoryCode:'123',checkedAt:'2026-10-21T01:00:00Z',attributes:[],notices:[],certifications:[],documents:[]};
  assert(M.currentRules(snapshot,'123',now));assert(M.currentRules(snapshot,'123',now+599999));
  assert(!M.currentRules(snapshot,'123',now+600000));assert(!M.currentRules(snapshot,'456',now));
  assert(!M.currentRules({...snapshot,checkedAt:null},'123',now));assert(!M.currentRules(snapshot,'123',now-1));
  assert(!M.currentRules({...snapshot,notices:null},'123',now));
});
const rules = () => ({
  categoryCode: '123',
  allowSingleItem: true,
  attributes: [{
    name: '색상',
    exposed: 'EXPOSED',
    required: 'MANDATORY',
    groupNumber: 'NONE',
    dataType: 'STRING'
  }, {
    name: 'Manufacturer Part Number',
    exposed: 'NONE',
    required: 'OPTIONAL',
    groupNumber: 'NONE',
    dataType: 'STRING'
  }],
  notices: [{
    name: '패션잡화',
    fields: [{
      name: '소재',
      required: 'MANDATORY'
    }]
  }],
  certifications: [],
  documents: []
});
function valid() {
  const d = M.fresh(),
    r = rules();
  Object.assign(d.basic, {
    sellerProductName: '상품',
    brand: '합성 브랜드',
    displayCategoryCode: '123'
  });
  for (const k of M.deliveryKeys) d.delivery[k] = M.spec[k][1] === 'number' ? '0' : M.spec[k][1] === 'select' ? M.spec[k][4][0] : M.spec[k][1] === 'id' ? '123' : '주소';
  d.settings = {
    brandId: 'KR-TEST',
    saleStartedAt: '2026-10-05T10:00:00',
    saleEndedAt: '2026-10-06T10:00:00'
  };
  const o = d.options[0];
  o.itemName = 'FREE 블랙';
  for (const k of M.optionKeys) if (M.spec[k][3]) o.registration[k] = M.spec[k][1] === 'number' ? '0' : M.spec[k][1] === 'select' ? M.spec[k][4][0] : '값';
  o.registration.unitCount = '1';
  o.images = [{
    type: 'REPRESENTATION',
    url: 'https://image.example.test/product.jpg'
  }];
  o.contents = [{
    type: 'HTML',
    detailType: 'TEXT',
    content: '<p>설명</p>'
  }];
  M.applyRules(d, r);
  o.attributes[0].value = '블랙';
  o.notices[0].content = '가죽';
  return {
    d,
    r
  };
}
test('new draft has one option and no implicit external identities', () => {
  const d = M.fresh();
  assert.equal(d.options.length, 1);
  assert.equal(d.basic.sellerProductId, undefined);
  assert.equal(d.options[0].vendorItemId, null);
  assert.equal(d.limits.purchaseAttributesReadOnly, false);
});
test('valid inputs produce no write request, zero price and stock are valid', () => {
  const {
    d,
    r
  } = valid();
  assert.deepEqual(M.validate(d, r), []);
  d.options[0].current = {
    salePrice: 0,
    amountInStock: 0,
    onSale: false
  };
  d.options[0].currentChanges = {
    salePrice: '0',
    amountInStock: '0'
  };
  assert.deepEqual(M.validate(d, r), []);
});
test('rules keep unknown and existing values and do not treat search as optional', () => {
  const {
    d,
    r
  } = valid();
  d.options[0].attributes.push({
    name: '미래 속성',
    value: '보존',
    exposed: 'UNKNOWN'
  });
  r.attributes[1].required = 'MANDATORY';
  M.applyRules(d, r);
  assert.equal(d.options[0].attributes[0].value, '블랙');
  assert.equal(d.options[0].attributes.at(-1).value, '보존');
  assert(M.validate(d, r).some(e => e.message.includes('Manufacturer Part Number')));
  d.options[0].attributes[1].value = 'SKU';
  assert.deepEqual(M.validate(d, r), []);
});
test('stale metadata cannot validate a different category', () => {
  const {
    d,
    r
  } = valid();
  d.basic.displayCategoryCode = '456';
  assert(M.validate(d, r).some(e => e.path === 'basic.displayCategoryCode'));
});
test('grouped required attributes require exactly one value', () => {
  const {
    d,
    r
  } = valid();
  r.attributes.push({
    name: '그룹A',
    exposed: 'EXPOSED',
    required: 'MANDATORY',
    groupNumber: '1',
    dataType: 'STRING'
  }, {
    name: '그룹B',
    exposed: 'EXPOSED',
    required: 'MANDATORY',
    groupNumber: '1',
    dataType: 'STRING'
  });
  M.applyRules(d, r);
  assert(M.validate(d, r).some(e => e.message.includes('그룹')));
  d.options[0].attributes[2].value = 'A';
  assert.deepEqual(M.validate(d, r), []);
  d.options[0].attributes.push({
    name: '그룹B',
    value: 'B',
    exposed: 'EXPOSED'
  });
  assert(M.validate(d, r).some(e => e.message.includes('그룹')));
});
test('invalid dates, unsafe numbers, duplicates and unsafe image URLs are blocked', () => {
  const {
    d,
    r
  } = valid();
  d.settings.saleStartedAt = '2026-02-30T10:00:00';
  d.options[0].registration.salePrice = '9007199254740992';
  d.options.push(structuredClone(d.options[0]));
  d.options[0].images[0] = {
    type: 'REPRESENTATION',
    url: 'blob:local'
  };
  const e = M.validate(d, r);
  assert(e.some(e => e.path === 'settings.saleStartedAt'));
  assert(e.some(e => e.path.endsWith('.salePrice')));
  assert(e.some(e => e.message.includes('중복')));
  assert(e.some(e => e.message.includes('HTTPS')));
});
test('current unknown never becomes zero and proposals need confirmed current values', () => {
  const {
    d,
    r
  } = valid();
  d.options[0].separateCurrentChanges = true;
  delete d.options[0].registration.salePrice;
  d.options[0].currentChanges = {
    amountInStock: '3'
  };
  assert(M.validate(d, r, 'edit').some(e => e.message.includes('현재 값')));
  assert.equal(d.options[0].current, null);
});
test('HTTPS validation and preview retain sandbox CSP restrictions', () => {
  assert.equal(M.url('http://bad.test/x'), null);
  assert.equal(M.url('https://user:pass@bad.test/x'), null);
  assert.equal(M.descriptionUrl('vendor_inventory/test.jpg'), 'https://img1a.coupangcdn.com/image/vendor_inventory/test.jpg');
  const html = M.preview('<img src="http://img1a.coupangcdn.com/image/test.jpg"><script>bad()</script>');
  assert(html.includes('https://img1a.coupangcdn.com/image/test.jpg'));
  for (const rule of ["script-src 'none'", "connect-src 'none'", "form-action 'none'", "base-uri 'none'"]) assert(html.includes(rule));
});
test('conditional document requirements follow option settings and retain alternate paths', () => {
  const {
    d,
    r
  } = valid();
  d.options[0].registration.overseasPurchased = 'NOT_OVERSEAS_PURCHASED';
  r.documents = [{
    name: '해외 서류',
    required: 'MANDATORY_OVERSEAS_PURCHASED'
  }];
  assert.deepEqual(M.validate(d, r), []);
  d.options[0].registration.overseasPurchased = 'OVERSEAS_PURCHASED';
  M.applyRules(d, r);
  assert(M.validate(d, r).some(e => e.path.startsWith('documents.')));
  d.documents[0].vendorPath = 'https://document.example.test/file';
  assert.deepEqual(M.validate(d, r), []);
});

// Exercise the real renderer with synthetic data. The harness has no network or browser storage.

test('new products require selected brand while imported edits preserve missing legacy IDs', () => {
  const {
    d,
    r
  } = valid();
  delete d.settings.brandId;
  assert(M.validate(d, r, 'new').some(e => e.path === 'basic.brand'));
  assert(!M.validate(d, r, 'edit').some(e => e.path === 'basic.brand'));
});
test('shared edits preserve untouched differences and match reordered fields by identity', () => {
  const a = M.blankOption(),
    b = M.blankOption();
  a.registration.searchTags = 'first';
  b.registration.searchTags = 'second';
  a.attributes = [{
    name: 'material',
    value: 'leather',
    exposed: 'NONE'
  }, {
    name: 'Manufacturer Part Number',
    value: 'A',
    exposed: 'NONE'
  }];
  b.attributes = [{
    name: 'Manufacturer Part Number',
    value: 'B',
    exposed: 'NONE'
  }, {
    name: 'material',
    value: 'canvas',
    exposed: 'NONE'
  }];
  a.notices = [{
    category: 'bags',
    name: 'material',
    content: 'leather'
  }];
  b.notices = [{
    category: 'bags',
    name: 'origin',
    content: 'KR'
  }, {
    category: 'bags',
    name: 'material',
    content: 'canvas'
  }];
  a.images = [{
    url: 'https://example.test/a.png'
  }];
  b.images = [{
    url: 'https://example.test/b.png'
  }];
  const d = {
      options: [a, b]
    },
    before = structuredClone(d);
  assert.deepEqual(M.sharedValues(d, 'options.0.registration.searchTags'), ['first', 'second']);
  assert.deepEqual(d, before);
  M.setSharedValue(d, 'options.0.registration.searchTags', 'shared');
  assert.equal(b.registration.searchTags, 'shared');
  M.setSharedValue(d, 'options.0.attributes.0.value', 'shared material');
  assert.equal(b.attributes[1].value, 'shared material');
  assert.equal(b.attributes[0].value, 'B');
  M.setSharedValue(d, 'options.0.notices.0.content', 'shared notice');
  assert.equal(b.notices[1].content, 'shared notice');
  assert.equal(b.notices[0].content, 'KR');
  assert.deepEqual(d.options.map(o => o.images), before.options.map(o => o.images));
  a.attributes.push({
    name: 'Global Trade Item Number',
    value: 'GTIN-A',
    exposed: 'NONE'
  });
  b.attributes.push({
    name: 'Global Trade Item Number',
    value: 'GTIN-B',
    exposed: 'NONE'
  });
  M.setSharedValue(d, 'options.0.attributes.' + (a.attributes.length - 1) + '.value', 'GTIN-CHANGED');
  assert.equal(b.attributes.at(-1).value, 'GTIN-B');
  const added = M.inheritShared(d, M.blankOption());
  assert(!added.attributes.some(a => a.name === 'Global Trade Item Number'));
  assert.equal(added.registration.searchTags, 'shared');
  assert(!added.attributes.some(a => a.name === 'Manufacturer Part Number'));
  assert.deepEqual(added.images, []);
});
test('image drag reorders and preserves each image identity', () => {
  const a = {
      type: 'REPRESENTATION',
      url: 'https://example.test/a',
      order: 0
    },
    b = {
      type: 'DETAIL',
      url: 'https://example.test/b',
      id: 'second-image',
      order: 1
    },
    c = {
      type: 'DETAIL',
      url: 'https://example.test/c',
      order: 2
    },
    images = [a, b, c];
  assert(M.moveImage(images, 2, 'DETAIL', 1));
  assert.deepEqual(images, [a, c, b]);
  assert.deepEqual(images.map(i => i.order), [0, 1, 2]);
  assert(M.moveImage(images, 2, 'REPRESENTATION'));
  assert.equal(images[0], b);
  assert.equal(a.type, 'DETAIL');
  assert.equal(b.type, 'REPRESENTATION');
  assert.equal(images[0].id, 'second-image');
  assert(M.moveImage(images, 0, 'DETAIL'));
  assert(!images.some(i => i.type === 'REPRESENTATION'));
  assert.equal(M.nextImageType(images), 'REPRESENTATION');
});
test('image limits apply per type and rejected moves leave data unchanged', () => {
  const images = [{
      type: 'REPRESENTATION'
    }, ...Array.from({
      length: 9
    }, () => ({
      type: 'DETAIL'
    })), ...Array.from({
      length: 4
    }, () => ({
      type: 'USED_PRODUCT'
    }))],
    before = structuredClone(images);
  assert.equal(M.nextImageType(images), null);
  assert.equal(M.moveImage(images, 0, 'DETAIL'), false);
  assert.deepEqual(images, before);
  assert(M.moveImage(images, 4, 'REPRESENTATION'));
  assert.equal(images.filter(i => i.type === 'DETAIL').length, 9);
  assert.equal(images.filter(i => i.type === 'USED_PRODUCT').length, 4);
  assert.equal(M.moveImage(images, 1, 'USED_PRODUCT'), false);
  assert.equal(M.moveImage(images, 0, 'UNKNOWN'), false);
});


test('registration names and category rebuild preserve compatible values and identify removals', () => {
  const d = M.fresh(),
    a = d.options[0];
  a.attributes = [{
    name: '사이즈',
    value: 'FREE',
    exposed: 'EXPOSED'
  }, {
    name: '색상',
    value: '브라운',
    exposed: 'EXPOSED'
  }, {
    name: '제외 속성',
    value: '입력',
    exposed: 'NONE'
  }];
  a.noticeCategory = '가방';
  a.notices = [{
    category: '가방',
    name: '소재',
    content: '가죽'
  }, {
    category: '기타',
    name: '품명',
    content: '제외'
  }];
  a.certifications = [{
    type: 'OLD',
    code: '123'
  }];
  d.documents = [{
    templateName: 'OLD',
    path: 'https://example.test/file'
  }];
  assert.equal(M.registrationOptionName(a), '브라운 FREE');
  const id = a.id;
  assert.notEqual(M.blankOption().id, id);
  const r = {
    ...rules(),
    notices: [{
      name: '가방',
      fields: [{
        name: '소재',
        required: 'MANDATORY'
      }]
    }]
  };
  const removed = M.categoryRemovals(d, r);
  assert(removed.includes('제외 속성'));
  assert(removed.includes('인증 OLD'));
  assert(removed.includes('OLD'));
  M.replaceRules(d, r);
  assert.equal(a.id, id);
  assert.equal(a.attributes[0].value, '브라운');
  assert.equal(a.notices[0].content, '가죽');
  assert.equal(a.certifications.length, 0);
  assert.equal(d.documents.length, 0);
  assert.equal(a.itemName, '브라운');
});


// Date generation is explicit and uses the coupang's Korean local time.
test('long sale period uses current Korean time and the requested 2099 endpoint', () => {
  const period = M.longSalePeriod(new Date('2026-10-13T06:10:26Z'));
  assert.equal(period.saleStartedAt, '2026-10-13T15:10:26');
  assert.equal(period.saleEndedAt, '2099-12-31T23:59:00');
});


test('sale dates validate wall-clock components without browser timezone normalization', () => {
  for (const value of ['2026-10-19T22:51:37', '2099-12-31T00:00', '2099-12-31T23:59:59', '2024-02-29T12:00:00']) assert(M.validDateTime(value), value);
  for (const value of ['', 'not-a-date', '2026-02-29T12:00:00', '2026-10-19T24:00', '2026-10-19T12:60', '2100-01-01T00:00']) assert(!M.validDateTime(value), value);
});
test('metadata fill preserves an existing notice type absent from current rules', () => {
  const d = M.fresh();d.options[0].noticeCategory='기존 유형';d.options[0].notices=[{category:'기존 유형',name:'소재',content:'보존'}];
  M.applyRules(d,rules());assert.equal(d.options[0].noticeCategory,'기존 유형');assert.deepEqual(d.options[0].notices,[{category:'기존 유형',name:'소재',content:'보존'}]);
});
