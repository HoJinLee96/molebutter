globalThis.MarketplaceChannels=require('../../../test-fixtures/marketplace-channels.json');
const {test}=require('node:test'),assert=require('node:assert/strict');
const M=require('../../../molebutter-app/src/main/resources/static/js/common-marketplace-editor-model.js');
test('overrides preserve explicit empty and zero; clearing follows latest common value',()=>{
 const d=M.fresh(),id=d.options[0].id;d.common.name='공통';d.options[0].price='100';
 assert.equal(M.resolved(d,'COUPANG','name'),'공통');M.toggle(d,'COUPANG','name',true);d.markets.COUPANG.overrides.name='';assert.equal(M.resolved(d,'COUPANG','name'),'');
 M.toggle(d,'COUPANG','price',true,id);M.optionOverride(d,'COUPANG',id).price='0';assert.equal(M.resolved(d,'COUPANG','price',id),'0');d.options[0].price='200';M.toggle(d,'COUPANG','price',false,id);assert.equal(M.resolved(d,'COUPANG','price',id),'200');
 d.common.name='최신';M.toggle(d,'COUPANG','name',false);assert.equal(M.resolved(d,'COUPANG','name'),'최신');
});
test('stable option IDs survive normalization; deleting cleans only associated data',()=>{
 const d=M.fresh(),a=d.options[0],b=M.blankOption();d.options.push(b);M.toggle(d,'NAVER','name',true,a.id);M.toggle(d,'NAVER','name',true,b.id);
 d.media.images=[{id:'A',url:'https://img.test/a',optionId:a.id},{id:'B',url:'https://img.test/b',optionId:b.id},{id:'C',url:'https://img.test/c',optionId:null}];d.media.contents=[{id:'a',optionId:a.id},{id:'b',optionId:b.id}];d.markets.NAVER.overrides.imageIds=['A','B','C'];
 const copy=M.normalize(d);assert.equal(copy.options[0].id,a.id);M.removeOption(copy,a.id);assert.deepEqual(copy.media.images.map(i=>i.id),['B','C']);assert.equal(copy.markets.NAVER.overrides.options.length,1);assert.deepEqual(copy.markets.NAVER.overrides.imageIds,['B','C']);assert.equal(copy.options[0].id,b.id);assert.equal(M.removeOption(copy,b.id),false);
});
test('images maintain representative and ordering within their option owner',()=>{
 const d=M.fresh(),id=d.options[0].id;d.media.images=[{id:'common',representative:true,optionId:null,order:0},{id:'a',representative:true,optionId:id,order:0},{id:'b',representative:false,optionId:id,order:1}];
 M.setRepresentative(d,'b');assert(d.media.images[0].representative);assert(!d.media.images[1].representative);M.moveImage(d,'b',-1);assert.deepEqual(M.imagesFor(d,id).map(i=>i.id),['b','a']);M.removeImage(d,'b');assert(M.imagesFor(d,id)[0].representative);
});
test('product quantity and service choices are not summed as option stock',()=>{
 const d=M.fresh();d.stockMode='PRODUCT';d.productQuantity='9';d.options[0].price='0';d.options[0].quantity='';d.options[0].name='블랙';d.common.name='이름';d.common.productCode='CODE';d.services=[{id:M.uuid(),name:'선물포장',choices:['O','X']}];
 assert.equal(M.validate(d,true).length,0);assert.equal(d.productQuantity,'9');assert.equal(d.options[0].quantity,'');
});
test('normalization preserves inactive market fields and safe internal image references',()=>{
 const d=M.fresh();d.selectedMarkets=[];d.markets.NAVER.naver={notices:[{name:'소재',value:'가죽'}]};d.media.images=[{id:M.uuid(),assetId:M.uuid(),url:'/api/marketplaces/assets/test',optionId:null}];const copy=M.normalize(d);
 assert.equal(copy.markets.NAVER.naver.notices[0].value,'가죽');assert.deepEqual(copy.selectedMarkets,[]);assert.equal(M.validate(copy).length,0);d.media.images[0].assetId=null;assert.equal(M.validate(d).length,1);assert.equal(M.url('http://private.test/image'),null);
});

test('validation ignores inactive market overrides while preserving their input',()=>{const d=M.fresh(),id=d.options[0].id;M.toggle(d,'NAVER','price',true,id);M.optionOverride(d,'NAVER',id).price='-1';assert.deepEqual(M.validate(d),[]);d.selectedMarkets.push('NAVER');assert.equal(M.validate(d).length,1);d.selectedMarkets=['COUPANG'];assert.equal(M.optionOverride(d,'NAVER',id).price,'-1');});

