"use strict";

/* Reads share a cancellable queue; never retry coupang writes in the browser. */
(() => {
  'use strict';

  const active = new Set(),
    queue = [];
  let pumping = false,
    lastFinished = -Infinity;
  const cancelled = () => new DOMException('조회가 취소되었습니다.', 'AbortError');
  function wait(ms, signal) {
    return new Promise((resolve, reject) => {
      if (signal.aborted) {
        reject(cancelled());
        return;
      }
      const abort = () => {
        clearTimeout(timer);
        reject(cancelled());
      };
      const timer = setTimeout(() => {
        signal.removeEventListener('abort', abort);
        resolve();
      }, ms);
      signal.addEventListener('abort', abort, {
        once: true
      });
    });
  }
  async function pump() {
    if (pumping) return;
    pumping = true;
    try {
      while (queue.length) {
        const task = queue.shift();
        try {
          if (task.controller.signal.aborted) throw cancelled();
          await wait(Math.max(0, 1000 - (performance.now() - lastFinished)), task.controller.signal);
          if (task.controller.signal.aborted) throw cancelled();
          task.sent = true;
          const options = {
            ...task.options,
            signal: task.controller.signal,
            headers: {
              ...task.options.headers,
              'X-Coupang-Request-Id': task.id
            }
          };
          task.resolve(await (options.method && options.method !== 'GET' ? apiRequest(task.url, options) : apiGet(task.url, options)));
        } catch (error) {
          task.reject(error);
        } finally {
          if (task.sent) lastFinished = performance.now();
        }
      }
    } finally {
      pumping = false;
    }
  }
  function start(url, options = {}) {
    const task = {
      id: AppUI.uuid(),
      controller: new AbortController(),
      url,
      options,
      sent: false
    };
    let settled = false;
    const promise = new Promise((resolve, reject) => {
      task.resolve = resolve;
      task.reject = reject;
    });
    const read = {
      promise: promise.finally(() => {
        settled = true;
        active.delete(read);
      }),
      cancel() {
        if (settled || task.controller.signal.aborted) return;
        task.controller.abort();
        task.reject(cancelled());
        active.delete(read);
        // Only a dispatched read has a server operation to cancel.
        if (task.sent) securedFetch('/api/coupang/requests/' + task.id + '/cancel', {
          method: 'POST',
          keepalive: true
        }).catch(() => {});
      }
    };
    active.add(read);
    queue.push(task);
    queueMicrotask(pump);
    return read;
  }
  window.addEventListener('pagehide', () => {
    for (const read of [...active]) read.cancel();
  });
  window.CoupangRead = {
    start
  };
})();
