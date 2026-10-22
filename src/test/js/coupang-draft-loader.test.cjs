'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const flush=()=>new Promise(r=>setImmediate(r));
function setup(){const elements={},reads=[];
 const get=id=>elements[id]??={value:'',textContent:'',innerHTML:'',hidden:false,disabled:false,open:false,listeners:{},focus(){},querySelectorAll(){return[];},querySelector(){return get('submit');},addEventListener(t,f){this.listeners[t]=f;},showModal(){this.open=true;},close(){this.open=false;this.listeners.close?.();}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-working-copies.js','utf8'),{AppUI:{$:get,escape:s=>String(s).replaceAll('<','&lt;')},FormActions:{bindEnterSubmit(){}},URLSearchParams,window:{addEventListener(){}},apiGet:url=>new Promise((resolve,reject)=>reads.push({url,resolve,reject}))});
 return{get,reads,open(){get('editor-load-working-copy').listeners.click();},submit(){get('coupang-working-copies-query').listeners.submit({preventDefault(){}});}};
}
const listing={items:[{id:'123',mode:'CREATE',name:'상품 <입력>',updatedAt:'2026-10-16T10:00:00',blocked:true}],totalElements:21,totalPages:2};
test('loader requests CREATE on server with 20 rows, search and page; cancel preserves form',async()=>{
 const s=setup();s.get('editor-name').value='미저장 입력';s.open();let q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('mode'),'CREATE');assert.equal(q.get('size'),'20');s.reads.shift().resolve(listing);await flush();assert.match(s.get('coupang-working-copies-rows').innerHTML,/전송 결과 확인 필요/);assert.match(s.get('coupang-working-copies-rows').innerHTML,/\/coupang\/working-copies\/123\/edit/);assert.match(s.get('coupang-working-copies-rows').innerHTML,/&lt;입력>/);
 s.get('coupang-working-copies-next').listeners.click();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('page'),'1');s.reads.shift().resolve(listing);await flush();s.get('coupang-working-copies-search').value='상품';s.submit();q=new URL(s.reads[0].url,'http://local').searchParams;assert.equal(q.get('page'),'0');assert.equal(q.get('query'),'상품');s.reads.shift().reject(Error('조회 실패'));await flush();assert.equal(s.get('editor-name').value,'미저장 입력');assert.equal(s.get('coupang-working-copies-search').value,'상품');assert.match(s.get('coupang-working-copies-rows').innerHTML,/123/);
 s.get('coupang-working-copy-close').listeners.click();assert.equal(s.get('coupang-working-copy-dialog').open,false);assert.equal(s.get('editor-name').value,'미저장 입력');
});
test('closing ignores a late response and rejects update drafts',async()=>{
 const s=setup();s.open();s.get('coupang-working-copy-close').listeners.click();s.reads.shift().resolve(listing);await flush();assert.equal(s.get('coupang-working-copies-rows').innerHTML,'');s.open();s.reads.shift().resolve({...listing,items:[{...listing.items[0],mode:'UPDATE'}]});await flush();assert.equal(s.get('coupang-working-copies-error').hidden,false);assert.equal(s.get('coupang-working-copies-rows').innerHTML,'');
});