test('LAN HTTP creates stable draft option and media identities without randomUUID',()=>{
 const vm=require('node:vm'),fs=require('node:fs');
 const context={MarketplaceChannels:globalThis.MarketplaceChannels,window:{},structuredClone,crypto:{getRandomValues:bytes=>require('node:crypto').webcrypto.getRandomValues(bytes)}};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/app-ui.js','utf8')+fs.readFileSync('molebutter-app/src/main/resources/static/js/common-marketplace-editor-model.js','utf8'),context);
 const model=context.window.MarketplaceEditorModel,draft=model.fresh(),first=draft.options[0].id;
 draft.options.push(model.blankOption());draft.media.images.push({id:model.uuid(),optionId:first});
 const ids=[...draft.options.map(option=>option.id),draft.media.images[0].id];
 assert.equal(new Set(ids).size,3);
 for(const id of ids)assert.match(id,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
 const normalized=model.normalize(draft);
 assert.equal(normalized.options[0].id,first);assert.equal(normalized.media.images[0].optionId,first);
});

function liveFixture(){
 const reference=M.fresh();reference.id='draft';reference.revision=2;reference.common.name='공통명';reference.options[0].name='블랙';reference.options[0].price='100000';reference.options[0].quantity='10';
 const current=structuredClone(reference);current.common.name='쿠팡명';current.options[0].price='110000';current.options[0].quantity='7';
 const session={id:'session',draftId:'draft',revision:2,expiresAt:'2099-01-01T00:00:00Z',targets:[{market:'COUPANG',mode:'UPDATE',status:'READY',observedAt:'2026-10-06T12:00:00Z',error:null,document:current}]};
 return {reference,current,session,state:M.sessionState(reference,session)};
}
test('live marketplace values remain independent of reference defaults and description-only changes',()=>{
 const {reference,state}=liveFixture(),entry=state.targets.get('COUPANG');
 assert.equal(entry.working.options[0].price,'110000');assert.equal(entry.working.options[0].quantity,'7');assert.equal(reference.options[0].price,'100000');assert.deepEqual(M.proposals(state,['COUPANG'])[0].changes,[]);
 entry.working.media.contents=[{id:'text',optionId:null,type:'HTML',value:'<p>이번 설명</p>'}];M.recordChange(entry,{path:'media.contents',optionId:null});
 assert.deepEqual(M.proposals(state,['COUPANG'])[0].changes.map(c=>c.path),['media.contents']);assert.equal(entry.working.options[0].quantity,'7');
});
test('explicit reference application selects only named fields and cancellation restores the live value',()=>{
 const {state}=liveFixture(),id=state.reference.options[0].id,change={path:'options.price',optionId:id};
 M.applyReference(state,'COUPANG',[change]);const entry=state.targets.get('COUPANG');assert.equal(entry.working.options[0].price,'100000');assert.equal(entry.working.options[0].quantity,'7');assert.equal(entry.changes.size,1);
 M.resetChange(entry,change);assert.equal(entry.working.options[0].price,'110000');assert.equal(entry.changes.size,0);
 entry.working.options[0].price='0';M.recordChange(entry,change);assert.equal([...entry.changes.values()][0].value,'0');assert.deepEqual(M.validateChanges(entry),[]);
});
test('unknown current stock cannot be replaced by reference stock, while zero remains confirmed',()=>{
 const {reference,session}=liveFixture(),id=reference.options[0].id;session.targets[0].document.options[0].quantity='';let state=M.sessionState(reference,session);
 assert.throws(()=>M.applyReference(state,'COUPANG',[{path:'common.name',optionId:null},{path:'options.quantity',optionId:id}]),/미확인/);assert.equal(state.targets.get('COUPANG').working.common.name,'쿠팡명');assert.equal(state.targets.get('COUPANG').changes.size,0);
 session.targets[0].document.options[0].quantity='0';state=M.sessionState(reference,session);M.applyReference(state,'COUPANG',[{path:'options.quantity',optionId:id}]);assert.equal(state.targets.get('COUPANG').working.options[0].quantity,'10');
});
test('refresh preserves proposals and requires review only when the same live field changed',()=>{
 const {reference,session,state}=liveFixture(),entry=state.targets.get('COUPANG'),id=reference.options[0].id,price={path:'options.price',optionId:id};entry.working.options[0].price='120000';M.recordChange(entry,price);
 const next=structuredClone(session);next.id='new-session';next.targets[0].document.options[0].quantity='6';let updated=M.sessionState(reference,next,state);assert.equal(updated.targets.get('COUPANG').working.options[0].price,'120000');assert.equal(updated.targets.get('COUPANG').working.options[0].quantity,'6');assert.equal(updated.targets.get('COUPANG').reviewRequired.size,0);
 next.targets[0].document.options[0].price='115000';updated=M.sessionState(reference,next,updated);assert.equal(updated.targets.get('COUPANG').reviewRequired.size,1);assert.equal(updated.targets.get('COUPANG').working.options[0].price,'120000');
 next.targets[0].document.options[0].price='120000';updated=M.sessionState(reference,next,updated);assert.equal(updated.targets.get('COUPANG').changes.size,0);assert.equal(updated.targets.get('COUPANG').reviewRequired.size,0);
});
test('failed refresh retains entered values without manufacturing a new confirmed baseline',()=>{
 const {reference,session,state}=liveFixture(),entry=state.targets.get('COUPANG'),id=reference.options[0].id;entry.working.options[0].quantity='5';M.recordChange(entry,{path:'options.quantity',optionId:id});session.targets[0].status='FAILED';session.targets[0].document=null;
 const next=M.sessionState(reference,session,state).targets.get('COUPANG');assert.equal(next.working.options[0].quantity,'5');assert.equal(next.baseline,null);assert.equal(next.changes.size,1);
});
test('patches use stable option identities despite response reordering and preserve HTML/image ownership',()=>{
 const {reference,session,state}=liveFixture(),entry=state.targets.get('COUPANG'),id=reference.options[0].id;const second=M.blankOption();second.name='화이트';reference.options.push(second);entry.working.options.push(structuredClone(second));entry.baseline.options.push(structuredClone(second));entry.working.options.reverse();
 const change=M.changeForPath(entry.working,'options.1.quantity');assert.equal(change.optionId,id);entry.working.options[1].quantity='0';M.recordChange(entry,change);assert.equal([...entry.changes.values()][0].optionId,id);
 const html='<img src="https://images.test/a"><script>parent.bad()</script>';entry.working.media.contents=[{id:'A',optionId:id,type:'HTML',value:html},{id:'B',optionId:second.id,type:'HTML',value:'화이트'}];M.recordChange(entry,{path:'media.contents',optionId:id});const content=[...entry.changes.values()].find(c=>c.path==='media.contents');assert.equal(content.value.length,1);assert.equal(content.value[0].value,html);M.resetChange(entry,content);assert.equal(entry.working.media.contents.length,1);assert.equal(entry.working.media.contents[0].id,'B');
});
test('numeric formatting alone is a no-op, and edits of missing mapped options are rejected',()=>{
 assert(M.equal('0007',7,'options.quantity'));assert(!M.equal('',0,'options.quantity'));assert(!M.equal(null,0,'options.quantity'));
 const {reference,state}=liveFixture();assert.throws(()=>M.applyReference(state,'COUPANG',[{path:'options.price',optionId:'missing'}]),/옵션|미확인/);assert.equal(state.targets.get('COUPANG').changes.size,0);
});


test('display name is a separate explicit patch and common application resolves its reference default',()=>{
 const {reference,state}=liveFixture(),entry=state.targets.get('COUPANG');state.reference.common.name='기준 상품명';state.reference.common.productName='기준 제품명';entry.baseline.common.productName='실제 제품명';entry.working.common.productName='실제 제품명';entry.baseline.markets.COUPANG.overrides.productName='실제 노출명';entry.working.markets.COUPANG.overrides.productName='실제 노출명';
 const change={path:'markets.COUPANG.overrides.productName',optionId:null};M.applyReference(state,'COUPANG',[change]);assert.equal(entry.working.markets.COUPANG.overrides.productName,'기준 상품명');assert.equal(entry.working.common.productName,'실제 제품명');assert.deepEqual(M.proposals(state,['COUPANG'])[0].changes.map(c=>c.path),['markets.COUPANG.overrides.productName']);
 M.resetChange(entry,change);assert.equal(entry.working.markets.COUPANG.overrides.productName,'실제 노출명');
});

test('new Coupang target projects common fields, brand ID and stable options without sharing mutable input',()=>{
 const reference=M.fresh(),option=reference.options[0];Object.assign(reference.common,{productCode:'INTERNAL',name:'등록상품명',productName:'제품명',brand:'브랜드'});Object.assign(option,{name:'블랙',sku:'SELLER-SKU',price:'0',quantity:'0',attributes:[{name:'색상',value:'블랙'}]});
 reference.markets.COUPANG.coupang={sellerProductId:null,settings:[{name:'brandId',value:'KR-123'}],delivery:[],documents:[],options:[],source:null};
 const target=M.createTarget(reference,'COUPANG'),d=target.working;
 assert.equal(d.markets.COUPANG.overrides.productName,'등록상품명');assert.equal(d.common.name,'등록상품명');assert.equal(d.common.productName,'제품명');assert.equal(d.common.productCode,'INTERNAL');assert.equal(d.common.brand,'브랜드');assert.equal(d.markets.COUPANG.coupang.settings[0].value,'KR-123');
 assert.equal(d.options[0].id,option.id);assert.equal(d.options[0].sku,'SELLER-SKU');assert.equal(d.options[0].price,'0');assert.equal(d.options[0].quantity,'0');assert.equal(d.markets.COUPANG.coupang.options[0].optionId,option.id);assert.equal(d.markets.COUPANG.coupang.options[0].attributes[0].value,'블랙');
 d.common.brand='수정';d.markets.COUPANG.coupang.settings[0].value='KR-OTHER';assert.equal(reference.common.brand,'브랜드');assert.equal(reference.markets.COUPANG.coupang.settings[0].value,'KR-123');assert.equal(reference.markets.COUPANG.coupang.options.length,0);
});
test('new target uses configured marketplace overrides including explicit blank, zero and selected media',()=>{
 const reference=M.fresh(),id=reference.options[0].id;Object.assign(reference.common,{name:'기준명',brand:'기준브랜드'});reference.options[0].price='99';reference.options[0].quantity='5';
 Object.assign(reference.markets.COUPANG.overrides,{name:'',brand:'별도브랜드',productName:'노출상품명',imageIds:['image-B'],description:''});reference.markets.COUPANG.overrides.options=[{optionId:id,sku:'별도SKU',price:'0',quantity:'0'}];reference.media.images=[{id:'image-A',optionId:null},{id:'image-B',optionId:id}];
 const d=M.newMarketDocument(reference,'COUPANG');assert.equal(d.common.name,'');assert.equal(d.common.brand,'별도브랜드');assert.equal(d.options[0].price,'0');assert.equal(d.options[0].quantity,'0');assert.equal(d.options[0].sku,'별도SKU');assert.equal(d.markets.COUPANG.overrides.productName,'노출상품명');assert.equal(d.markets.COUPANG.overrides.name,null);assert.deepEqual(d.media.images.map(i=>i.id),['image-B']);assert.equal(d.media.contents[0].value,'');
 const target=M.createTarget(reference,'COUPANG'),next=M.createTarget(reference,'COUPANG',target);assert.equal(next.working.media.contents[0].id,target.working.media.contents[0].id);
});
test('new targets inherit changed common input while marketplace edits retain explicit blank and zero',()=>{
 const reference=M.fresh(),id=reference.options[0].id;reference.common.name='이전명';Object.assign(reference.options[0],{price:'100',quantity:'5'});const initial=M.createTarget(reference,'COUPANG');
 initial.working.options[0].price='0';M.recordChange(initial,{path:'options.price',optionId:id});initial.working.common.name='';M.recordChange(initial,{path:'common.name',optionId:null});
 reference.common.name='새 기준명';Object.assign(reference.options[0],{price:'200',quantity:'9'});let target=M.createTarget(reference,'COUPANG',initial);assert.equal(target.working.common.name,'');assert.equal(target.working.options[0].price,'0');assert.equal(target.working.options[0].quantity,'9');
 // Matching a later reference value must not silently remove an explicit edit.
 reference.options[0].price='0';target=M.createTarget(reference,'COUPANG',target);assert.equal(target.changes.size,1);reference.options[0].price='300';target=M.createTarget(reference,'COUPANG',target);assert.equal(target.working.options[0].price,'0');
 M.resetChange(target,{path:'options.price',optionId:id});reference.options[0].price='400';target=M.createTarget(reference,'COUPANG',target);assert.equal(target.working.options[0].price,'400');
});
test('reference syncing modifies only ready CREATE targets and preserves observed UPDATE values',()=>{
 const {reference,state}=liveFixture(),linked=state.targets.get('COUPANG');state.targets.set('NAVER',M.createTarget(reference,'NAVER'));state.targets.set('GMARKET',{market:'GMARKET',mode:'CREATE',status:'UNSUPPORTED',working:structuredClone(reference)});
 reference.common.name='수정한 공통명';reference.options[0].quantity='0';M.syncCreateTargets(state,reference);
 assert.equal(state.targets.get('COUPANG'),linked);assert.equal(linked.working.common.name,'쿠팡명');assert.equal(linked.working.options[0].quantity,'7');assert.equal(state.targets.get('NAVER').working.common.name,'수정한 공통명');assert.equal(state.targets.get('NAVER').working.options[0].quantity,'0');assert.equal(state.targets.get('GMARKET').working.common.name,'공통명');
});
test('new target syncing uses UUID ownership when options reorder, add or disappear',()=>{
 const reference=M.fresh(),a=reference.options[0],b=M.blankOption();a.name='A';b.name='B';reference.options.push(b);let target=M.createTarget(reference,'COUPANG');target.working.options[1].sku='B-custom';M.recordChange(target,{path:'options.sku',optionId:b.id});
 reference.options.reverse();const c=M.blankOption();c.name='C';reference.options.push(c);target=M.createTarget(reference,'COUPANG',target);assert.deepEqual(target.working.options.map(o=>o.id),[b.id,a.id,c.id]);assert.equal(target.working.options[0].sku,'B-custom');assert.deepEqual(target.working.markets.COUPANG.coupang.options.map(o=>o.optionId),[b.id,a.id,c.id]);
 M.removeOption(reference,b.id);target=M.createTarget(reference,'COUPANG',target);assert.equal(target.changes.size,0);assert.equal(target.createOverrides.size,0);assert.deepEqual(target.working.options.map(o=>o.id),[a.id,c.id]);
});
test('category metadata keeps search attributes while common purchase attributes use names',()=>{
 const reference=M.fresh(),option=reference.options[0];option.attributes=[{name:'색상',value:'공통 블랙'}];reference.markets.COUPANG.coupang={settings:[],options:[{optionId:option.id,attributes:[{name:'소재',value:'가죽',exposed:'NONE'},{name:'색상',value:'이전값',exposed:'EXPOSED'}],registration:[],notices:[],certifications:[]}],documents:[],delivery:[]};
 let target=M.createTarget(reference,'COUPANG');assert.deepEqual(target.working.markets.COUPANG.coupang.options[0].attributes.map(a=>a.value),['가죽','공통 블랙']);target.working.markets.COUPANG.coupang.options[0].attributes[1].value='별도 브라운';M.recordChange(target,{path:'markets.COUPANG.coupang.options.attributes',optionId:option.id});reference.options[0].attributes[0].value='공통 화이트';target=M.createTarget(reference,'COUPANG',target);assert.equal(target.working.markets.COUPANG.coupang.options[0].attributes[1].value,'별도 브라운');
});
test('server CREATE session installation preserves unsaved marketplace edits for later reference syncing',()=>{
 const reference=M.fresh(),id=reference.options[0].id;reference.options[0].price='100';let local=M.createTarget(reference,'COUPANG');local.working.options[0].price='0';M.recordChange(local,{path:'options.price',optionId:id});
 const previous={targets:new Map([['COUPANG',local]])},session={id:'server',targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(reference,'COUPANG')}]};let state=M.sessionState(reference,session,previous);assert.equal(state.targets.get('COUPANG').working.options[0].price,'0');
 reference.options[0].price='0';M.syncCreateTargets(state,reference);state=M.sessionState(reference,{...session,targets:[{...session.targets[0],document:M.newMarketDocument(reference,'COUPANG')}]},state);reference.options[0].price='200';M.syncCreateTargets(state,reference);assert.equal(state.targets.get('COUPANG').working.options[0].price,'0');assert.equal(state.targets.get('COUPANG').changes.size,1);
});
test('a linked Coupang document cannot be converted into a local CREATE target',()=>{
 const reference=M.fresh();reference.markets.COUPANG.coupang={sellerProductId:'123',options:[],settings:[]};assert.throws(()=>M.createTarget(reference,'COUPANG'),/최신 값/);
 reference.markets.COUPANG.coupang={options:[{optionId:reference.options[0].id,vendorItemId:'456'}]};assert.throws(()=>M.newMarketDocument(reference,'COUPANG'),/최신 값/);
});

