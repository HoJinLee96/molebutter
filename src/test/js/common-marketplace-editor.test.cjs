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
 const {reference,state}=liveFixture(),entry=state.targets.get('COUPANG');state.reference.common.productName='기준 제품명';entry.baseline.common.productName='실제 제품명';entry.working.common.productName='실제 제품명';entry.baseline.markets.COUPANG.overrides.productName='실제 노출명';entry.working.markets.COUPANG.overrides.productName='실제 노출명';
 const change={path:'markets.COUPANG.overrides.productName',optionId:null};M.applyReference(state,'COUPANG',[change]);assert.equal(entry.working.markets.COUPANG.overrides.productName,'기준 제품명');assert.equal(entry.working.common.productName,'실제 제품명');assert.deepEqual(M.proposals(state,['COUPANG'])[0].changes.map(c=>c.path),['markets.COUPANG.overrides.productName']);
 M.resetChange(entry,change);assert.equal(entry.working.markets.COUPANG.overrides.productName,'실제 노출명');
});
