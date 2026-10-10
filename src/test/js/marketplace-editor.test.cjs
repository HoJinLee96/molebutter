const {test}=require('node:test'),assert=require('node:assert/strict');
const M=require('../../../molebutter-app/src/main/resources/static/js/marketplace-editor-model.js');
const rules=()=>({categoryCode:'123',allowSingleItem:true,attributes:[{name:'색상',exposed:'EXPOSED',required:'MANDATORY',groupNumber:'NONE',dataType:'STRING'},{name:'Manufacturer Part Number',exposed:'NONE',required:'OPTIONAL',groupNumber:'NONE',dataType:'STRING'}],notices:[{name:'패션잡화',fields:[{name:'소재',required:'MANDATORY'}]}],certifications:[],documents:[]});
function valid(){const d=M.fresh(),r=rules();Object.assign(d.basic,{sellerProductName:'상품',brand:'합성 브랜드',displayCategoryCode:'123'});for(const k of M.deliveryKeys)d.delivery[k]=M.spec[k][1]==='number'?'0':M.spec[k][1]==='select'?M.spec[k][4][0]:M.spec[k][1]==='id'?'123':'주소';d.settings={brandId:'KR-TEST',saleStartedAt:'2026-10-05T10:00:00',saleEndedAt:'2026-10-06T10:00:00'};const o=d.options[0];o.itemName='FREE 블랙';for(const k of M.optionKeys)if(M.spec[k][3])o.registration[k]=M.spec[k][1]==='number'?'0':M.spec[k][1]==='select'?M.spec[k][4][0]:'값';o.registration.unitCount='1';o.images=[{type:'REPRESENTATION',url:'https://image.example.test/product.jpg'}];o.contents=[{type:'HTML',detailType:'TEXT',content:'<p>설명</p>'}];M.applyRules(d,r);o.attributes[0].value='블랙';o.notices[0].content='가죽';return {d,r};}
test('new draft has one option and no implicit external identities',()=>{const d=M.fresh();assert.equal(d.options.length,1);assert.equal(d.basic.sellerProductId,undefined);assert.equal(d.options[0].vendorItemId,null);assert.equal(d.limits.purchaseAttributesReadOnly,false);});
test('valid inputs produce no write request, zero price and stock are valid',()=>{const {d,r}=valid();assert.deepEqual(M.validate(d,r),[]);d.options[0].current={salePrice:0,amountInStock:0,onSale:false};d.options[0].currentChanges={salePrice:'0',amountInStock:'0'};assert.deepEqual(M.validate(d,r),[]);});
test('rules keep unknown and existing values and do not treat search as optional',()=>{const {d,r}=valid();d.options[0].attributes.push({name:'미래 속성',value:'보존',exposed:'UNKNOWN'});r.attributes[1].required='MANDATORY';M.applyRules(d,r);assert.equal(d.options[0].attributes[0].value,'블랙');assert.equal(d.options[0].attributes.at(-1).value,'보존');assert(M.validate(d,r).some(e=>e.message.includes('Manufacturer Part Number')));d.options[0].attributes[1].value='SKU';assert.deepEqual(M.validate(d,r),[]);});
test('stale metadata cannot validate a different category',()=>{const {d,r}=valid();d.basic.displayCategoryCode='456';assert(M.validate(d,r).some(e=>e.path==='basic.displayCategoryCode'));});
test('grouped required attributes require exactly one value',()=>{const {d,r}=valid();r.attributes.push({name:'그룹A',exposed:'EXPOSED',required:'MANDATORY',groupNumber:'1',dataType:'STRING'},{name:'그룹B',exposed:'EXPOSED',required:'MANDATORY',groupNumber:'1',dataType:'STRING'});M.applyRules(d,r);assert(M.validate(d,r).some(e=>e.message.includes('그룹')));d.options[0].attributes[2].value='A';assert.deepEqual(M.validate(d,r),[]);d.options[0].attributes[3].value='B';assert(M.validate(d,r).some(e=>e.message.includes('그룹')));});
test('invalid dates, unsafe numbers, duplicates, failed local images are blocked',()=>{const {d,r}=valid();d.settings.saleStartedAt='2026-02-30T10:00:00';d.options[0].registration.salePrice='9007199254740992';d.options.push(structuredClone(d.options[0]));d.options[0].images[0]={local:true,type:'REPRESENTATION',url:'blob:local',error:'이미지 오류'};const e=M.validate(d,r);assert(e.some(e=>e.path==='settings.saleStartedAt'));assert(e.some(e=>e.path.endsWith('.salePrice')));assert(e.some(e=>e.message.includes('중복')));assert(e.some(e=>e.message==='이미지 오류'));});
test('current unknown never becomes zero and proposals need confirmed current values',()=>{const {d,r}=valid();d.options[0].separateCurrentChanges=true;delete d.options[0].registration.salePrice;d.options[0].currentChanges={amountInStock:'3'};assert(M.validate(d,r,'edit').some(e=>e.message.includes('현재 값')));assert.equal(d.options[0].current,null);});
test('editor conversion is separate and independent of view response and preserves unknown scalar settings',()=>{const doc={basic:{sellerProductId:'99999999999999999999'},limits:{categoryReadOnly:true},delivery:[{name:'returnAddress',value:'테스트 주소'}],settings:[],options:[{...M.blankOption(),registration:[{name:'futureField',value:'keep'}],notices:[{category:'기타',name:'설명',content:'유지'}]}]};const d=M.fromDocument(doc);d.options[0].notices[0].content='수정';assert.equal(doc.options[0].notices[0].content,'유지');assert.equal(d.options[0].registration.futureField,'keep');assert.equal(d.basic.sellerProductId,'99999999999999999999');});
test('HTTPS validation and preview retain sandbox CSP restrictions',()=>{assert.equal(M.url('http://bad.test/x'),null);assert.equal(M.url('https://user:pass@bad.test/x'),null);assert.equal(M.descriptionUrl('vendor_inventory/test.jpg'),'https://img1a.coupangcdn.com/image/vendor_inventory/test.jpg');const html=M.preview('<img src="http://img1a.coupangcdn.com/image/test.jpg"><script>bad()</script>');assert(html.includes('https://img1a.coupangcdn.com/image/test.jpg'));for(const rule of ["script-src 'none'","connect-src 'none'","form-action 'none'","base-uri 'none'"])assert(html.includes(rule));});

