const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {environment}=require('./helpers/form-dom.cjs');
const root='molebutter-app/src/main/resources/';
const C=require('../../../molebutter-app/src/main/resources/static/js/marketplace-editor-model.js');
const N=require('../../../molebutter-app/src/main/resources/static/js/naver-product-editor-model.js');
globalThis.MarketplaceChannels=require('../../../test-fixtures/marketplace-channels.json');
const M=require('../../../molebutter-app/src/main/resources/static/js/common-marketplace-editor-model.js');
function fixture(name){const f=environment(fs.readFileSync(root+'templates/'+name+'.html','utf8')),calls=[];Object.assign(f.context,{AppUI:{$:f.$,escape:v=>String(v??''),uuid:()=> 'test-id'},apiPost:async(url,body)=>{calls.push({url,body});throw Error('합성 저장 실패');},apiRequest:async(url,body)=>{calls.push({url,body});throw Error('합성 저장 실패');},CSS:{escape:v=>v},AbortController,confirm:()=>true});f.context.location.pathname='/new';f.window.CoupangRead={start(){throw Error('unexpected read');}};return {f,calls};}
async function check({f,calls},formId,buttonId,input){const form=f.$(formId),button=f.$(buttonId);form.hidden=false;input.value='보존할 입력';const event=f.key(input);assert(event.defaultPrevented);await form.fire('submit');await f.settle();assert.equal(calls.length,0);assert.equal(input.value,'보존할 입력');await button.click();await f.settle();assert.equal(calls.length,1);assert.equal(input.value,'보존할 입력');}
test('common editor input Enter and implicit submit cannot save; explicit save preserves input on rejection',async()=>{
 const h=fixture('common-marketplace-editor'),{f,calls}=h;f.window.MarketplaceEditorModel={...M,validate:()=>[]};f.window.CoupangEditorModel=C;f.window.MarketplaceSubmissionUI={create:()=>({update(){}})};
 let source=fs.readFileSync(root+'static/js/common-marketplace-editor.js','utf8').replace(/    initialize\(\);\s*\}\)\(\);\s*$/,`    reference=M.fresh();reference.selectedMarkets=[];draft=reference;\n})();`);f.run(source);
 const input=f.document.createElement('input');f.$('common-editor-form').append(input);await check(h,'common-editor-form','common-save',input);assert.match(f.$('common-error').textContent,/합성 저장 실패/);
});
test('Coupang registration form requires an explicit save button and runs its input validation',async()=>{
 const h=fixture('marketplace-editor'),{f,calls}=h;let valid=true;f.window.CoupangEditorModel={...C,validate:()=>valid?[]:[{path:'basic.sellerProductName',message:'합성 필수값 누락'}]};f.window.CoupangProductRegistration={create:()=>({prepare:async()=>calls.push({prepare:true}),changed(){}})};
 const source=fs.readFileSync(root+'static/js/marketplace-editor.js','utf8').replace(/    initialize\(\);\s*\}\)\(\);\s*$/,`    draft=M.fresh();\n})();`);f.run(source);
 const input=f.document.createElement('input');f.$('marketplace-editor-form').append(input);await check(h,'marketplace-editor-form','editor-validate',input);valid=false;await f.$('editor-validate').click();assert.equal(calls.length,1);assert.match(f.$('editor-validation').textContent,/합성 필수값 누락/);
});
test('Naver registration form requires an explicit save button and preserves validation',async()=>{
 const h=fixture('naver-product-editor'),{f,calls}=h;let valid=true;f.window.NaverEditorModel={...N,validate:()=>valid?[]:[{path:'name',message:'합성 필수값 누락'}]};f.window.NaverEditorSave={create:()=>({prepare:async()=>calls.push({prepare:true}),changed(){}})};f.window.NaverProductForm={mount:()=>({validate:()=>valid?[]:[{message:'합성 필수값 누락'}],setValue(){},setBusy(){},destroy(){}})};
 const source=fs.readFileSync(root+'static/js/naver-product-editor.js','utf8').replace(/(?:heading\(\);)?load\(\);\s*\}\)\(\);\s*$/,'})();');f.run(source);
 const input=f.document.createElement('input');f.$('naver-form').append(input);await check(h,'naver-form','naver-save',input);valid=false;await f.$('naver-save').click();assert.equal(calls.length,1);assert.match(f.$('naver-validation').textContent,/합성 필수값 누락/);
});
