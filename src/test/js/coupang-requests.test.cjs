"use strict";

const test = require('node:test'),
  assert = require('node:assert/strict'),
  vm = require('node:vm'),
  fs = require('node:fs'),
  path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../../molebutter-app/src/main/resources/static/js/coupang-requests.js'), 'utf8');
function fixture() {
  const calls = [],
    cancels = [],
    events = {};
  let id = 0;
  const get = (url, options) => new Promise((resolve, reject) => {
    const call = {
      url,
      start: performance.now(),
      end: null
    };
    calls.push(call);
    const timer = setTimeout(() => {
      call.end = performance.now();
      url === '/fail' ? reject(Error('조회 실패')) : resolve(url);
    }, 30);
    options.signal.addEventListener('abort', () => {
      clearTimeout(timer);
      call.end = performance.now();
      reject(new DOMException('cancelled', 'AbortError'));
    }, {
      once: true
    });
  });
  const window = {
    addEventListener: (name, fn) => events[name] = fn
  };
  vm.runInNewContext(source, {
    window,
    AppUI: {
      uuid: () => String(++id)
    },
    AbortController,
    DOMException,
    performance,
    setTimeout,
    clearTimeout,
    queueMicrotask,
    apiGet: get,
    apiRequest: get,
    securedFetch: async url => cancels.push(url)
  });
  return {
    start: window.CoupangRead.start,
    calls,
    cancels,
    events
  };
}
test('reads serialize with one second after completion, including failure', async () => {
  const f = fixture(),
    a = f.start('/fail'),
    b = f.start('/next');
  await assert.rejects(a.promise, /조회 실패/);
  assert.equal(await b.promise, '/next');
  assert.equal(f.calls.length, 2);
  assert(f.calls[1].start - f.calls[0].end >= 990);
});
test('queued and cooling reads cancel without HTTP or cancellation requests', async () => {
  const f = fixture(),
    a = f.start('/first'),
    b = f.start('/queued');
  const rejected = assert.rejects(b.promise, {
    name: 'AbortError'
  });
  b.cancel();
  await rejected;
  await a.promise;
  const c = f.start('/cooling');
  const stopped = assert.rejects(c.promise, {
    name: 'AbortError'
  });
  await new Promise(r => setTimeout(r, 20));
  c.cancel();
  await stopped;
  const d = f.start('/last');
  assert.equal(await d.promise, '/last');
  assert.deepEqual(f.calls.map(c => c.url), ['/first', '/last']);
  assert.equal(f.cancels.length, 0);
  assert(f.calls[1].start - f.calls[0].end >= 990);
});
test('pagehide cancels dispatched and queued reads, notifies only dispatched operation', async () => {
  const f = fixture(),
    a = f.start('/active'),
    b = f.start('/pending');
  const stopped = Promise.all([assert.rejects(a.promise, {
    name: 'AbortError'
  }), assert.rejects(b.promise, {
    name: 'AbortError'
  })]);
  await new Promise(r => setTimeout(r, 10));
  f.events.pagehide();
  await stopped;
  await new Promise(r => setTimeout(r, 20));
  assert.equal(f.calls.length, 1);
  assert.equal(f.cancels.length, 1);
});