test('conditional document requirements follow option settings and retain alternate paths',()=>{const {d,r}=valid();d.options[0].registration.overseasPurchased='NOT_OVERSEAS_PURCHASED';r.documents=[{name:'해외 서류',required:'MANDATORY_OVERSEAS_PURCHASED'}];assert.deepEqual(M.validate(d,r),[]);d.options[0].registration.overseasPurchased='OVERSEAS_PURCHASED';M.applyRules(d,r);assert(M.validate(d,r).some(e=>e.path.startsWith('documents.')));d.documents[0].vendorPath='https://document.example.test/file';assert.deepEqual(M.validate(d,r),[]);});

// Exercise the real renderer with synthetic data. The harness has no network or browser storage.
async function renderedEditor(documentData){
 const fs=require('node:fs'),vm=require('node:vm'),nodes=new Map(),pickerIds=['editor-options-option','editor-media-option','editor-description-option','editor-settings-option','editor-tags-option','editor-filters-option','editor-notices-option'];
 const dom={innerHTML:'',classList:{add(){}},append(){},remove(){},replaceWith(){}};
 const node=id=>{if(!nodes.has(id))nodes.set(id,{id,innerHTML:'',textContent:'',hidden:false,disabled:false,options:[],value:'',parentElement:dom,firstElementChild:dom,childNodes:[],querySelectorAll(){return [];},querySelector(selector){return node(id+selector);},classList:{add(){}},append(){},replaceWith(){},closest(){return node('fake-label');},setAttribute(){},addEventListener(type,fn){(this.listeners??={})[type]=fn;}});return nodes.get(id);};
 const escape=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const context={FormActions:{bindEnter(){},bindExplicitSubmit(form,button,handler){button.type='button';form.addEventListener('submit',event=>event.preventDefault());button.addEventListener('click',()=>handler({preventDefault(){}}));}},AppUI:{$:node,escape},window:{CoupangEditorSave:{create:()=>({observe(){},changed(){}})},CoupangBrandPicker:{mount(){}},CoupangEditorModel:M,CoupangRead:{start:()=>({promise:Promise.resolve({token:"test",expiresAt:"2099-01-01",document:documentData}),cancel(){}})},addEventListener(){}},location:{pathname:'/marketplaces/coupang/products/1/edit'},document:{createElement:()=>({...dom,childNodes:[],firstElementChild:dom}),getElementById:node,querySelectorAll:q=>q==='[data-editor-option]'?pickerIds.map(node):[]},URL,structuredClone,Date,setTimeout:()=>1,clearTimeout(){},console};
 vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/marketplace-editor.js','utf8'),context);
 for(let i=0;i<6;i++)await Promise.resolve();assert.equal(nodes.get('editor-load-error')?.textContent,'');return nodes;
}
test('Coupang editor keeps MPN, model number and seller SKU separate in the new sections',async()=>{
 const documentData={basic:{sellerProductId:'1',sellerProductName:'상품',displayProductName:'노출명',brand:'브랜드',displayCategoryCode:'123'},limits:{categoryReadOnly:true},options:[{...M.blankOption(),itemName:'블랙',attributes:[{name:'색상',value:'블랙',exposed:'EXPOSED'},{name:'Manufacturer Part Number',value:'MPN-11',exposed:'NONE'},{name:'미래 검색값',value:'보존',exposed:'FUTURE'},{name:'Global Trade Item Number',value:'GTIN-44',exposed:'NONE'}],registration:[{name:'modelNo',value:'MODEL-22'},{name:'externalVendorSku',value:'SKU-33'},{name:'searchTags',value:'가방, 지갑'}]}],delivery:[],settings:[{name:'bundleInfo.bundleType',value:'AB'}],documents:[]};
 const nodes=await renderedEditor(documentData),table=nodes.get('editor-option-list').innerHTML;
 assert(table.includes('data-field="options.0.attributes.1.value"'));assert(table.includes('value="MPN-11"'));assert(table.includes('value="SKU-33"'));
 assert(!nodes.get('editor-search-filter-fields').innerHTML.includes('data-field="options.0.attributes.1.value"'));
 assert(table.includes('value="GTIN-44"'));assert(!nodes.get('editor-search-filter-fields').innerHTML.includes('GTIN-44'));
 assert(nodes.get('editor-settings-fields').innerHTML.includes('value="MODEL-22"'));assert(nodes.get('editor-settings-fields').innerHTML.includes('모델번호'));
 assert(nodes.get('editor-search-filter-fields').innerHTML.includes('value="보존"'));assert(nodes.get('editor-tag-list').innerHTML.includes('가방'));assert(nodes.get('editor-tag-list').innerHTML.includes('지갑'));
 assert.match(nodes.get('editor-settings-fields').innerHTML, /data-field="settings\.bundleInfo\.bundleType"[^>]*disabled/);
});
test('Coupang editor distinguishes confirmed zero from partial current values and retains unknown selects',async()=>{
 const documentData={basic:{sellerProductId:'1',sellerProductName:'상품',brand:'합성 브랜드',displayCategoryCode:'123'},limits:{categoryReadOnly:true},options:[{...M.blankOption(),itemName:'블랙',separateCurrentChanges:true,current:{salePrice:0,amountInStock:null,onSale:false},registration:[{name:'salePrice',value:'9000'},{name:'maximumBuyCount',value:'5'},{name:'taxType',value:'FUTURE_TAX'}]}],delivery:[{name:'deliveryCompanyCode',value:'FUTURE_COURIER'},{name:'returnChargeName',value:'반품지'}],settings:[],documents:[]};
 const nodes=await renderedEditor(documentData),current=nodes.get('editor-option-list').innerHTML;
 assert.match(current,/data-field="options\.0\.currentChanges\.salePrice"[^>]*value="0"/);assert(current.includes('미확인'));assert(!current.includes('현재 판매 정보'));
 assert.match(current,/data-field="options\.0\.currentChanges\.amountInStock"[^>]*disabled/);assert(!/data-field="options\.0\.currentChanges\.salePrice"[^>]*disabled/.test(current));
 assert(nodes.get('editor-settings-fields').innerHTML.includes('value="FUTURE_TAX" checked'));assert(nodes.get('editor-delivery-fields').innerHTML.includes('value="FUTURE_COURIER" selected'));assert(nodes.get('editor-return-fields').innerHTML.includes('반품지'));assert(nodes.get('editor-return-fields').innerHTML.includes('data-address-book="return"'));
});

