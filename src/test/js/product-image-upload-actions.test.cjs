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