test('common brand search stores each market identity and exact selected name independently',()=>{
 const d=M.fresh();d.common.brand='닥스';d.markets.COUPANG.coupang={settings:[{name:'unitCount',value:'2'},{name:'brandId',value:'KR-OLD'},{name:'brandId',value:'KR-DUPLICATE'}],delivery:[],options:[],documents:[]};d.markets.NAVER.naver={attributes:[{name:'재질',value:'가죽'}],editorInput:{fields:{native:'unchanged'}}};
 M.applyBrandSelections(d,{COUPANG:{id:'KR-41013',name:'DAKS'},NAVER:{id:'9007199254740993',name:'닥스(DAKS)'},LOTTEON:{id:'000000123456',name:'닥스'}});
 assert.equal(d.common.brand,'닥스');assert.deepEqual(M.brandSelection(d,'COUPANG'),{id:'KR-41013',name:'DAKS'});assert.deepEqual(M.brandSelection(d,'NAVER'),{id:'9007199254740993',name:'닥스(DAKS)'});assert.deepEqual(M.brandSelection(d,'LOTTEON'),{id:'000000123456',name:'닥스'});
 assert.deepEqual(d.markets.COUPANG.coupang.settings,[{name:'unitCount',value:'2'},{name:'brandId',value:'KR-41013'}]);assert.deepEqual(d.markets.NAVER.naver.attributes,[{name:'재질',value:'가죽'}]);assert.deepEqual(d.markets.NAVER.naver.editorInput,{fields:{native:'unchanged'}});
 assert(!d.selectedMarkets.includes('LOTTEON'));assert(M.markets.some(([market])=>market==='LOTTEON'));assert.equal(M.brandSelection(d,'GMARKET'),null);assert.deepEqual(M.brandSelection(d,'ELEVENST'),{id:null,name:d.common.brand});
 const normalized=M.normalize(JSON.parse(JSON.stringify(d)));assert.deepEqual(M.brandSelection(normalized,'NAVER'),{id:'9007199254740993',name:'닥스(DAKS)'});assert.deepEqual(M.brandSelection(normalized,'LOTTEON'),{id:'000000123456',name:'닥스'});
});
test('market selection removal restores common brand inheritance without touching other selected brands',()=>{
 const d=M.fresh();d.common.brand='공통 닥스';M.applyBrandSelections(d,{COUPANG:{id:'KR-1',name:'쿠팡 닥스'},NAVER:{id:'42',name:'네이버 닥스'},LOTTEON:{id:'0001',name:'롯데 닥스'}});
 M.applyBrandSelections(d,{NAVER:null});assert.deepEqual(M.brandSelection(d,'NAVER'),{id:null,name:'공통 닥스'});assert.equal(d.markets.NAVER.overrides.brand,null);assert.deepEqual(M.brandSelection(d,'COUPANG'),{id:'KR-1',name:'쿠팡 닥스'});
 M.applyBrandSelections(d,{COUPANG:null,LOTTEON:null});assert.equal(d.markets.COUPANG.coupang.settings.length,0);assert.deepEqual(M.brandSelection(d,'LOTTEON'),{id:null,name:'공통 닥스'});assert.equal(d.markets.LOTTEON.overrides.brand,null);
 M.setCommonBrand(d,'새 공통명');assert.equal(M.brandSelection(d,'COUPANG').name,'새 공통명');assert.equal(M.brandSelection(d,'NAVER').name,'새 공통명');assert.equal(M.brandSelection(d,'LOTTEON').name,'새 공통명');
});
test('LotteON selected names retain the catalog 255-character boundary',()=>{
 const d=M.fresh(),name='가'.repeat(255);M.applyBrandSelections(d,{LOTTEON:{id:'0000012',name}});assert.equal(M.brandSelection(M.normalize(JSON.parse(JSON.stringify(d))),'LOTTEON').name,name);
 const before=structuredClone(d);assert.throws(()=>M.applyBrandSelections(d,{LOTTEON:{id:'0000012',name:name+'가'}}),/브랜드/);assert.deepEqual(d,before);
});
test('changing common brand preserves explicit marketplace choices but clears inherited legacy IDs',()=>{
 const d=M.fresh();d.common.brand='원래 공통명';d.markets.COUPANG.coupang={settings:[{name:'brandId',value:'KR-LEGACY'},{name:'unitCount',value:'1'}]};d.markets.NAVER.naver={brandId:'123',status:'SALE'};
 M.setCommonBrand(d,'원래 공통명');assert.equal(M.brandSelection(d,'COUPANG').id,'KR-LEGACY');M.setCommonBrand(d,'새 공통명');assert.equal(M.brandSelection(d,'COUPANG').id,null);assert.equal(M.brandSelection(d,'NAVER').id,null);assert.equal(d.markets.NAVER.naver.status,'SALE');assert.equal(d.markets.COUPANG.coupang.settings[0].name,'unitCount');
 M.applyBrandSelections(d,{COUPANG:{id:'KR-5',name:'쿠팡 선택명'},NAVER:{id:'456',name:'네이버 선택명'},LOTTEON:{id:'0002',name:'롯데 선택명'}});M.setCommonBrand(d,'다른 검색어');assert.deepEqual(M.brandSelection(d,'COUPANG'),{id:'KR-5',name:'쿠팡 선택명'});assert.deepEqual(M.brandSelection(d,'NAVER'),{id:'456',name:'네이버 선택명'});assert.deepEqual(M.brandSelection(d,'LOTTEON'),{id:'0002',name:'롯데 선택명'});
});
test('brand selection rejects unsupported or invalid entries atomically and never invents market codes',()=>{
 const d=M.fresh();d.common.brand='원래';const before=structuredClone(d);
 for(const selections of [{COUPANG:{id:'KR-5',name:'선택'},GMARKET:{id:'5',name:'임의 코드'}},{NAVER:{id:9007199254740993,name:'정밀도 유실'}},{NAVER:{id:'9223372036854775808',name:'범위 초과'}},{NAVER:{id:'0',name:'영'}},{COUPANG:{id:'KR-5',name:''}},{LOTTEON:{id:'a\nb',name:'잘못된 코드'}},{LOTTEON:{id:'1234567890123',name:'긴 코드'}}]){assert.throws(()=>M.applyBrandSelections(d,selections),/브랜드|마켓/);assert.deepEqual(d,before);}
 assert.equal(d.markets.LOTTEON.lotteon,null);assert.deepEqual(M.brandSelection(d,'ELEVENST'),{id:null,name:d.common.brand});assert.equal(M.brandSelection(d,'COUPANG').id,null);
});
test('linked Coupang brand is locked while independent new-market selections remain editable',()=>{
 const d=M.fresh();d.common.brand='기존';d.markets.COUPANG.coupang={sellerProductId:'123',settings:[{name:'brandId',value:'KR-LOCKED'}]};assert(M.brandLocked(d,'COUPANG'));assert(!M.brandLocked(d,'NAVER'));
 const before=structuredClone(d);assert.throws(()=>M.applyBrandSelections(d,{NAVER:{id:'42',name:'선택'},COUPANG:null}),/기존 쿠팡/);assert.deepEqual(d,before);assert.throws(()=>M.applyBrandSelections(d,{COUPANG:{id:'KR-5',name:'다른 브랜드'}}),/기존 쿠팡/);
 M.setCommonBrand(d,'다른 공통명');assert.equal(M.brandSelection(d,'COUPANG').id,'KR-LOCKED');M.applyBrandSelections(d,{NAVER:{id:'42',name:'새 네이버 브랜드'}});assert.equal(M.brandSelection(d,'NAVER').name,'새 네이버 브랜드');assert.equal(d.markets.COUPANG.coupang.settings[0].value,'KR-LOCKED');
});
test('brand selections project into CREATE targets while observed UPDATE and individual edits stay independent',()=>{
 const {reference,state}=liveFixture(),observed=state.targets.get('COUPANG');M.applyBrandSelections(reference,{COUPANG:{id:'KR-5',name:'쿠팡 선택명'},NAVER:{id:'123',name:'네이버 선택명'}});state.targets.set('NAVER',M.createTarget(reference,'NAVER'));M.syncCreateTargets(state,reference);assert.equal(state.targets.get('COUPANG'),observed);assert.equal(observed.working.common.brand,'');assert.equal(state.targets.get('NAVER').working.markets.NAVER.naver.editorInput.fields['originProduct.detailAttribute.naverShoppingSearchInfo.brandName'],'네이버 선택명');
 let target=M.createTarget(reference,'COUPANG');assert.equal(target.working.common.brand,'쿠팡 선택명');assert.equal(target.working.markets.COUPANG.coupang.settings.find(f=>f.name==='brandId').value,'KR-5');target.working.options[0].price='0';M.recordChange(target,{path:'options.price',optionId:reference.options[0].id});M.setCommonBrand(reference,'새 검색어');target=M.createTarget(reference,'COUPANG',target);assert.equal(target.working.options[0].price,'0');assert.equal(target.working.common.brand,'쿠팡 선택명');
});