test('new products require selected brand while imported edits preserve missing legacy IDs',()=>{const {d,r}=valid();delete d.settings.brandId;assert(M.validate(d,r,'new').some(e=>e.path==='basic.brand'));assert(!M.validate(d,r,'edit').some(e=>e.path==='basic.brand'));});

test('shared edits preserve untouched differences and match reordered fields by identity',()=>{
 const a=M.blankOption(),b=M.blankOption();a.registration.searchTags='first';b.registration.searchTags='second';
 a.attributes=[{name:'material',value:'leather',exposed:'NONE'},{name:'Manufacturer Part Number',value:'A',exposed:'NONE'}];
 b.attributes=[{name:'Manufacturer Part Number',value:'B',exposed:'NONE'},{name:'material',value:'canvas',exposed:'NONE'}];
 a.notices=[{category:'bags',name:'material',content:'leather'}];b.notices=[{category:'bags',name:'origin',content:'KR'},{category:'bags',name:'material',content:'canvas'}];
 a.images=[{url:'https://example.test/a.png'}];b.images=[{url:'https://example.test/b.png'}];
 const d={options:[a,b]},before=structuredClone(d);
 assert.deepEqual(M.sharedValues(d,'options.0.registration.searchTags'),['first','second']);assert.deepEqual(d,before);
 M.setSharedValue(d,'options.0.registration.searchTags','shared');assert.equal(b.registration.searchTags,'shared');
 M.setSharedValue(d,'options.0.attributes.0.value','shared material');assert.equal(b.attributes[1].value,'shared material');assert.equal(b.attributes[0].value,'B');
 M.setSharedValue(d,'options.0.notices.0.content','shared notice');assert.equal(b.notices[1].content,'shared notice');assert.equal(b.notices[0].content,'KR');
 assert.deepEqual(d.options.map(o=>o.images),before.options.map(o=>o.images));
 a.attributes.push({name:'Global Trade Item Number',value:'GTIN-A',exposed:'NONE'});b.attributes.push({name:'Global Trade Item Number',value:'GTIN-B',exposed:'NONE'});
 M.setSharedValue(d,'options.0.attributes.'+(a.attributes.length-1)+'.value','GTIN-CHANGED');assert.equal(b.attributes.at(-1).value,'GTIN-B');
 const added=M.inheritShared(d,M.blankOption());assert(!added.attributes.some(a=>a.name==='Global Trade Item Number'));assert.equal(added.registration.searchTags,'shared');assert(!added.attributes.some(a=>a.name==='Manufacturer Part Number'));assert.deepEqual(added.images,[]);
});

