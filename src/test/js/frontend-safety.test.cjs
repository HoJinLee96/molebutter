const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const common = readFileSync('molebutter-app/src/main/resources/static/js/app-ui.js', 'utf8');
const source = common + readFileSync('molebutter-app/src/main/resources/static/js/product-source-search.js', 'utf8');

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
