const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-state.js','utf8');
function storage(entries){const data=new Map(entries);return {data,get length(){return data.size;},key:i=>[...data.keys()][i],getItem:k=>data.has(k)?data.get(k):null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)};}
test('V46 moves each tab navigation and pending state without clearing authentication or procurement',()=>{
 const localStorage=storage([['accessToken','auth'],['procurement.search','keep'],['molebutter.marketplace.pending','draft-bytes']]);
 const sessionStorage=storage([['coupang-registration-42','pending-write'],['molebutter.coupang.navigation','products'],['molebutter.marketplace.drafts.navigation.v45','drafts'],['inventory.filters','keep']]);
 vm.runInNewContext(source,{localStorage,sessionStorage});
 assert.equal(localStorage.getItem('accessToken'),'auth');assert.equal(localStorage.getItem('procurement.search'),'keep');assert.equal(sessionStorage.getItem('inventory.filters'),'keep');
 assert.equal(localStorage.getItem('molebutter.coupang.pending'),'draft-bytes');assert.equal(localStorage.getItem('molebutter.marketplace.pending'),null);
 assert.equal(sessionStorage.getItem('molebutter.coupang.products.navigation.v46'),'products');assert.equal(sessionStorage.getItem('molebutter.coupang.drafts.navigation.v46'),'drafts');assert.equal(sessionStorage.getItem('coupang-registration-42'),'pending-write');
 const otherTab=storage([['molebutter.coupang.navigation','other-tab']]);vm.runInNewContext(source,{localStorage,sessionStorage:otherTab});assert.equal(otherTab.getItem('molebutter.coupang.products.navigation.v46'),'other-tab');
});
test('existing new values and conflicting old input both survive retry',()=>{
 const localStorage=storage([]),sessionStorage=storage([['molebutter.marketplace.pending','old'],['molebutter.coupang.pending','new']]);vm.runInNewContext(source,{localStorage,sessionStorage});
 assert.equal(sessionStorage.getItem('molebutter.marketplace.pending'),'old');assert.equal(sessionStorage.getItem('molebutter.coupang.pending'),'new');assert.equal(sessionStorage.getItem('molebutter.coupang.schema.v46'),null);
});
test('disabled browser storage does not prevent the editor from opening',()=>assert.doesNotThrow(()=>vm.runInNewContext(source,{})));
