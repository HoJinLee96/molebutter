const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const {environment}=require('./helpers/form-dom.cjs');
const source=readFileSync('molebutter-app/src/main/resources/static/js/supplier-editor.js','utf8');
async function fixture(mall='HI_THEHYUNDAI'){
 const f=environment(),posts=[],preferences=[];
 const data={revision:0,stores:[],rules:[],groups:[{mall,scope:'STORES',storeIds:[]}],branchRequirements:{}};
 Object.assign(f.context,{AppUI:{escape:v=>String(v??'')},confirm:()=>true,apiGet:async url=>url==='/api/settings/preferred-suppliers'?structuredClone(data):url==='/api/settings/suppliers'?[{code:mall,name:mall}]:[],apiPost:async(url,body)=>{posts.push({url,body});if(url.endsWith('/channel-preview'))return {channelName:'official',channelUid:'channel-1',preferenceRevision:0};const store={...body,id:'store-1',revision:0};data.stores=[store];return store;},apiRequest:async(url,options)=>{preferences.push({url,...options});return {};}});
 f.run(source);await f.window.SupplierEditor.open({admin:true,mall});
 return {...f,posts,preferences};
}
test('store name Enter does not persist the draft; explicit add and preference save remain separate',async()=>{
 const f=await fixture();await f.$('supplier-editor-new').click();const form=f.$('supplier-editor-form'),name=f.$('supplier-editor-name');name.value='new branch';
 f.key(name);await form.fire('submit');assert.equal(f.posts.length,0);assert.equal(name.value,'new branch');
 await f.$('supplier-editor-draft-add').click();await f.settle();assert.equal(f.posts.length,1);assert.equal(f.posts[0].url,'/api/settings/supplier-stores');assert.equal(f.preferences.length,0);
 await f.$('supplier-editor-results').querySelector('[data-choose-store]').click();await f.$('supplier-editor-save').click();await f.settle();
 assert.equal(f.preferences.length,1);assert.equal(f.preferences[0].method,'PUT');assert.deepEqual(JSON.parse(f.preferences[0].body).storeIds,['store-1']);
});
test('supplier product URL Enter only previews the channel; datalist keeps its native Enter',async()=>{
 const f=await fixture('NAVER_SMART_STORE');await f.$('supplier-editor-official-new').click();const input=f.$('supplier-editor-product-url');input.value='https://brand.naver.com/example/products/1';
 f.key(input);await f.settle();assert.equal(f.posts.length,1);assert.equal(f.posts[0].url,'/api/settings/supplier-stores/channel-preview');
 f.key(f.$('supplier-editor-name'));await f.$('supplier-editor-form').fire('submit');assert.equal(f.posts.length,1);
 assert.equal(f.key(f.$('supplier-editor-retailer')).defaultPrevented,undefined);
 await f.$('supplier-editor-draft-add').click();assert.equal(f.posts.length,2);assert.equal(f.posts[1].url,'/api/settings/supplier-stores');assert.equal(f.posts[1].body.expectedChannelUid,'channel-1');
});
test('failed explicit store save retains draft inputs and can be retried',async()=>{
 const f=await fixture();await f.$('supplier-editor-new').click();const name=f.$('supplier-editor-name');name.value='keep me';
 let calls=0;f.context.apiPost=async()=>{if(++calls===1)throw Error('mock failure');return {id:'store-1',mall:'HI_THEHYUNDAI',kind:'BRANCH',name:'keep me'};};
 await f.$('supplier-editor-draft-add').click();assert.equal(name.value,'keep me');assert.equal(f.$('supplier-editor-draft').hidden,false);assert.match(f.$('supplier-editor-draft-error').textContent,/mock failure/);
 f.key(name);assert.equal(calls,1);await f.$('supplier-editor-draft-add').click();assert.equal(calls,2);
});
