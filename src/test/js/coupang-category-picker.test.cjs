"use strict";

const {
    test
  } = require('node:test'),
  assert = require('node:assert/strict'),
  fs = require('node:fs'),
  vm = require('node:vm');
function fixture() {
  const elements = new Map(),
    calls = [];
  const element = () => ({
    value: '',
    textContent: '',
    innerHTML: '',
    disabled: false,
    hidden: false,
    focus() {}
  });
  const dialog = {
    ...element(),
    open: false,
    setAttribute() {},
    querySelector(s) {
      if (!elements.has(s)) elements.set(s, element());
      return elements.get(s);
    },
    addEventListener() {},
    showModal() {
      this.open = true;
    },
    close() {
      this.open = false;
    }
  };
  const request = (url, options = {}) => new Promise((resolve, reject) => calls.push({
    url,
    options,
    resolve,
    reject
  }));
  const window = {
    addEventListener() {},
    CoupangRead: {
      start(url) {
        const controller = new AbortController();
        return {
          promise: request(url, {
            signal: controller.signal
          }),
          cancel: () => controller.abort()
        };
      }
    }
  };
  vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-category-picker.js', 'utf8'), {
    window,
    FormActions: {
      bindEnterSubmit(form, button) {
        assert.equal(form, dialog.querySelector('form'));
        assert.equal(button, dialog.querySelector('button[type=submit]'));
      }
    },
    AbortController,
    structuredClone,
    AppUI: {
      escape: v => String(v).replaceAll('<', '&lt;')
    },
    document: {
      createElement: () => dialog,
      body: {
        append() {}
      }
    },
    apiGet: request
  });
  const settle = () => new Promise(r => setImmediate(r)),
    click = dataset => dialog.querySelector('[data-coupang-category-results-container]').onclick({
      target: {
        closest: () => ({
          dataset
        })
      }
    });
  const apply = () => dialog.querySelector('[data-coupang-category-apply]').onclick(),
    submit = () => dialog.querySelector('form').onsubmit({
      preventDefault() {}
    });
  const resolve = (call, rows, paged = false) => call.resolve(paged ? {
    items: rows.map(b => ({
      brandId: b.id,
      brandName: b.name
    })),
    page: 1,
    hasNext: false
  } : rows);
  return {
    picker: window.CoupangCategoryPicker,
    dialog,
    calls,
    settle,
    click,
    apply,
    submit,
    resolve
  };
}
const cp = [{
    id: '123',
    name: '가방 > 크로스백'
  }, {
    id: '456',
    name: '가방 > 지갑'
  }];
function answer(call) { call.resolve(cp); }
test('late responses cannot replace a reopened Coupang category dialog', async () => {
  const f = fixture();
  f.picker.open({
    name: '가방',
    onApply() {}
  });
  const old = f.calls.slice();
  f.picker.close();
  f.picker.open({
    name: '123',
    onApply() {}
  });
  old.forEach(c => c.resolve(cp));
  f.calls.slice(1).forEach(answer);
  await f.settle();
  let html = f.dialog.querySelector('[data-coupang-category-results-container]').innerHTML;
  assert(html.includes('크로스백'));
  assert(!html.includes('456'));
  f.dialog.querySelector('[data-coupang-category-query]').value = '지갑';
  f.submit();
  assert.equal(f.calls.length, 2);
  await f.settle();
  assert(f.dialog.querySelector('[data-coupang-category-results-container]').innerHTML.includes('지갑'));
});
test('Coupang category choice applies a single selection and preserves cancel input',async()=>{
 const f=fixture();let applied;
 const previous={id:'456',name:'기존'};
 f.picker.open({name:'123',selection:previous,onApply:value=>applied=value});
 f.calls[0].resolve(cp);await f.settle();f.click({categoryIndex:'0'});
 assert.equal(applied,undefined);assert.deepEqual(previous,{id:'456',name:'기존'});f.apply();
 assert.deepEqual(JSON.parse(JSON.stringify(applied)),{name:'123',selection:{id:'123',name:'가방 > 크로스백'}});
});