test('market brand survives rebuilding and draft persistence without modifying reference or another market',()=>{
 const reference=M.fresh();reference.common.brand='공통';M.applyBrandSelections(reference,{COUPANG:{id:'A',name:'쿠팡 기준'},NAVER:{id:'12',name:'네이버'}});
 let target=M.createTarget(reference,'COUPANG');M.applyBrandSelections(target.working,{COUPANG:{id:'B',name:'개별'}});target.working.common.brand='개별';
 for(const path of ['common.brand','markets.COUPANG.coupang.settings'])M.recordChange(target,{path,optionId:null});
 for(let i=0;i<4;i++)target=M.createTarget(reference,'COUPANG',target);
 assert.equal(target.working.common.brand,'개별');assert.deepEqual(M.brandSelection(target.working,'COUPANG'),{id:'B',name:'개별'});assert.equal(M.brandSelection(reference,'COUPANG').id,'A');assert.equal(M.brandSelection(reference,'NAVER').id,'12');
 const saved=structuredClone(reference);const restored=M.sessionState(saved,{targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(saved,'COUPANG'),pendingChanges:[...target.createOverrides.values()]}]});M.syncCreateTargets(restored,saved);const reopened=restored.targets.get('COUPANG');assert.equal(reopened.working.common.brand,'개별');assert.equal(M.brandSelection(reopened.working,'COUPANG').id,'B');assert.equal(saved.common.brand,'공통');
});
test('brand comparison groups ID and name and reset preserves other settings while following latest baseline',()=>{
 const reference=M.fresh();reference.common.brand='기준';M.applyBrandSelections(reference,{COUPANG:{id:'A',name:'기준'}});let t=M.createTarget(reference,'COUPANG');
 t.working.common.brand='개별';t.working.markets.COUPANG.coupang.settings=[{name:'brandId',value:'B'},{name:'manufacture',value:'별도 제조사'}];for(const path of ['common.brand','markets.COUPANG.coupang.settings'])M.recordChange(t,{path,optionId:null});
 M.applyBrandSelections(reference,{COUPANG:{id:'C',name:'최신 기준'}});t=M.createTarget(reference,'COUPANG',t);
 const rows=M.comparisonRows(t);assert.equal(rows.length,2);assert.deepEqual(rows.find(r=>r.brand).before,{이름:'최신 기준',ID:'C'});M.resetComparison(t,rows.find(r=>r.brand));assert.equal(t.working.common.brand,'최신 기준');assert.equal(M.brandSelection(t.working,'COUPANG').id,'C');assert.equal(t.working.markets.COUPANG.coupang.settings.find(v=>v.name==='manufacture').value,'별도 제조사');assert.equal(M.comparisonRows(t).length,1);
});
test('comparison preserves explicit zero and blank values',()=>{
 const d=M.fresh();d.options[0].price='10';d.common.name='기준';const t=M.createTarget(d,'COUPANG');t.working.options[0].price='0';t.working.common.name='';M.recordChange(t,{path:'options.price',optionId:d.options[0].id});M.recordChange(t,{path:'common.name',optionId:null});const rows=M.comparisonRows(t);assert.equal(rows[0].after,'0');assert.equal(rows[1].after,'');
});
test('each Coupang setting has an independent decision key including start and end dates',()=>{
 const d=M.fresh(),t=M.createTarget(d,'COUPANG'),path='markets.COUPANG.coupang.settings';t.mode='UPDATE';t.working.markets.COUPANG.coupang.settings=[{name:'saleStartedAt',value:'2026-10-13T18:32:58'},{name:'saleEndedAt',value:'2099-12-31T23:59:00'}];M.recordChange(t,{path,optionId:null});const rows=M.comparisonRows(t);assert.equal(rows.length,2);assert.notEqual(rows[0].keys[0],rows[1].keys[0]);for(const row of rows)t.reviewRequired.set(row.keys[0],{});M.resetComparison(t,rows[0]);assert(!t.reviewRequired.has(rows[0].keys[0]));assert(t.reviewRequired.has(rows[1].keys[0]));assert.equal(M.comparisonRows(t).length,1);
});
test('server resolution preserves unselected unsaved fields and other markets',()=>{
 const {reference,state,session}=liveFixture(),t=state.targets.get('COUPANG'),id=reference.options[0].id;
 for(const [path,value]of [['options.price','123000'],['options.quantity','0']]){M.writeChange(t.working,{path,optionId:id,value});M.recordChange(t,{path,optionId:id});}
 const next=structuredClone(session);next.revision++;next.targets[0].pendingChanges=[];const keep=M.afterResolution(state,next,'COUPANG',[{path:'options.price',optionId:id}],true),restored=M.sessionState(reference,next,keep).targets.get('COUPANG');assert.equal(restored.working.options[0].price,'110000');assert.equal(restored.working.options[0].quantity,'0');assert.equal(restored.changes.size,1);
 const failed=M.afterResolution(state,next,'COUPANG',[{path:'options.price',optionId:id}],false);assert.equal(failed.targets.get('COUPANG').changes.size,2);
});
test('SmartStore brand name and ID display and reset as one pair while preserving other edits',()=>{
 const N=require('../../../molebutter-app/src/main/resources/static/js/common-naver-editor-model.js'),prefix=N.prefix+'fields.originProduct.detailAttribute.naverShoppingSearchInfo.';
 const d=M.fresh('NAVER');d.common.brand='공통';d.markets.NAVER.naver={brandId:'39'};const t=M.createTarget(d,'NAVER'),before=structuredClone(t.working.markets.NAVER.naver.editorInput),after=structuredClone(before);after.fields['originProduct.detailAttribute.naverShoppingSearchInfo.brandName']='닥스';after.fields['originProduct.detailAttribute.naverShoppingSearchInfo.brandId']='1217';after.fields['originProduct.name']='별도 상품명';N.edited(t.working,before,after);for(const c of N.changes(before,after))M.recordChange(t,c);
 const rows=M.comparisonRows(t),brand=rows.find(r=>r.naverBrand);assert.equal(rows.length,2);assert.equal(brand.keys.length,2);assert.deepEqual(brand.after,{이름:'닥스',ID:'1217'});M.resetComparison(t,brand);assert.equal(M.changeValue(t.working,{path:prefix+'brandName'}),'공통');assert.equal(M.changeValue(t.working,{path:prefix+'brandId'}),'39');assert.equal(M.comparisonRows(t).length,1);assert.equal(t.working.markets.NAVER.naver.editorInput.fields['originProduct.name'],'별도 상품명');assert(!t.working.markets.NAVER.naver.editorState.overrides.some(key=>key.includes('naverShoppingSearchInfo.brand')));
});
test('all Coupang edits and explicit equal-to-common overrides survive serialized pending restore',()=>{
 let reference=M.fresh(),t=M.createTarget(reference,'COUPANG'),id=reference.options[0].id;
 const edits=[{path:'common.name',value:'개별 이름'},{path:'common.productName',value:'제품명'},{path:'common.manufacturer',value:''},{path:'markets.COUPANG.categoryCode',value:'123'},{path:'delivery.charge',value:'0'},{path:'options.quantity',optionId:id,value:'0'},{path:'markets.COUPANG.coupang.options.notices',optionId:id,value:[{category:'기타',name:'품명',content:'내용'}]},{path:'markets.COUPANG.coupang.options.certifications',optionId:id,value:[{type:'인증',code:'번호',attachments:[]}]},{path:'markets.COUPANG.coupang.documents',value:[{templateName:'서류',path:'https://test.test/a'}]},{path:'media.contents',optionId:id,value:[{id:'content',optionId:id,type:'HTML',value:'<p>개별</p>'}]},{path:'media.images',optionId:id,value:[{id:'image',optionId:id,url:'https://test.test/a.png',representative:true,order:0}]}];
 for(const c of edits){M.writeChange(t.working,c);M.recordChange(t,c);}reference.common.name='개별 이름';t=M.createTarget(reference,'COUPANG',t);assert(!t.changes.has(M.changeKey(edits[0])));assert(t.createOverrides.has(M.changeKey(edits[0])));
 const session=JSON.parse(JSON.stringify({targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(reference,'COUPANG'),pendingChanges:[...t.createOverrides.values()]}]}));
 let state=M.sessionState(reference,session);reference.common.name='다시 바뀐 공통';reference.common.productName='새 공통 제품';M.syncCreateTargets(state,reference);t=state.targets.get('COUPANG');for(const c of edits)assert.deepEqual(M.changeValue(t.working,c),c.value);
 M.resetChange(t,edits[0]);const refreshed=M.sessionState(reference,session,state);assert.equal(refreshed.targets.get('COUPANG').working.common.name,'개별 이름');M.syncCreateTargets(refreshed,reference);assert.equal(refreshed.targets.get('COUPANG').working.common.name,'다시 바뀐 공통');
});
test('Coupang setting inheritance is tracked per field across restore',()=>{
 const d=M.fresh();d.markets.COUPANG.coupang={settings:[{name:'brandId',value:'A'},{name:'manufacture',value:'공통 제조'}]};let t=M.createTarget(d,'COUPANG');t.working.markets.COUPANG.coupang.settings[0].value='B';M.recordChange(t,{path:'markets.COUPANG.coupang.settings',optionId:null});assert.deepEqual([...t.createOverrides.values()].map(c=>c.path),['markets.COUPANG.coupang.settings.brandId']);
 const state=M.sessionState(d,{targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(d,'COUPANG'),pendingChanges:[...t.createOverrides.values()]}]});d.markets.COUPANG.coupang.settings[1].value='새 제조';M.syncCreateTargets(state,d);t=state.targets.get('COUPANG');assert.equal(t.working.markets.COUPANG.coupang.settings[1].value,'새 제조');assert.equal(t.working.markets.COUPANG.coupang.settings[0].value,'B');M.resetComparison(t,M.comparisonRows(t)[0]);assert.equal(t.createOverrides.size,0);
});

