const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {environment}=require('./helpers/form-dom.cjs');
test('input Enter and implicit submit never upload; explicit button activation preserves validation and request identity',async()=>{
 const root='molebutter-app/src/main/resources/',f=environment(fs.readFileSync(root+'templates/product-images.html','utf8')),requests=[];
 Object.assign(f.context,{sendJson:async(url,method,body)=>{requests.push({url,method,body});return {id:'job',status:'SUCCEEDED',result:{files:[]}};},showToast(){},sleep:async()=>{},AppUI:{uuid:()=> '11111111-1111-4111-8111-111111111111'},sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}}});
 const source=fs.readFileSync(root+'static/js/product-images/upload-dialog.js','utf8').replace(/^import[^\n]+\n/gm,'').replace(/^export /gm,'');f.run(source);
 f.context.enableImageUploading({canOpen:()=>true,snapshot:()=>({productCode:'SOURCE',uploadProductCode:'TARGET',images:[{imageIndex:0}]}),onState(){}});
 const button=f.$('uploadImagesButton'),confirm=f.$('confirmUploadButton');button.disabled=false;await button.click();assert.equal(f.$('imageUploadDialog').open,true);
 const input=f.$('uploadProductCodeInput')||f.$('storageProductCodeInput');input.value='TARGET';f.key(input);await f.$('imageUploadForm').fire('submit');await f.settle();assert.equal(requests.length,0);assert.equal(input.value,'TARGET');
 assert.equal(confirm.type,'button');const key=await confirm.fire('keydown',{key:'Enter'});assert.equal(key.defaultPrevented,undefined);
 // Native browsers synthesize click for a focused button's Enter/Space; this fixture invokes that activation explicitly.
 await confirm.click();assert.equal(requests.length,1);assert.equal(requests[0].method,'POST');assert.equal(requests[0].body.uploadProductCode,'TARGET');assert.equal(requests[0].body.requestId,'11111111-1111-4111-8111-111111111111');
});
test('product-code Enter runs one read lookup while upload input Enter remains inactive',async()=>{
 const root='molebutter-app/src/main/resources/',f=environment(fs.readFileSync(root+'templates/product-images.html','utf8')),reads=[],writes=[];
 Object.assign(f.context,{ResizeObserver:class {observe(){}},copyText:async()=>{},escapeAttribute:v=>v,escapeHtml:v=>v,formatWon:v=>v,sendJson:async(url,method)=>{(method==='POST'?writes:reads).push(url);throw Error('합성 조회 실패');},showToast(){},sleep:async()=>{},downloadZip:async()=>{},openSizeGuideEditor(){},ImageOrder:class {constructor(){this.order=[];this.trash=[];this.selected=null;}},enableImageDragging:()=>({cancel(){}}),enableImageUploading(){}});
 const source=fs.readFileSync(root+'static/js/product-images/app.js','utf8').replace(/^import[^\n]+\n/gm,'');f.run(source);const input=f.$('productCodeInput');input.value=' TEST ';f.key(input);await f.settle();assert.equal(reads.length,1);assert.match(reads[0],/\/products\/TEST\?brand=/);assert.equal(writes.length,0);input.value='';await f.$('lookupButton').click();assert.equal(reads.length,1);
});
