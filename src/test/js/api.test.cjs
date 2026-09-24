const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const source = readFileSync('src/main/resources/static/js/api.js', 'utf8');
const reply = (status, code, data = null) => new Response(JSON.stringify({ code, data }), {
    status, headers: { 'Content-Type': 'application/json' },
});

test('parallel expired requests share one refresh and send a CSRF header', async () => {
    let refreshes = 0, csrfFetches = 0, authenticated = false;
    const context = vm.createContext({ Headers, location: {}, fetch: async (url, options) => {
        if (url === '/api/auth/csrf') {
            csrfFetches++;
            return reply(200, 'SUCCESS', { headerName: 'X-XSRF-TOKEN', token: 'csrf' });
        }
        if (url === '/api/auth/refresh') {
            refreshes++;
            assert.equal(options.headers.get('X-XSRF-TOKEN'), 'csrf');
            await new Promise(resolve => setTimeout(resolve, 5));
            authenticated = true;
            return reply(200, 'SUCCESS');
        }
        return authenticated ? reply(200, 'SUCCESS', 'ok') : reply(401, 'UNAUTHORIZED');
    }});
    vm.runInContext(source, context);
    const results = await vm.runInContext("Promise.all([apiGet('/one'), apiGet('/two')])", context);
    assert.equal(results.join(','), 'ok,ok');
    assert.equal(refreshes, 1);
    assert.equal(csrfFetches, 1);
});

test('stale CSRF is fetched again once and does not trigger session refresh', async () => {
    let csrfFetches = 0, posts = 0;
    const context = vm.createContext({ Headers, location: {}, fetch: async (url, options) => {
        if (url === '/api/auth/csrf') return reply(200, 'SUCCESS', {
            headerName: 'X-XSRF-TOKEN', token: 'csrf-' + ++csrfFetches,
        });
        assert.equal(url, '/action');
        posts++;
        if (posts === 1) return reply(403, 'INVALID_CSRF_TOKEN');
        assert.equal(options.headers.get('X-XSRF-TOKEN'), 'csrf-2');
        return reply(200, 'SUCCESS', 'done');
    }});
    vm.runInContext(source, context);
    assert.equal(await vm.runInContext("apiPost('/action', {})", context), 'done');
    assert.equal(posts, 2);
    assert.equal(csrfFetches, 2);
});

test('permission denied is not retried', async () => {
    let requests = 0;
    const context = vm.createContext({ Headers, location: {}, fetch: async () => {
        requests++;
        return reply(403, 'HANDLE_ACCESS_DENIED');
    }});
    vm.runInContext(source, context);
    await assert.rejects(vm.runInContext("apiGet('/admin')", context), { code: 'HANDLE_ACCESS_DENIED' });
    assert.equal(requests, 1);
});