test('Coupang inherits common name into both names while independent display edits survive rebuilding',()=>{
 const reference=M.fresh();reference.common.name='공통 상품명';reference.common.productName='별도 제품명';
 let target=M.createTarget(reference,'COUPANG');assert.equal(target.working.common.name,'공통 상품명');assert.equal(target.working.markets.COUPANG.overrides.productName,'공통 상품명');
 reference.common.name='새 공통 상품명';target=M.createTarget(reference,'COUPANG',target);assert.equal(target.working.common.name,'새 공통 상품명');assert.equal(target.working.markets.COUPANG.overrides.productName,'새 공통 상품명');
 const change={path:'markets.COUPANG.overrides.productName',optionId:null};target.working.markets.COUPANG.overrides.productName='개별 노출명';M.recordChange(target,change);
 reference.common.name='다음 공통명';target=M.createTarget(reference,'COUPANG',target);assert.equal(target.working.common.name,'다음 공통명');assert.equal(target.working.markets.COUPANG.overrides.productName,'개별 노출명');
 M.resetChange(target,change);assert.equal(target.working.markets.COUPANG.overrides.productName,'다음 공통명');assert.equal(target.working.common.productName,'별도 제품명');
});

test('Coupang seller SKU inherits only common option SKU until explicitly edited and survives saved session restoration',()=>{
 const d=M.fresh(),id=d.options[0].id;d.common.productCode='COMMON-A';let t=M.createTarget(d,'COUPANG');assert.equal(t.working.options[0].sku,'');assert.equal(d.options[0].sku,'');
 d.common.productCode='COMMON-B';d.options[0].sku='OPTION-B';t=M.createTarget(d,'COUPANG',t);assert.equal(t.working.options[0].sku,'OPTION-B');
 const change={path:'options.sku',optionId:id};t.working.options[0].sku='CUSTOM';M.recordChange(t,change);d.common.productCode='COMMON-C';t=M.createTarget(d,'COUPANG',t);assert.equal(t.working.options[0].sku,'CUSTOM');
 const session=JSON.parse(JSON.stringify({targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(d,'COUPANG'),pendingChanges:[...t.createOverrides.values()]}]}));
 const state=M.sessionState(d,session);d.common.productCode='COMMON-D';d.options[0].sku='OPTION-D';M.syncCreateTargets(state,d);t=state.targets.get('COUPANG');assert.equal(t.working.options[0].sku,'CUSTOM');
 M.resetChange(t,change);assert.equal(t.working.options[0].sku,'OPTION-D');t.working.options[0].sku='';M.recordChange(t,change);d.common.productCode='COMMON-E';t=M.createTarget(d,'COUPANG',t);assert.equal(t.working.options[0].sku,'');
 const next=M.blankOption();d.options.push(next);t=M.createTarget(d,'COUPANG',t);assert.equal(t.working.options.find(o=>o.id===next.id).sku,'');
});