test('image drag reorders, replaces representative and preserves pending file references',()=>{
 const a={type:'REPRESENTATION',url:'https://example.test/a',order:0},b={type:'DETAIL',url:'blob:pending',pending:true,order:1},c={type:'DETAIL',url:'https://example.test/c',order:2},images=[a,b,c];
 assert(M.moveImage(images,2,'DETAIL',1));assert.deepEqual(images,[a,c,b]);assert.deepEqual(images.map(i=>i.order),[0,1,2]);
 assert(M.moveImage(images,2,'REPRESENTATION'));assert.equal(images[0],b);assert.equal(a.type,'DETAIL');assert.equal(b.type,'REPRESENTATION');b.pending=false;assert.equal(images[0].pending,false);
 assert(M.moveImage(images,0,'DETAIL'));assert(!images.some(i=>i.type==='REPRESENTATION'));assert.equal(M.nextImageType(images),'REPRESENTATION');
});
test('image limits apply per type and rejected moves leave data unchanged',()=>{
 const images=[{type:'REPRESENTATION'},...Array.from({length:9},()=>({type:'DETAIL'})),...Array.from({length:4},()=>({type:'USED_PRODUCT'}))],before=structuredClone(images);
 assert.equal(M.nextImageType(images),null);assert.equal(M.moveImage(images,0,'DETAIL'),false);assert.deepEqual(images,before);
 assert(M.moveImage(images,4,'REPRESENTATION'));assert.equal(images.filter(i=>i.type==='DETAIL').length,9);assert.equal(images.filter(i=>i.type==='USED_PRODUCT').length,4);
 assert.equal(M.moveImage(images,1,'USED_PRODUCT'),false);assert.equal(M.moveImage(images,0,'UNKNOWN'),false);
});


