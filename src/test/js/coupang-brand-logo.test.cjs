const {test}=require('node:test'),assert=require('node:assert/strict');
const logo=require('../../../molebutter-app/src/main/resources/static/js/coupang-brand-logo.js');
const model=require('../../../molebutter-app/src/main/resources/static/js/coupang-working-copy-model.js');
const editor=require('../../../molebutter-app/src/main/resources/static/js/coupang-editor-model.js');
const row=(id,content,purpose)=>({id,type:'IMAGE',detailType:'IMAGE',content,assetId:null,...(purpose?{purpose}:{})});
test('managed logo follows common prefix and replaces only its own stable row',()=>{
 const guide=row('guide','https://assets.test/common/guide.png'),photo=row('photo','https://assets.test/products/CODE/1.png'),html={id:'html',type:'HTML',detailType:'TEXT',content:'<p>keep</p>'},option={contents:[guide,photo,html]};
 assert(logo.apply(option,'https://assets.test/common/daks.png',()=> 'logo'));assert.deepEqual(option.contents.map(v=>v.id),['guide','logo','photo','html']);const managed=logo.current(option);
 assert(logo.apply(option,'https://assets.test/common/other.png',()=> 'unused'));assert.equal(logo.current(option),managed);assert.equal(managed.id,'logo');assert.equal(guide,option.contents[0]);assert.equal(html,option.contents[3]);
 assert.equal(logo.apply(option,managed.content,()=> 'unused'),false);assert(logo.apply(option,'',()=> 'unused'));assert.deepEqual(option.contents,[guide,photo,html]);
});
test('same URL never converts an unmanaged row into a logo or inserts duplicates',()=>{
 const old=row('untouched','https://assets.test/common/daks.png'),option={contents:[old]};assert.throws(()=>logo.apply(option,old.content,()=> 'new'),/같은 이미지/);assert.equal(old.purpose,undefined);assert.deepEqual(option.contents,[old]);assert.equal(logo.apply(option,'',()=> 'new'),false);
});
test('purpose survives flatten, restore and option inheritance with a new UUID',()=>{
 const option=editor.blankOption();option.contents=[row('11111111-1111-4111-8111-111111111111','https://assets.test/common/daks.png','BRAND_LOGO')];const document={basic:{sellerProductName:'닥스'},options:[option],delivery:{},settings:{},documents:[]};
 const flattened=model.flatten(document);assert.equal(flattened.options[0].contents[0].purpose,'BRAND_LOGO');assert.equal(model.inflate({document:flattened}).options[0].contents[0].purpose,'BRAND_LOGO');
 const copied=editor.inheritShared(document,editor.blankOption());assert.equal(copied.contents[0].purpose,'BRAND_LOGO');assert.notEqual(copied.contents[0].id,option.contents[0].id);assert.notEqual(copied.contents[0],option.contents[0]);
});
