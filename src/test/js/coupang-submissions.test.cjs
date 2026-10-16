"use strict";

const {
    test
  } = require('node:test'),
  assert = require('node:assert/strict');
const S = require('../../../molebutter-app/src/main/resources/static/js/coupang-submissions.js');
const execution = (status, ...states) => ({
  status,
  target: {
    steps: states.map(status => ({
      status
    }))
  }
});
test('standalone option changes use observed option name and readable price/stock labels', () => {
  const draft = {
    options: [{
      id: 'internal-option-id',
      itemName: '브라운 FREE'
    }]
  };
  assert.equal(S.changeLabel('옵션.internal-option-id.options.price', draft), '옵션 브라운 FREE · 판매가');
  assert.equal(S.changeLabel('옵션.internal-option-id.options.quantity', draft), '옵션 브라운 FREE · 재고');
});
test('ambiguous registration cannot offer retry; reconciliation remains available', () => {
  const e = execution('UNKNOWN', 'UNKNOWN');
  assert.equal(S.retryable(e), false);
  assert.equal(S.reconcilable(e), true);
  assert.equal(S.retryable(execution('PARTIAL', 'SUCCEEDED', 'FAILED', 'UNKNOWN')), false);
});
test('failed independent operations can retry after completion without repeating successful work', () => {
  assert.equal(S.retryable(execution('PARTIAL', 'SUCCEEDED', 'FAILED')), true);
  assert.equal(S.retryable(execution('RUNNING', 'FAILED', 'QUEUED')), false);
  assert.equal(S.retryable(execution('SUCCEEDED', 'SUCCEEDED')), false);
  assert.equal(S.retryable({
    status: 'FAILED',
    target: {
      steps: [{
        status: 'FAILED',
        code: 'BASELINE_CHANGED'
      }]
    }
  }), false);
  assert.equal(S.retryable({
    ...execution('PARTIAL', 'SUCCEEDED', 'FAILED'),
    revised: true
  }), false);
});
test('approval acceptance stays distinct from success and only offers result verification', () => {
  const e = execution('ACCEPTED', 'ACCEPTED');
  assert.equal(S.active(e), false);
  assert.equal(S.reconcilable(e), true);
  assert.equal(S.retryable(e), false);
  assert.notEqual(S.statuses.ACCEPTED, S.statuses.SUCCEEDED);
});
test('change review identifies the option and field across registration and current-value requests', () => {
  const draft = {
    options: [{
      id: 'stable-option-id',
      itemName: '블랙'
    }]
  };
  const spec = require('../../../molebutter-app/src/main/resources/static/js/coupang-editor-model.js').spec;
  assert.equal(S.changeLabel('items.[1].salePrice', draft, spec), '옵션 블랙 · 등록 판매가');
  assert.equal(S.changeLabel('옵션.stable-option-id.salePrice', draft, spec), '옵션 블랙 · 현재 판매가');
  assert.equal(S.changeLabel('items.[1].images.[2].vendorPath', draft, spec), '옵션 블랙 · 이미지 2 · 이미지 주소');
  assert.equal(S.changeLabel('배송.returnCharge', draft, spec), '배송 · 반품 비용');
});
test('retry requires a replayable failure and the current saved draft revision', () => {
  for (const code of ['ALREADY_REGISTERED', 'UNSUPPORTED_SNAPSHOT_VERSION', 'BASELINE_CHANGED']) assert.equal(S.retryable({
    status: 'FAILED',
    revision: 1,
    target: {
      steps: [{
        status: 'FAILED',
        code
      }]
    }
  }, 1), false);
  const failed = {
    ...execution('FAILED', 'FAILED'),
    revision: 1
  };
  assert.equal(S.retryable(failed, 2), false);
  assert.equal(S.retryable(failed, 1), true);
  assert.equal(S.retryable({
    ...failed,
    revised: true
  }, 1), false);
  assert.equal(S.retryable(execution('PARTIAL', 'FAILED', 'ACCEPTED')), false);
});
const fs = require('node:fs'),
  vm = require('node:vm');
