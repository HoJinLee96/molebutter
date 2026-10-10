const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const common = readFileSync('molebutter-app/src/main/resources/static/js/app-ui.js', 'utf8');
const source = common + readFileSync('molebutter-app/src/main/resources/static/js/product-source-search.js', 'utf8');
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

test('UUID uses native randomUUID when available and exports the shared helper to CommonJS', () => {
    const expected = ['11111111-2222-4333-8444-555555555555', 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'];
    let calls = 0;
    const secureCrypto = {
        randomUUID() { assert.equal(this, secureCrypto); return expected[calls++]; },
        getRandomValues() { assert.fail('native UUID generation must not invoke the fallback'); },
    };
    const context = vm.createContext({ crypto: secureCrypto, module: { exports: {} } });
    vm.runInContext(common, context);
    assert.equal(vm.runInContext('AppUI.uuid()', context), expected[0]);
    assert.equal(vm.runInContext('AppUI.uuid()', context), expected[1]);
    assert.equal(calls, 2);
    assert.equal(context.module.exports.uuid, vm.runInContext('AppUI.uuid', context));
});

test('insecure contexts generate fresh RFC 4122 v4 UUIDs using getRandomValues alone', () => {
    for (const randomUUID of [undefined, true]) {
        let calls = 0;
        const secureCrypto = { randomUUID, getRandomValues(bytes) {
            assert.equal(this, secureCrypto);
            assert.equal(ArrayBuffer.isView(bytes), true);
            assert.equal(bytes.byteLength, 16);
            if (calls === 0) bytes.fill(0);
            else if (calls === 1) bytes.fill(0xff);
            else bytes.forEach((_, index) => { bytes[index] = index; });
            calls++;
            return bytes;
        }};
        const math = Object.create(Math);
        math.random = () => assert.fail('UUID generation must never use Math.random');
        const context = vm.createContext({ crypto: secureCrypto, isSecureContext: false, Math: math });
        vm.runInContext(common, context);
        const ids = Array.from({ length: 3 }, () => vm.runInContext('AppUI.uuid()', context));
        assert.deepEqual(ids, [
            '00000000-0000-4000-8000-000000000000',
            'ffffffff-ffff-4fff-bfff-ffffffffffff',
            '00010203-0405-4607-8809-0a0b0c0d0e0f',
        ]);
        ids.forEach(id => assert.match(id, uuidV4));
        assert.equal(new Set(ids).size, ids.length);
        assert.equal(calls, ids.length);
    }
});

test('UUID refuses to generate identifiers without secure entropy', () => {
    for (const crypto of [undefined, null, {}, { randomUUID: true, getRandomValues: true }]) {
        const math = Object.create(Math);
        math.random = () => assert.fail('missing secure entropy must not fall back to Math.random');
        const context = vm.createContext({ crypto, Math: math });
        vm.runInContext(common, context);
        assert.throws(() => vm.runInContext('AppUI.uuid()', context), error => {
            assert.equal(error.name, 'Error');
            assert.match(error.message, /보안 난수/);
            return true;
        });
    }
});

test('frontend callers centralize UUID creation in AppUI', () => {
    const directory = 'molebutter-app/src/main/resources/static/js';
    function javascriptFiles(path) {
        return readdirSync(path, { withFileTypes: true }).flatMap(entry => {
            const file = join(path, entry.name);
            return entry.isDirectory() ? javascriptFiles(file) : entry.name.endsWith('.js') ? [file] : [];
        });
    }
    for (const file of javascriptFiles(directory)) {
        if (file === join(directory, 'app-ui.js')) continue;
        assert.doesNotMatch(readFileSync(file, 'utf8'), /\bcrypto\s*(?:\.|\?\.)\s*randomUUID\b/, file);
    }
    assert.doesNotMatch(common, /\bMath\s*\.\s*random\s*\(/);
});

test('outbound links accept HTTP and HTTPS but reject script URLs and credentials', () => {
    const context = vm.createContext({URL});vm.runInContext(source, context);
    const webUrl = vm.runInContext('ProductSourceSearch.webUrl', context);
    for (const url of ['http://www.hazzys.com/product/1', 'https://www.hazzys.com/product/1']) assert.equal(webUrl(url), url);
    for (const url of ['', 'javascript:alert(1)', 'data:text/html,test', 'ftp://example.com/a', 'http://user:pass@example.com/a']) assert.equal(webUrl(url), '');
});


const ctx=vm.createContext({});
vm.runInContext(common + readFileSync('molebutter-app/src/main/resources/static/js/mall-tag.js','utf8')+';globalThis.tag=MallTag;',ctx);
const {tag}=ctx;
test('mall labels escape untrusted HTML',()=>{
    const html=tag.html('HMALL','<b>"x"</b>');
    assert.match(html,/&lt;b&gt;&quot;x&quot;&lt;\/b&gt;/);
    assert.doesNotMatch(html,/<b>|"x"/);
});