test('common category choices inherit separately and explicit category survives tab rebuild and saved intent',()=>{
 const d=M.fresh();M.applyCategorySelections(d,{COUPANG:{id:'123'},NAVER:{id:'50001'}});let t=M.createTarget(d,'COUPANG');assert.equal(t.working.markets.COUPANG.categoryCode,'123');
 t.working.markets.COUPANG.categoryCode='456';M.recordChange(t,{path:'markets.COUPANG.categoryCode',optionId:null});M.applyCategorySelections(d,{COUPANG:{id:'789'}});t=M.createTarget(d,'COUPANG',t);assert.equal(t.working.markets.COUPANG.categoryCode,'456');assert.equal(d.markets.NAVER.categoryCode,'50001');
 const state=M.sessionState(d,JSON.parse(JSON.stringify({targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(d,'COUPANG'),pendingChanges:[...t.createOverrides.values()]}]})));M.syncCreateTargets(state,d);t=state.targets.get('COUPANG');assert.equal(t.working.markets.COUPANG.categoryCode,'456');M.resetChange(t,{path:'markets.COUPANG.categoryCode',optionId:null});assert.equal(t.working.markets.COUPANG.categoryCode,'789');
 assert.throws(()=>M.applyCategorySelections(d,{COUPANG:{id:'../123'}}));
});
test('purchase values and group selections follow option UUID across reorder save resume and reset',()=>{
 const reference=M.fresh(),a=reference.options[0],b=M.blankOption();reference.options.push(b);
 a.attributes=[{name:'색상',value:'블랙'},{name:'용량',value:'1ml'}];b.attributes=[{name:'색상',value:'화이트'},{name:'용량',value:'2ml'}];
 let target=M.createTarget(reference,'COUPANG');
 const path='markets.COUPANG.coupang.options.attributes';
 const row=target.working.markets.COUPANG.coupang.options.find(o=>o.optionId===b.id);
 row.attributes=[{name:'색상',value:'브라운',exposed:'EXPOSED'},{name:'중량',value:'0g',exposed:'EXPOSED'}];M.recordChange(target,{path,optionId:b.id});
 reference.options.reverse();reference.options.find(o=>o.id===a.id).attributes[0].value='그린';
 target=M.createTarget(reference,'COUPANG',target);
 const values=t=>Object.fromEntries(t.working.markets.COUPANG.coupang.options.map(o=>[o.optionId,o.attributes]));
 assert.equal(values(target)[a.id][0].value,'그린');assert.deepEqual(values(target)[b.id],row.attributes);
 // JSON round trip models the persisted pending edit contract, with a freshly generated baseline.
 const pendingChanges=JSON.parse(JSON.stringify([...target.createOverrides.values()]));
 const session={id:'session',targets:[{market:'COUPANG',mode:'CREATE',status:'READY',document:M.newMarketDocument(reference,'COUPANG'),pendingChanges}]};
 target=M.sessionState(JSON.parse(JSON.stringify(reference)),session).targets.get('COUPANG');
 target=M.createTarget(reference,'COUPANG',target);
 assert.equal(values(target)[a.id][0].value,'그린');assert.deepEqual(values(target)[b.id],row.attributes);
 M.resetChange(target,{path,optionId:b.id});target=M.createTarget(reference,'COUPANG',target);
 assert.equal(values(target)[b.id][0].value,'화이트');assert.equal(values(target)[b.id][1].name,'용량');assert.equal(target.createOverrides.size,0);
});
test('common normal price maps to Coupang registration and explicit market price survives resync and serialization',()=>{
 const d=M.fresh(),o=d.options[0];o.originalPrice='128000';o.price='99000';
 let t=M.createTarget(d,'COUPANG');const path='markets.COUPANG.coupang.options.registration.originalPrice',c={path,optionId:o.id};assert.equal(M.changeValue(t.working,c),'128000');assert.equal(t.working.options[0].price,'99000');
 M.writeChange(t.working,{...c,value:'140000'});M.recordChange(t,c);d.options[0].originalPrice='150000';d.options[0].price='100000';t=M.createTarget(JSON.parse(JSON.stringify(d)),'COUPANG',t);assert.equal(M.changeValue(t.working,c),'140000');assert.equal(t.working.options[0].price,'100000');
 M.resetChange(t,c);assert.equal(M.changeValue(t.working,c),'150000');assert.equal(M.normalize(JSON.parse(JSON.stringify(d))).options[0].originalPrice,'150000');
 assert(M.validate({...d,options:[{...o,originalPrice:'-1'}]}).some(e=>e.path.endsWith('originalPrice')));
});