test('major information uses ordered radio groups, separate documents and explicit common settings',async()=>{
 const data={basic:{sellerProductId:'1',sellerProductName:'상품',displayCategoryCode:'123'},limits:{},settings:[{name:'bundleInfo.bundleType',value:'SINGLE'},{name:'saleStartedAt',value:'2026-10-07T10:00:00'},{name:'saleEndedAt',value:'2027-10-07T10:00:00'}],delivery:[],documents:[{templateName:'증빙',path:'https://example.test/document'}],options:[{...M.blankOption(),registration:[{name:'maximumBuyForPerson',value:'3'},{name:'maximumBuyForPersonPeriod',value:'7'},{name:'adultOnly',value:'EVERYONE'},{name:'taxType',value:'TAX'}]}]};
 const nodes=await renderedEditor(data),html=nodes.get('editor-settings-fields').innerHTML;
 const labels=['상품 구성','인증정보','병행수입','구매 연령','인당 최대구매수량','판매기간','부가세'];
 for(let n=1;n<labels.length;n++)assert(html.indexOf('aria-label="'+labels[n-1]+'"')<html.indexOf('aria-label="'+labels[n]+'"'));
 assert(html.includes('type="radio"'));assert(!html.includes('documents.'));assert(nodes.get('editor-document-fields').innerHTML.includes('https://example.test/document'));
 const change=nodes.get('marketplace-editor-form').listeners.change;
 change({target:{disabled:false,checked:true,value:'OFF',dataset:{settingsAction:'purchase-limit'}}});
 assert(!nodes.get('editor-settings-fields').innerHTML.includes('data-field="options.0.registration.maximumBuyForPerson"'));
 change({target:{disabled:false,checked:true,value:'ON',dataset:{settingsAction:'purchase-limit'}}});
 assert(nodes.get('editor-settings-fields').innerHTML.includes('data-field="options.0.registration.maximumBuyForPerson"'));
 change({target:{disabled:false,checked:true,value:'OFF',dataset:{settingsAction:'sale-period'}}});
 assert(!nodes.get('editor-settings-fields').innerHTML.includes('data-field="settings.saleEndedAt"'));
 change({target:{disabled:false,checked:true,value:'ON',dataset:{settingsAction:'sale-period'}}});
 assert(nodes.get('editor-settings-fields').innerHTML.includes('value="2099-12-31T23:59:59"'));
 assert.equal(data.options[0].registration[0].value,'3');assert.equal(data.settings[2].value,'2027-10-07T10:00:00');
});

