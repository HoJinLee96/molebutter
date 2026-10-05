const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const ctx=vm.createContext({window:{},document:{addEventListener(){}},AppUI:{escape:x=>String(x??'').replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;'),stamp:x=>x},ProductSourceSearch:{webUrl:()=>''}});
vm.runInContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/product-changes.js','utf8'),ctx);const ui=ctx.window.ProductChanges;
const summary={selectedId:'1',listings:[{supplierId:'1',deltas:[{kind:'STOCK',optionId:'a',scope:'OPTION',difference:-63,before:100,after:37},{kind:'STOCK',optionId:'b',scope:'OPTION',difference:3,before:1,after:4}]}]};
test('multiple option deltas are hidden in list and retained in detail without summing quantities',()=>{const listing={id:'1',result:{options:[{id:'a'},{id:'b'}]}};assert.equal(ui.selectedStock(summary,listing,'42'),'');const html=ui.selectedStock(summary,listing);assert.match(html,/옵션 재고 변동 2건/);assert.doesNotMatch(html,/60개|41개/);});
test('same option id in another scope does not receive the quantity delta',()=>{assert.equal(ui.option(summary,'1',{id:'a',stockScope:'PRODUCT'}),'');assert.match(ui.option(summary,'1',{id:'a'}),/63개/);});
test('zero or absent price delta has no badge and shipping has no product percentage',()=>{assert.equal(ui.metric({listings:[{supplierId:'1',deltas:[{kind:'PRICE',difference:0}]}]},'1','PRICE'),'');const fee=ui.metric({listings:[{supplierId:'1',deltas:[{kind:'DELIVERY',difference:3000,before:3000,after:6000}]}]},'1','DELIVERY');assert.match(fee,/3,000원/);assert.doesNotMatch(fee,/%/);});

test('list retains stock, sold-out and restock deltas while detail retains all option changes',()=>{
 const ds=['STOCK','SOLD_OUT','RESTOCK','AVAILABILITY','OPTION_NEW','OPTION_MISSING'].map(kind=>({kind,optionId:'a',scope:'OPTION'}));
 const changes={listings:[{supplierId:'1',deltas:ds}]},listing={id:'1',result:{options:[{id:'a'}]}};
 const compact=ui.selectedStock(changes,listing,'42'),detail=ui.selectedStock(changes,listing);
 assert.match(compact,/품절 전환/);assert.match(compact,/재입고/);assert.doesNotMatch(compact,/구매 상태 변경|신규 옵션|옵션 구성 변경/);
 assert.match(detail,/구매 상태 변경/);assert.match(detail,/신규 옵션/);assert.match(detail,/옵션 구성 변경/);
});
