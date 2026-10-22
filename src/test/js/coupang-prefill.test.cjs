const {test}=require('node:test'),assert=require('node:assert/strict'),{readFileSync}=require('node:fs');
const {environment}=require('./helpers/form-dom.cjs');
function fixture({blocked=false,reject=false}={}){
 const f=environment('<button id="register" type="button">상품 등록</button><p id="status"></p><a id="link" hidden></a>');let resolve,count=0,opened=0,closed=0,locked=false,url;
 const pending=new Promise(r=>resolve=r);f.window.open=()=>{opened++;return blocked?null:{closed:false,close(){closed++;},location:{replace(v){url=v;}}};};
 f.run(readFileSync('molebutter-app/src/main/resources/static/js/product-images/coupang-prefill.js','utf8').replace(/^export /gm,''));
 Object.assign(f.context,{opts:{button:f.$('register'),status:f.$('status'),link:f.$('link'),snapshot:()=>({productCode:'SOURCE',storageProductCode:'SKU123',images:[]}),canStart:()=>!locked,onBusy:v=>locked=v,post:async()=>{count++;await pending;if(reject)throw Error('조회 만료');return {editorUrl:'/coupang/products/new?prefill=11111111-1111-4111-8111-111111111111'};}}});f.run('enableCoupangPrefill(opts)');
 return {...f,resolve,count:()=>count,opened:()=>opened,closed:()=>closed,locked:()=>locked,url:()=>url};
}
test('preparation reserves one tab and does not perform upload/save/transmission on duplicate click',async()=>{const f=fixture();const first=f.$('register').click();assert.equal(f.count(),1);assert(f.locked());await f.$('register').click();assert.equal(f.count(),1);f.resolve();await first;assert.equal(f.opened(),1);assert.match(f.url(),/prefill=/);assert.equal(f.locked(),false);assert.equal(f.$('link').hidden,false);});
test('blocked popup leaves a reviewable local link and failure preserves inputs',async()=>{const f=fixture({blocked:true});const p=f.$('register').click();f.resolve();await p;assert.equal(f.$('link').hidden,false);assert.match(f.$('status').textContent,/차단/);const fail=fixture({reject:true});const q=fail.$('register').click();fail.resolve();await q;assert.equal(fail.closed(),1);assert.match(fail.$('status').textContent,/만료/);assert.equal(fail.locked(),false);});