test('notice reference checkbox reflects every option and restores prior values on uncheck',async()=>{
 const data={basic:{sellerProductId:'1',displayCategoryCode:'123'},limits:{},settings:[],delivery:[],documents:[],options:[{...M.blankOption(),registration:[],notices:[{category:'가방',name:'소재',content:'가죽'}]},{...M.blankOption(),registration:[],notices:[{category:'가방',name:'소재',content:'면'}]}]};
 for(const o of data.options)o.notices.push({category:'어린이제품',name:'KC 인증정보',content:'숨긴 유형 값'});
 const nodes=await renderedEditor(data),change=nodes.get('marketplace-editor-form').listeners.change;
 assert(!nodes.get('editor-notice-fields').innerHTML.includes('어린이제품 · KC 인증정보'));
 assert(!/id="editor-notice-reference" checked/.test(nodes.get('editor-notice-fields').innerHTML));
 change({target:{id:'editor-notice-reference',disabled:false,checked:true}});
 assert(/id="editor-notice-reference" checked/.test(nodes.get('editor-notice-fields').innerHTML));
 assert(nodes.get('editor-notice-fields').innerHTML.includes('상품 상세페이지 참조</textarea>'));
 change({target:{id:'editor-notice-reference',disabled:false,checked:false}});
 assert(nodes.get('editor-notice-fields').innerHTML.includes('가죽</textarea>'));
 assert.equal(data.options[1].notices[0].content,'면');
 for(const o of data.options)o.notices[0].content='상품 상세페이지 참조';
 const imported=await renderedEditor(data);assert(/id="editor-notice-reference" checked/.test(imported.get('editor-notice-fields').innerHTML));
 data.options[1].notices[0].content='외부 변경';const mixed=await renderedEditor(data);assert(!/id="editor-notice-reference" checked/.test(mixed.get('editor-notice-fields').innerHTML));
});

test('save changes use remote option identities, active notices and confirmed zero',()=>{
 const {d}=valid();d.options[0].sellerProductItemId='1001';d.options[0].separateCurrentChanges=true;d.options[0].current={salePrice:100,amountInStock:4};const before=structuredClone(d);d.options[0].currentChanges={salePrice:'0',amountInStock:'0'};d.options[0].notices.push({category:'inactive',name:'hidden',content:'never transmit'});d.options[0].notices[0].content='changed';d.options[0].registration.originalPrice='0';
 const changes=M.changes(before,d);assert.deepEqual(changes.filter(c=>['options.price','options.quantity'].includes(c.path)).map(c=>[c.sellerProductItemId,c.value]),[['1001','0'],['1001','0']]);assert.equal(changes.find(c=>c.path.endsWith('.notices')).value.length,1);assert(!JSON.stringify(changes).includes('never transmit'));
 const reordered=structuredClone(before);reordered.options.reverse();assert.deepEqual(M.changes(before,reordered),[]);
});
test('save media preserves image type and blocks unfinished uploads',()=>{
 const {d}=valid();d.options[0].sellerProductItemId='1001';const before=structuredClone(d);d.options[0].images.push({type:'USED_PRODUCT',url:'https://image.example.test/used.png'});let c=M.changes(before,d).find(c=>c.path==='media.images');assert.equal(c.value[1].type,'USED_PRODUCT');assert.equal(c.value[0].representative,true);d.options[0].images[1]={type:'DETAIL',url:'blob:local',local:true,pending:true};assert.throws(()=>M.changes(before,d),/업로드/);
});
test('successful save rebases latest values while retaining edits made during execution',()=>{
 const {d}=valid();d.options[0].sellerProductItemId='1001';const edited=structuredClone(d),fresh=structuredClone(d);edited.basic.sellerProductName='typing';fresh.delivery.returnCharge='5000';fresh.options[0].current={salePrice:0,amountInStock:0};const next=M.rebase(d,edited,fresh);assert.equal(next.basic.sellerProductName,'typing');assert.equal(next.delivery.returnCharge,'5000');assert.equal(next.options[0].current.salePrice,0);
});
test('common option edits export all stable identities and pending prices use typed option paths',()=>{
 const {d}=valid();d.options[0].sellerProductItemId='1001';const second=structuredClone(d.options[0]);second.sellerProductItemId='1002';second.itemName='second';d.options.push(second);const before=structuredClone(d);M.setSharedValue(d,'options.0.registration.searchTags','shared');d.options.reverse();d.options[0].registration.salePrice='10';const c=M.changes(before,d);assert.deepEqual(c.filter(v=>v.path.endsWith('.searchTags')).map(v=>v.sellerProductItemId).sort(),['1001','1002']);assert.deepEqual(c.find(v=>v.path==='options.price'),{path:'options.price',sellerProductItemId:'1002',value:'10'});assert(!c.some(v=>v.path.endsWith('.registration.salePrice')));
});
test('rebase retains in-flight image entry identity for upload completion after execution',()=>{
 const {d}=valid();d.options[0].sellerProductItemId='1001';const before=structuredClone(d),fresh=structuredClone(d),entry={local:true,pending:true,url:'blob:pending',file:{name:'pending'}};d.options[0].images.push(entry);const result=M.rebase(before,d,fresh);assert.equal(result.options[0].images[1],entry);entry.pending=false;entry.assetId='uploaded';entry.local=false;assert.equal(result.options[0].images[1].assetId,'uploaded');
});