const uiSource = fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-submissions.js', 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
function setupUI(items) {
  const nodes = new Map(),
    reads = [],
    writes = [],
    busy = [];
  let saved = 0, settled=0;
  const get = id => {
    if (!nodes.has(id)) nodes.set(id, {
      hidden: false,
      disabled: false,
      open: false,
      dataset: {},
      listeners: {},
      value: '작성 중인 값',
      innerHTML: '',
      textContent: '',
      addEventListener(name, fn) {
        this.listeners[name] = fn;
      },
      querySelectorAll() {
        return [];
      },
      showModal() {
        this.open = true;
      },
      close() {
        this.open = false;
      },
      focus() {},
      scrollIntoView() {}
    });
    return nodes.get(id);
  };
  const draft = {
    id: 'draft-1',
    revision: 2
  };
  const context = {
    FormActions: {bindExplicitSubmit(form, button, handler) {button.addEventListener('click', handler);}},
    AppUI: {
      $: get,
      escape: v => String(v ?? ''),
      uuid: () => 'key'
    },
    window: {
      addEventListener() {}
    },
    setTimeout: () => 1,
    clearTimeout() {},
    apiGet: url => {
      reads.push(url);
      return Promise.resolve({
        items,
        totalPages: 1
      });
    },
    apiPost: (url, body) => new Promise((resolve, reject) => writes.push({
      url,
      body,
      resolve,
      reject
    }))
  };
  vm.runInNewContext(uiSource, context);
  const ui = context.window.CoupangSubmissionUI.create({
    canAct: () => true,
    save: () => {
      saved++;
      return Promise.resolve(draft);
    },
    settled: async () => { settled++; },
    busy: value => busy.push(value)
  });
  ui.workingCopy(draft);
  return {
    ui,
    get,
    reads,
    writes,
    busy,
    draft,
    saved: () => saved,
    settled:()=>settled,
    action(type, id = 'execution-1') {
      const button = {
        disabled: false,
        dataset: {
          submissionAction: type,
          executionId: id
        }
      };
      get('coupang-submissions').listeners.click({
        target: {
          closest: () => button
        }
      });
    },
    click(id) {
      get(id).listeners.click();
    },
    setItems(value) {
      items = value;
    }
  };
}
const partial = () => ({
  id: 'execution-1',
  workingCopyId: 'draft-1',
  revision: 1,
  status: 'PARTIAL',
  target: {
    mode: 'UPDATE',
    steps: [{
      status: 'SUCCEEDED',
      label: '배송'
    }, {
      status: 'FAILED',
      code: 'HTTP_400',
      label: '가격'
    }, {
      status: 'QUEUED',
      label: '재고'
    }]
  }
});
test('shared history offers input revision after draft revision changes and preserves attempted results', async () => {
  const old = partial(),
    s = setupUI([old]);
  await flush();
  assert.match(s.get('coupang-submission-list').innerHTML, /data-submission-action="revise"/);
  assert.doesNotMatch(s.get('coupang-submission-list').innerHTML, /data-submission-action="retry"/);
  const input = s.get('common-field-common-name');
  s.action('revise');
  s.action('revise');
  assert.equal(s.writes.length, 1);
  assert.equal(s.writes[0].url, '/api/coupang/submissions/execution-1/revise');
  assert.equal(s.saved(), 0);
  const revised = {
    ...old,
    revised: true,
    target: {
      ...old.target,
      steps: [...old.target.steps.slice(0, 2), {
        status: 'FAILED',
        code: 'NOT_EXECUTED',
        label: '재고'
      }]
    }
  };
  s.setItems([revised]);
  s.writes[0].resolve(revised);
  await flush();
  assert.equal(input.value, '작성 중인 값');
  assert.equal(s.saved(), 0);
  assert.equal(s.reads.length, 2);
  assert.doesNotMatch(s.get('coupang-submission-list').innerHTML, /data-submission-action="(?:revise|retry)"/);
  assert.match(s.get('coupang-submission-list').innerHTML, /성공/);
  assert.match(s.get('coupang-submission-list').innerHTML, /실패/);
  assert.equal(s.busy.at(-1),false);
  assert(s.busy.includes(true));
  s.action('revise');
  assert.equal(s.writes.length, 1);
});
test('revision failure retains input and permits an explicit recovery retry', async () => {
  const s = setupUI([partial()]);
  await flush();
  s.action('revise');
  s.writes[0].reject(Error('충돌'));
  await flush();
  assert.equal(s.get('common-field-common-name').value, '작성 중인 값');
  assert.equal(s.get('coupang-submission-error').textContent, '충돌');
  s.action('revise');
  assert.equal(s.writes.length, 2);
});
test('uncertain accepted running and already revised executions cannot release their locks from shared UI', async () => {
  for (const blocked of [execution('UNKNOWN', 'FAILED', 'UNKNOWN'), execution('PARTIAL', 'FAILED', 'ACCEPTED'), execution('RUNNING', 'FAILED', 'RUNNING'), {
    ...partial(),
    revised: true
  }]) {
    const s = setupUI([{
      ...blocked,
      id: 'execution-1'
    }]);
    await flush();
    assert.doesNotMatch(s.get('coupang-submission-list').innerHTML, /data-submission-action="revise"/);
    s.action('revise');
    assert.equal(s.writes.length, 0);
  }
});
test('revision invalidates prepared confirmation and permits a fresh preparation without reloading inputs', async () => {
  const previous=partial();previous.target.steps=previous.target.steps.map(v=>v.status==='QUEUED'?{...v,status:'FAILED'}:v);const s = setupUI([previous]);
  await flush();
  s.click('coupang-save');
  await flush();
  s.writes[0].resolve({
    id: 'preview-old',
    workingCopyId: s.draft.id,
    revision: 2,
    executable: true,
    target: null
  });
  await flush();
  assert.equal(s.get('coupang-submission-dialog').open, true);
  const revised = {
    ...previous,
    revised: true
  };
  s.setItems([revised]);
  s.action('revise');
  s.writes[1].resolve(revised);
  await flush();
  assert.equal(s.get('coupang-submission-dialog').open, false);
  s.click('coupang-submission-confirm');
  assert.equal(s.writes.length, 2);
  s.click('coupang-save');
  await flush();
  assert.equal(s.writes[2].url, '/api/coupang/submissions/prepare');
  assert.equal(s.saved(), 2);
});
test('a late revision response cannot overwrite the next draft history or confirmation', async () => {
  const s = setupUI([partial()]);
  await flush();
  s.action('revise');
  s.setItems([]);
  s.ui.workingCopy({
    id: 'draft-2',
    revision: 4
  });
  await flush();
  s.writes[0].resolve({
    ...partial(),
    revised: true
  });
  await flush();
  assert.doesNotMatch(s.get('coupang-submission-list').innerHTML, /execution-1/);
  assert.equal(s.reads.length, 2);
  assert.equal(s.busy.at(-1),false);
  assert(s.busy.includes(true));
});
test('normal refresh settles a successful execution exactly once',async()=>{let settled=0;const done={id:'finished',workingCopyId:'draft-1',revision:2,status:'SUCCEEDED',target:{steps:[{status:'SUCCEEDED'}]}};const s=setupUI([]);await flush();s.setItems([done]);s.click('coupang-submission-refresh');await flush();await flush();assert.equal(s.settled(),1);s.click('coupang-submission-refresh');await flush();assert.equal(s.settled(),1);});
test('immediate success releases editor input locks after settlement',async()=>{
 const s=setupUI([]);await flush();s.click('coupang-save');await flush();
 s.writes[0].resolve({id:'preview',workingCopyId:'draft-1',revision:2,executable:true,target:{steps:[]}});await flush();
 s.click('coupang-submission-confirm');await flush();
 s.writes[1].resolve({id:'finished',workingCopyId:'draft-1',revision:2,status:'SUCCEEDED',target:{steps:[{id:'create',status:'SUCCEEDED'}]}});await flush();await flush();
 assert.equal(s.settled(),1);assert.equal(s.busy.at(-1),false);assert.equal(s.get('coupang-save').disabled,false);
});