test('market address reference choices persist, inherit and do not replace explicit per-market edits',()=>{
 const address=(code,name)=>({code,name,zipCode:'12345',address:'서울',detail:'1층',phone:'01012345678'}),d=M.fresh();
 M.applyAddressSelections(d,{COUPANG:{outbound:address('101','쿠팡 출고지'),returns:address('102','쿠팡 반품지')},NAVER:{outbound:address('201','네이버 출고지'),returns:address('202','네이버 반품지')}});
 let cp=M.createTarget(d,'COUPANG'),naver=M.createTarget(d,'NAVER');
 assert.equal(cp.working.delivery.outboundCode,'101');assert.equal(cp.working.delivery.returnCode,'102');assert.equal(d.delivery.outboundCode,'');
 const ship='originProduct.deliveryInfo.claimDeliveryInfo.shippingAddressId',ret='originProduct.deliveryInfo.claimDeliveryInfo.returnAddressId';
 assert.equal(naver.working.markets.NAVER.naver.editorInput.fields[ship],'201');
 cp.working.delivery.outboundCode='999';M.recordChange(cp,{path:'delivery.outboundCode',optionId:null});
 const N=require('../../../molebutter-app/src/main/resources/static/js/common-naver-editor-model.js'),before=structuredClone(naver.working.markets.NAVER.naver.editorInput),after=structuredClone(before);after.fields[ship]='888';N.edited(naver.working,before,after);
 const saved=JSON.parse(JSON.stringify(d));M.applyAddressSelections(saved,{COUPANG:{outbound:address('103','새 쿠팡')},NAVER:{outbound:address('203','새 네이버'),returns:address('204','새 반품지')}});
 cp=M.createTarget(saved,'COUPANG',cp);naver=M.createTarget(saved,'NAVER',naver);
 assert.equal(cp.working.delivery.outboundCode,'999');assert.equal(cp.baseline.delivery.outboundCode,'103');assert.equal(cp.working.delivery.returnCode,'102');
 assert.equal(naver.working.markets.NAVER.naver.editorInput.fields[ship],'888');assert.equal(naver.working.markets.NAVER.naver.editorInput.fields[ret],'204');
 M.resetChange(cp,{path:'delivery.outboundCode',optionId:null});assert.equal(cp.working.delivery.outboundCode,'103');
 const invalid=JSON.stringify(saved);assert.throws(()=>M.applyAddressSelections(saved,{COUPANG:{outbound:address('105','valid')},NAVER:{returns:address('../x','bad')}}));assert.equal(JSON.stringify(saved),invalid);
});