test('registration names and category rebuild preserve compatible values and identify removals',()=>{
 const d=M.fresh(),a=d.options[0];a.attributes=[{name:'사이즈',value:'FREE',exposed:'EXPOSED'},{name:'색상',value:'브라운',exposed:'EXPOSED'},{name:'제외 속성',value:'입력',exposed:'NONE'}];a.noticeCategory='가방';a.notices=[{category:'가방',name:'소재',content:'가죽'},{category:'기타',name:'품명',content:'제외'}];a.certifications=[{type:'OLD',code:'123'}];d.documents=[{templateName:'OLD',path:'https://example.test/file'}];
 assert.equal(M.registrationOptionName(a),'브라운 FREE');const id=a.id;assert.notEqual(M.blankOption().id,id);
 const r={...rules(),notices:[{name:'가방',fields:[{name:'소재',required:'MANDATORY'}]}]};const removed=M.categoryRemovals(d,r);assert(removed.includes('제외 속성'));assert(removed.includes('인증 OLD'));assert(removed.includes('OLD'));
 M.replaceRules(d,r);assert.equal(a.id,id);assert.equal(a.attributes[0].value,'브라운');assert.equal(a.notices[0].content,'가죽');assert.equal(a.certifications.length,0);assert.equal(d.documents.length,0);assert.equal(a.itemName,'브라운');
});

test('dedicated registration serialization retains stable option and asset UUIDs without remote identities',()=>{
 const fs=require('node:fs'),vm=require('node:vm'),context={AppUI:{$(){},escape:String,uuid:()=>require('node:crypto').randomUUID()},window:{CoupangEditorModel:M},structuredClone};vm.runInNewContext(fs.readFileSync('molebutter-app/src/main/resources/static/js/coupang-product-registration.js','utf8'),context);
 const {d}=valid();d.options[0].sellerProductItemId='123';d.options[0].vendorItemId='456';d.options[0].contents.push({type:'IMAGE',detailType:'IMAGE',content:'http://img1a.coupangcdn.com/image/test.jpg'});const module=context.window.CoupangProductRegistration,first=module.input(d),second=module.input(d);
 assert.equal(first.options[0].contents[1].content,'https://img1a.coupangcdn.com/image/test.jpg');assert.equal(first.options[0].id,second.options[0].id);assert.equal(first.options[0].images[0].id,second.options[0].images[0].id);assert.equal(first.options[0].contents[0].id,second.options[0].contents[0].id);assert(!JSON.stringify(first).includes('sellerProductItemId'));assert(!JSON.stringify(first).includes('vendorItemId'));assert.equal(module.model(first).options[0].id,first.options[0].id);
});
