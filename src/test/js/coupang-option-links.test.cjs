'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {coupangOptionLink:link}=require('../../../molebutter-app/src/main/resources/static/js/app-ui.js');
test('exposure and vendor IDs form the precise Coupang URL with safe native new-tab navigation',()=>{
 const html=link('9550561343','96040603660');assert(html.includes('href="https://www.coupang.com/vp/products/9550561343?vendorItemId=96040603660"'));assert(html.includes('target="_blank"'));assert(html.includes('rel="noopener noreferrer"'));assert(html.includes('>96040603660</a>'));
 assert(link('999999999999999999999999','888888888888888888888888').includes('/999999999999999999999999?vendorItemId=888888888888888888888888'));
});
test('missing exposure IDs resolve from the local DB without substituting seller or vendor IDs',()=>{
 assert(link(null,'96040603660').includes('href="/api/coupang/products/options/96040603660/open"'));
 assert(link('javascript:alert(1)','96040603660').includes('/options/96040603660/open'));
 assert(!link(null,null).includes('<a'));assert.equal(link(null,null,'등록 후 발급'),'등록 후 발급');
 assert.equal(link('9550561343','<script>'), '&lt;script&gt;');assert(!link('9550561343',Number.MAX_SAFE_INTEGER+1).includes('<a'));
});