test('first lazy target load restores server pending even when an unloaded placeholder already exists',()=>{
 const reference=M.fresh(),id=reference.options[0].id;
 const lazy={id:'session',expiresAt:'2099-01-01',targets:[{market:'COUPANG',mode:'UPDATE',status:'UNLOADED',document:null}]};
 const before=M.sessionState(reference,lazy);
 const loaded={...lazy,targets:[{market:'COUPANG',mode:'UPDATE',status:'READY',document:reference,pendingChanges:[{path:'options.price',optionId:id,value:'0'}]}]};
 const state=M.sessionState(reference,loaded,before),target=state.targets.get('COUPANG');
 assert.equal(target.working.options[0].price,'0');assert.equal(target.changes.size,1);assert.equal(reference.options[0].price,'');
});

test('a failed observation keeps the last confirmed baseline for conflict checking on retry',()=>{
 const {reference,session,state}=liveFixture(),entry=state.targets.get('COUPANG'),id=reference.options[0].id;
 entry.working.options[0].price='120000';M.recordChange(entry,{path:'options.price',optionId:id});
 const failed=structuredClone(session);failed.targets[0].document=null;failed.targets[0].status='FAILED';
 const retained=M.sessionState(reference,failed,state);assert.equal(retained.targets.get('COUPANG').baseline,null);
 const fresh=structuredClone(session);fresh.targets[0].document.options[0].price='115000';
 const restored=M.sessionState(reference,fresh,retained).targets.get('COUPANG');assert.equal(restored.working.options[0].price,'120000');assert.equal(restored.reviewRequired.size,1);
});

test('editing one inherited image creates an option override and reset restores inheritance',()=>{
 const d=M.fresh(),option=d.options[0].id,source={id:M.uuid(),url:'https://assets.test/source.jpg',representative:true,order:0,optionId:null};d.media.images=[source];
 const baseline=M.normalize(d),entry={market:'COUPANG',mode:'UPDATE',baseline,working:M.normalize(d),changes:new Map()};
 entry.working.media.images.push({...source,id:M.uuid(),sourceId:source.id,assetId:M.uuid(),url:'/api/marketplaces/assets/cropped',optionId:option});
 const change={path:'media.images',optionId:option};M.recordChange(entry,change);assert.equal([...entry.changes.values()][0].value[0].sourceId,source.id);assert.equal(entry.working.media.images[0].url,source.url);
 M.resetChange(entry,change);assert.deepEqual(entry.working.media.images,[source]);assert.equal(entry.changes.size,0);
});

test('empty individual media overrides retain shared source and other options and reset inheritance',()=>{
 const d=M.fresh(),a=d.options[0].id,b=M.blankOption();d.options.push(b);d.media.images=[{id:'shared',url:'https://source/one',optionId:null,representative:true,order:0},{id:'other',optionId:b.id,order:0}];
 const entry={working:structuredClone(d),baseline:structuredClone(d),changes:new Map(),reviewRequired:new Set()};
 const change={path:'media.images',optionId:a,value:[]};M.writeChange(entry.working,change);assert.equal(entry.working.media.imageOverrides[a],true);assert.deepEqual(entry.working.media.images.map(i=>i.id),['shared','other']);
 const restored=M.normalize(JSON.parse(JSON.stringify(entry.working)));assert.equal(restored.media.imageOverrides[a],true);
 M.resetChange(entry,change);assert.equal(entry.working.media.imageOverrides[a],undefined);assert.deepEqual(entry.working.media.images,d.media.images);
});

test('editing one inherited media file preserves remaining common files and other option inheritance',()=>{
 const d=M.fresh(),a=d.options[0].id,b=M.blankOption();d.options.push(b);d.media.images=[{id:'one',optionId:null,order:0,representative:true},{id:'two',optionId:null,order:1},{id:'edited',optionId:a,sourceId:'one',order:0,representative:true,url:'edited'}];
 M.materializeMedia(d,'images',a);assert.equal(d.media.imageOverrides[a],true);assert.equal(d.media.imageOverrides[b.id],undefined);assert.deepEqual(M.imagesFor(d,null).map(i=>i.id),['one','two']);assert.deepEqual(M.imagesFor(d,a).map(i=>i.sourceId),['one','two']);assert.equal(M.imagesFor(d,a)[0].url,'edited');
 M.removeOption(d,a);assert.equal(d.media.imageOverrides[a],undefined);
});
