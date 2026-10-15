/* Internal sales drafts. These documents are never external marketplace payloads. */
(() => {
    'use strict';
    const N=typeof module!=='undefined'?require('./common-naver-editor-model.js'):window.CommonNaverEditorModel;
    const channels=globalThis.MarketplaceChannels||[],markets=channels.filter(c=>c.commonDraft).map(c=>[c.id,c.label]);
    const uuid=typeof module!=='undefined'?require('./app-ui.js').uuid:AppUI.uuid;
    const commonKeys=['productCode','name','productName','brand','manufacturer','origin','material','model','afterService','taxType','adultOnly','afterServiceManager','afterServiceTelephone','saleStartedAt','saleEndedAt'];
    const deliveryKeys=['method','carrier','chargeType','charge','freeOver','returnCharge','initialReturnCharge','remoteArea','bundle','outboundCode','returnCode','returnName','returnContact','returnZip','returnAddress','returnAddressDetail','shippingDays','jejuCharge','remoteCharge'];
    const blankOption=()=>({id:uuid(),name:'',sku:'',originalPrice:'',price:'',quantity:'',attributes:[]});
    const config=()=>({categoryCode:'',overrides:{name:null,productName:null,brand:null,options:[],imageIds:null,description:null},coupang:null,naver:null,esm:null,lotteon:null});
    function fresh(market='COUPANG'){
        return {id:null,revision:null,common:Object.fromEntries(commonKeys.map(k=>[k,k==='taxType'?'TAX':k==='adultOnly'?'EVERYONE':''])),options:[blankOption()],stockMode:'OPTION',productQuantity:'',services:[],media:{images:[],contents:[]},delivery:Object.fromEntries(deliveryKeys.map(k=>[k,''])),selectedMarkets:markets.some(([key])=>key===market)?[market]:['COUPANG'],markets:Object.fromEntries(markets.map(([key])=>[key,config()]))};
    }
    function normalize(document){
        const d=structuredClone(document),blank=fresh();
        d.common={...blank.common,...d.common};d.delivery={...blank.delivery,...d.delivery};d.media={images:[],contents:[],...d.media};d.options=(d.options||[]).map(o=>({...o,originalPrice:o.originalPrice??'',attributes:o.attributes||[]}));d.services=d.services||[];d.selectedMarkets=d.selectedMarkets||[];d.markets=d.markets||{};
        for(const [key] of markets){const c=d.markets[key]||{};d.markets[key]={...config(),...c,overrides:{...config().overrides,...c.overrides,options:c.overrides?.options||[]}};}
        return d;
    }
    function optionOverride(d,market,optionId,create=false){
        const list=d.markets[market].overrides.options;let entry=list.find(o=>o.optionId===optionId);
        if(!entry&&create){entry={optionId,name:null,sku:null,price:null,quantity:null};list.push(entry);}return entry;
    }
    const inherits=v=>v===null||v===undefined;
    function resolved(d,market,key,optionId){const o=optionId?d.options.find(o=>o.id===optionId):d.common;const v=optionId?optionOverride(d,market,optionId)?.[key]:d.markets[market].overrides[key];return inherits(v)?o?.[key]:v;}
    function brandSelection(d,market){
        const c=d.markets?.[market];if(!c||!['COUPANG','NAVER','LOTTEON','ELEVENST'].includes(market))return null;
        if(market==='ELEVENST'){const f=c.elevenst?.editorInput?.fields;return {id:f?.apiPrdAttrBrandCd||null,name:f&&Object.hasOwn(f,'brand')?f.brand:resolved(d,market,'brand')};}
        const lotteFields=c.lotteon?.nativeInput?.product?.fields;if(market==='LOTTEON'&&lotteFields&&Object.hasOwn(lotteFields,'brdNo')&&lotteFields.brdNo!==c.lotteon.brandId)return {id:lotteFields.brdNo||null,name:lotteFields.brdNo?'개별 브랜드 '+lotteFields.brdNo:''};
        const id=market==='COUPANG'?c.coupang?.settings?.find(f=>f.name==='brandId')?.value:market==='NAVER'?c.naver?.brandId:c.lotteon?.brandId;
        return {id:typeof id==='string'&&id!==''?id:null,name:inherits(c.overrides?.brand)?d.common.brand:c.overrides.brand};
    }
    function brandLocked(d,market){return market==='COUPANG'&&!!d.markets?.COUPANG?.coupang?.sellerProductId;}
    function clearBrandId(c,market){
        if(market==='COUPANG'&&c.coupang)c.coupang.settings=(c.coupang.settings||[]).filter(f=>f.name!=='brandId');
        if(market==='NAVER'&&c.naver)c.naver.brandId=null;
        if(market==='LOTTEON'&&c.lotteon)c.lotteon.brandId=null;if(market==='ELEVENST'&&c.elevenst?.editorInput)c.elevenst.editorInput.fields.apiPrdAttrBrandCd='';
    }
    // Selected names belong to their marketplace. Editing the common name only
    // invalidates legacy IDs that still inherit that name, never explicit choices.
    function setCommonBrand(d,name){
        if(typeof name!=='string')throw Error('브랜드명을 확인해 주세요.');
        if(d.common.brand===name)return d;
        for(const market of ['COUPANG','NAVER','LOTTEON','ELEVENST']){const c=d.markets?.[market];if(c&&inherits(c.overrides?.brand)&&!brandLocked(d,market))clearBrandId(c,market);}
        d.common.brand=name;return d;
    }
    function applyBrandSelections(d,selections){
        if(!selections||typeof selections!=='object'||Array.isArray(selections))throw Error('마켓별 브랜드 선택을 확인해 주세요.');
        const choices=Object.entries(selections).map(([market,selection])=>{
            if(!['COUPANG','NAVER','LOTTEON','ELEVENST'].includes(market)||(market!=='LOTTEON'&&!d.markets?.[market]))throw Error('브랜드 검색이 지원되지 않는 마켓입니다.');
            if(brandLocked(d,market))throw Error('기존 쿠팡 브랜드는 변경할 수 없습니다.');
            if(selection===null)return {market,selection:null};
            if(typeof selection?.id!=='string'||(!selection.id.trim()&&market!=='ELEVENST')||selection.id.length>(market==='LOTTEON'?12:100)||/[\u0000-\u001f\u007f]/.test(selection.id)||typeof selection.name!=='string'||!selection.name.trim()||selection.name.length>(market==='LOTTEON'?255:200)||/[\u0000-\u001f\u007f]/.test(selection.name))throw Error('검색 결과에서 브랜드를 선택해 주세요.');
            const id=selection.id,name=selection.name;
            if(market==='ELEVENST'&&id&&!/^[1-9]\d{0,29}$/.test(id))throw Error('11번가 브랜드 코드를 확인해 주세요.');
            if(market==='NAVER'&&(!/^[1-9]\d*$/.test(id)||BigInt(id)>9223372036854775807n))throw Error('스마트스토어 브랜드 ID를 확인해 주세요.');
            return {market,selection:{id,name}};
        });
        // Validate every choice before modifying the document, so a locked or
        // invalid second market cannot leave the first market partially updated.
        for(const {market,selection} of choices){
            const c=d.markets[market]??=config();c.overrides??=config().overrides;clearBrandId(c,market);c.overrides.brand=selection?.name??null;
            if(selection&&market==='COUPANG'){c.coupang??={sellerProductId:null,settings:[],delivery:[],documents:[],options:[],source:null};c.coupang.settings.push({name:'brandId',value:selection.id});}
            if(selection&&market==='NAVER'){c.naver??={};c.naver.brandId=selection.id;}
            if(selection&&market==='LOTTEON'){c.lotteon??={};c.lotteon.brandId=selection.id;}
            if(market==='ELEVENST'){const E=typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel;d.markets.ELEVENST=E.project(d).markets.ELEVENST;const before=structuredClone(E.input(d)),after=structuredClone(before);after.fields.brand=selection?.name??d.common.brand;after.fields.apiPrdAttrBrandCd=selection?.id||'';E.mark(d,before,after);d.markets.ELEVENST.elevenst.editorInput=after;}
        }
        return d;
    }
    const addressDelivery={outbound:{outboundCode:'code'},returns:{returnCode:'code',returnName:'name',returnContact:'phone',returnZip:'zipCode',returnAddress:'address',returnAddressDetail:'detail'}};
    function applyAddressSelections(d,selections){
        const next=[];
        for(const [market,book] of Object.entries(selections||{})){
            if(!['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(market)||!book||typeof book!=='object')throw Error('마켓별 주소 선택을 확인해 주세요.');
            for(const [kind,address] of Object.entries(book)){
                if(!['outbound','returns'].includes(kind)||!address||typeof address.code!=='string'||!(market==='LOTTEON'?/^[A-Za-z0-9_-]{1,30}$/:/^[1-9]\d{0,29}$/).test(address.code))throw Error('조회한 주소록에서 선택해 주세요.');
                const clean={};for(const key of ['code','name','zipCode','address','detail','phone']){if(typeof address[key]!=='string'||address[key].length>1000)throw Error('주소 정보를 확인해 주세요.');clean[key]=address[key];}
                next.push({market,kind,address:clean});
            }
        }
        for(const {market,kind,address} of next){d.markets[market].addressBook??={outbound:null,returns:null};d.markets[market].addressBook[kind]=address;}
        return d;
    }
    function addressDeliveryValues(book){return Object.fromEntries(Object.entries(addressDelivery).flatMap(([kind,fields])=>book?.[kind]?Object.entries(fields).map(([key,value])=>[key,book[kind][value]||'']):[]));}
    function toggle(d,market,key,on,optionId){const obj=optionId?optionOverride(d,market,optionId,true):d.markets[market].overrides;obj[key]=on?resolved(d,market,key,optionId)??'':null;}
    function removeOption(d,id){
        if(d.options.length<=1)return false;
        d.options=d.options.filter(o=>o.id!==id);delete d.media.imageOverrides?.[id];delete d.media.contentOverrides?.[id];
        for(const c of Object.values(d.markets)){if(c.overrides)c.overrides.options=(c.overrides.options||[]).filter(o=>o.optionId!==id);if(c.coupang)c.coupang.options=(c.coupang.options||[]).filter(o=>o.optionId!==id);}
        const removedImages=new Set(d.media.images.filter(i=>i.optionId===id).map(i=>i.id));
        d.media.images=d.media.images.filter(i=>!removedImages.has(i.id));d.media.contents=d.media.contents.filter(c=>c.optionId!==id);
        for(const c of Object.values(d.markets))if(c.overrides?.imageIds)c.overrides.imageIds=c.overrides.imageIds.filter(imageId=>!removedImages.has(imageId));
        return true;
    }
    function materializeMedia(d,kind,optionId){
        if(!optionId)return;const mode=kind==='images'?'imageOverrides':'contentOverrides';if(d.media[mode]?.[optionId])return;
        const common=d.media[kind].filter(v=>!v.optionId),own=d.media[kind].filter(v=>v.optionId===optionId),shadow=new Set(own.map(v=>v.sourceId).filter(Boolean));
        const inherited=common.filter(v=>!shadow.has(v.id)).map(v=>({...clone(v),id:uuid(),optionId,sourceId:v.id}));
        let values=[...inherited,...own];if(kind==='images')values.sort((a,b)=>a.order-b.order);else values.sort((a,b)=>{const index=v=>{const n=common.findIndex(c=>c.id===(v.sourceId||v.id));return n<0?common.length+own.indexOf(v):n;};return index(a)-index(b);});
        d.media[kind]=d.media[kind].filter(v=>v.optionId!==optionId).concat(values);(d.media[mode]??={})[optionId]=true;
    }
    function setRepresentative(d,imageId){const target=d.media.images.find(i=>i.id===imageId);if(!target)return;for(const i of d.media.images)if(i.optionId===target.optionId)i.representative=i.id===imageId;}
    function imagesFor(d,optionId=null){return d.media.images.filter(i=>(i.optionId??null)===optionId).sort((a,b)=>a.order-b.order);}
    function moveImage(d,imageId,delta){const image=d.media.images.find(i=>i.id===imageId);if(!image)return;const list=imagesFor(d,image.optionId??null),index=list.indexOf(image),to=index+delta;if(to<0||to>=list.length)return;[list[index],list[to]]=[list[to],list[index]];list.forEach((i,n)=>i.order=n);}
    function removeImage(d,id){const image=d.media.images.find(i=>i.id===id);d.media.images=d.media.images.filter(i=>i.id!==id);for(const c of Object.values(d.markets)){const override=c.overrides;if(override?.imageIds)override.imageIds=override.imageIds.filter(v=>v!==id);}if(image?.representative){const next=imagesFor(d,image.optionId??null)[0];if(next)next.representative=true;}}
    const url=v=>{try{const u=new URL(v);return u.protocol==='https:'&&!u.username&&!u.password?u.href:null;}catch{return null;}};
    const integer=v=>/^\d+$/.test(String(v))&&Number.isSafeInteger(Number(v));
    function validate(d,complete=false){
        const errors=[],fail=(path,message)=>errors.push({market:null,path,message});
        if(complete&&!d.common.productCode.trim())fail('common.productCode','상품코드를 입력해 주세요.');
        if(complete&&!d.common.name.trim())fail('common.name','상품명을 입력해 주세요.');
        if(complete&&!d.selectedMarkets.length)fail('selectedMarkets','판매 마켓을 선택해 주세요.');
        const ids=new Set();
        d.options.forEach((o,n)=>{if(ids.has(o.id))fail('options.'+n,'옵션 식별자를 확인해 주세요.');ids.add(o.id);if(complete&&!o.name.trim())fail('options.'+n+'.name','옵션명을 입력해 주세요.');for(const key of ['originalPrice','price','quantity']){const v=o[key];if(key==='quantity'&&d.stockMode==='PRODUCT')continue;if(!inherits(v)&&v!==''&&!integer(v))fail('options.'+n+'.'+key,'가격·수량은 0 이상의 정수로 입력해 주세요.');if(complete&&key!=='originalPrice'&&(v===null||v===''))fail('options.'+n+'.'+key,(key==='price'?'판매가':'수량')+'를 입력해 주세요.');}});
        if(d.stockMode==='PRODUCT'){if(d.productQuantity!==''&&!integer(d.productQuantity))fail('productQuantity','상품 공통 수량은 0 이상의 정수로 입력해 주세요.');if(complete&&!d.productQuantity)fail('productQuantity','상품 공통 수량을 입력해 주세요.');}
        for(const [key,c] of Object.entries(d.markets)){if(!d.selectedMarkets.includes(key))continue;for(const o of c.overrides.options||[])for(const field of ['price','quantity'])if(!inherits(o[field])&&o[field]!==''&&!integer(o[field]))errors.push({market:key,path:'markets.'+key+'.overrides.options',message:'마켓별 가격·수량은 0 이상의 정수로 입력해 주세요.'});}
        for(const [n,i] of d.media.images.entries())if(!i.assetId&&!url(i.url))fail('media.images.'+n,'이미지에 HTTPS 주소가 필요합니다.');
        for(const [n,c] of d.media.contents.entries())if(c.type==='IMAGE'&&!c.assetId&&c.value&&!url(c.value))fail('media.contents.'+n+'.value','설명 이미지에 HTTPS 주소가 필요합니다.');
        return errors;
    }
    // An editing session owns live marketplace baselines. Reference defaults never
    // replace an existing marketplace value unless the user explicitly applies them.
    const clone=v=>v===undefined?undefined:structuredClone(v);
    const changeKey=c=>c.path+'|'+(c.optionId??'');
    function canonical(value){
        if(Array.isArray(value))return value.map(canonical);
        if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])]));
        return value;
    }
    function equal(a,b,path=''){
        if(/(?:price|quantity|productQuantity|charge|freeOver|returnCharge|initialReturnCharge)$/.test(path)&&a!=null&&b!=null&&a!==''&&b!==''&&integer(a)&&integer(b))return String(BigInt(a))===String(BigInt(b));
        return JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
    }
    // New targets follow reference defaults until a field is explicitly edited.
    // A connected target continues to use its separately observed marketplace values.
    function newMarketDocument(reference,market,previous=null){
        if(market==='LOTTEON')return (typeof module!=='undefined'?require('./lotteon-editor-model.js'):window.LotteonEditorModel).project(reference);
        if(market==='ELEVENST')return (typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel).project(reference);
        const base=normalize(reference),d=clone(base),c=d.markets[market];
        if(market==='NAVER')return N.baseline(d);
        if(!c)throw Error('마켓 입력을 확인해 주세요.');
        if(market==='COUPANG'&&(c.coupang?.sellerProductId||c.coupang?.source||(c.coupang?.options||[]).some(o=>o.sellerProductItemId||o.vendorItemId)))throw Error('연결된 상품은 마켓의 최신 값을 먼저 조회해 주세요.');
        const over=c.overrides;
        for(const key of ['name','brand'])d.common[key]=resolved(base,market,key);
        d.options=d.options.map(o=>({...o,...Object.fromEntries(['name','sku','price','quantity'].map(key=>[key,resolved(base,market,key,o.id)]))}));
        if(over.imageIds!==null)d.media.images=d.media.images.filter(i=>over.imageIds.includes(i.id));
        if(over.description!==null)d.media.contents=[{id:previous?.media.contents.find(v=>v.optionId==null&&v.type==='HTML')?.id||uuid(),type:'HTML',value:over.description,optionId:null}];
        if(market==='COUPANG'){
            const selected=addressDeliveryValues(c.addressBook),native={outboundCode:'outboundShippingPlaceCode',returnCode:'returnCenterCode',returnName:'returnChargeName',returnContact:'companyContactNumber',returnZip:'returnZipCode',returnAddress:'returnAddress',returnAddressDetail:'returnAddressDetail'};
            Object.assign(d.delivery,selected);if(c.coupang)c.coupang.delivery=(c.coupang.delivery||[]).filter(v=>!Object.keys(selected).some(k=>native[k]===v.name));c.addressBook=null;
            const existing=clone(c.coupang||{});existing.settings??=[];for(const key of ['saleStartedAt','saleEndedAt'])if(d.common[key]&&!existing.settings.some(v=>v.name===key))existing.settings.push({name:key,value:d.common[key]});const defaults={maximumBuyForPerson:'0',maximumBuyForPersonPeriod:'1',unitCount:'1',outboundShippingTimeDay:d.delivery.shippingDays||'1',parallelImported:'NOT_PARALLEL_IMPORTED',overseasPurchased:'NOT_OVERSEAS_PURCHASED',pccNeeded:'false'};
            c.coupang={sellerProductId:null,delivery:existing.delivery||[],settings:existing.settings||[],documents:existing.documents||[],source:null,options:d.options.map(o=>{
                const row=existing.options?.find(v=>v.optionId===o.id),attributes=clone(row?.attributes||[]);
                for(const a of o.attributes||[]){const mapped=attributes.find(v=>v.name===a.name);if(mapped){if(mapped.exposed==='EXPOSED')mapped.value=a.value;}else attributes.push({...a,exposed:'EXPOSED'});}
                const registration=clone(row?.registration||Object.entries(defaults).map(([name,value])=>({name,value})));if(o.originalPrice!==''){const original=registration.find(v=>v.name==='originalPrice');if(original)original.value=o.originalPrice;else registration.push({name:'originalPrice',value:o.originalPrice});}
                return row?{...row,registration,attributes}:{optionId:o.id,sellerProductItemId:null,vendorItemId:null,registration,attributes,notices:[],certifications:[]};
            })};
        }
        c.overrides={...config().overrides,productName:market==='COUPANG'?(over.productName??base.common.name):over.productName};
        return d;
    }
    function createTarget(reference,market,previous=null){
        const baseline=newMarketDocument(reference,market,previous?.baseline),entry={market,mode:'CREATE',status:'READY',observedAt:null,error:null,baseline,working:clone(baseline),changes:new Map(),createOverrides:new Map(),reviewRequired:new Map(),refreshError:null};
        if(market==='LOTTEON'){const L=typeof module!=='undefined'?require('./lotteon-editor-model.js'):window.LotteonEditorModel,merged=normalize(reference);if(previous?.working?.markets.LOTTEON.lotteon)merged.markets.LOTTEON.lotteon=clone(previous.working.markets.LOTTEON.lotteon);entry.working=L.project(merged);for(const c of L.diff(L.input(baseline),L.input(entry.working)))recordChange(entry,c);return entry;}
        if(market==='ELEVENST'){
            const E=typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel,merged=normalize(reference),prior=previous?.working?.markets.ELEVENST.elevenst;
            if(prior)merged.markets.ELEVENST.elevenst=clone(prior);
            entry.working=E.project(merged);for(const c of E.diff(E.input(baseline),E.input(entry.working)))recordChange(entry,c);return entry;
        }
        if(market==='NAVER'){
            if(!previous?.working?.markets.NAVER.naver?.editorInput){entry.working=N.project(normalize(reference));for(const change of N.changes(baseline.markets.NAVER.naver.editorInput,entry.working.markets.NAVER.naver.editorInput))recordChange(entry,change);return entry;}
            const merged=normalize(reference),prior=previous.working.markets.NAVER.naver;
            merged.markets.NAVER.naver={...merged.markets.NAVER.naver,editorInput:clone(prior.editorInput),editorState:clone(prior.editorState)};
            entry.working=N.project(merged);
            for(const change of N.changes(baseline.markets.NAVER.naver.editorInput,entry.working.markets.NAVER.naver.editorInput))recordChange(entry,change);
            return entry;
        }
        for(const change of (previous?.createOverrides||previous?.changes)?.values()||[]){
            if(change.optionId&&!baseline.options.some(o=>o.id===change.optionId))continue;
            try{let applied=change;
                if(change.path===brandSettingsPath){const prior=changeValue(previous.baseline,change)||[],latest=changeValue(baseline,change)||[],desired=change.value||[],names=new Set([...prior,...desired].map(v=>v.name)),merged=clone(latest);for(const name of names)if(!equal(prior.find(v=>v.name===name),desired.find(v=>v.name===name))){const index=merged.findIndex(v=>v.name===name);if(index>=0)merged.splice(index,1);const row=desired.find(v=>v.name===name);if(row)merged.push(clone(row));}applied={...change,value:merged};}
                writeChange(entry.working,applied);if(change.path==='common.brand')entry.working.markets[market].overrides.brand=change.value;recordChange(entry,applied);entry.createOverrides.set(changeKey(applied),clone(applied));}catch{entry.refreshError='옵션 연결이 변경되었습니다. 입력을 확인해 주세요.';}
        }
        return entry;
    }
    function syncCreateTargets(state,reference){
        state.reference=normalize(reference);
        for(const [market,entry] of state.targets)if(entry.mode==='CREATE'&&entry.status==='READY'&&entry.listing?.state!=='RECREATE_READY')state.targets.set(market,createTarget(reference,market,entry));
        return state;
    }
    function changeForPath(document,path){
        const parts=path.split('.');
        if(parts[0]==='options'&&/^\d+$/.test(parts[1]||''))return {path:'options.'+parts[2],optionId:document.options[Number(parts[1])]?.id};
        if(parts[0]==='media')return {path:'media.'+parts[1],optionId:document.media[parts[1]][Number(parts[2])]?.optionId??null};
        if(parts[0]==='markets'&&parts[2]==='coupang'){
            if(parts[3]==='options')return {path:'markets.COUPANG.coupang.options.'+parts[5],optionId:document.markets.COUPANG.coupang.options[Number(parts[4])]?.optionId};
            return {path:parts.slice(0,4).join('.'),optionId:null};
        }
        return {path,optionId:null};
    }
    function fieldCollection(change){
        const match=/^(markets\.COUPANG\.coupang\.(?:settings|delivery|options\.registration))\.(.+)$/.exec(change.path);
        return match?{path:match[1],name:match[2],optionId:change.optionId??null}:null;
    }
    function changeValue(document,change){
        if(change.optionId&&change.path.startsWith(N.prefix+'options.'))return document.markets.NAVER.naver.editorInput.options.find(o=>o.id===change.optionId)?.[change.path.slice((N.prefix+'options.').length)];
        if(change.optionId&&change.path.startsWith('partner.options.'))return (typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel).input(document).options.find(o=>o.id===change.optionId)?.[change.path.slice(16)];
        if(change.path.startsWith('lotteon.'))return (typeof module!=='undefined'?require('./lotteon-editor-model.js'):window.LotteonEditorModel).value(document,change.path);
        if(change.path.startsWith('partner.'))return (typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel).value(document,change.path);
        if(change.path.startsWith(N.prefix))return N.value(document,change.path);
        const field=fieldCollection(change);if(field)return changeValue(document,field)?.find(v=>v.name===field.name)?.value??'';
        if(change.path.startsWith('options.'))return document.options.find(o=>o.id===change.optionId)?.[change.path.slice(8)];
        if(change.path.startsWith('media.'))return (document.media[change.path.slice(6)]||[]).filter(v=>(v.optionId??null)===(change.optionId??null));
        if(change.path.startsWith('markets.COUPANG.coupang.options.'))return document.markets.COUPANG.coupang?.options.find(o=>o.optionId===change.optionId)?.[change.path.split('.').at(-1)];
        return change.path.split('.').reduce((v,k)=>v?.[k],document);
    }
    function writeChange(document,change){
        if(change.optionId&&(change.path.startsWith(N.prefix+'options.')||change.path.startsWith('partner.options.'))){const input=change.path.startsWith('partner.')?(typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel).input(document):document.markets.NAVER.naver.editorInput,row=input.options.find(o=>o.id===change.optionId);if(!row)throw Error('옵션 연결을 확인해 주세요.');row[change.path.split('.').at(-1)]=clone(change.value);return;}
        if(change.path.startsWith('lotteon.'))return (typeof module!=='undefined'?require('./lotteon-editor-model.js'):window.LotteonEditorModel).write(document,change);
        if(change.path.startsWith('partner.'))return (typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel).write(document,change);
        const value=clone(change.value);
        const field=fieldCollection(change);if(field){const rows=changeValue(document,field),row=rows.find(v=>v.name===field.name);if(row)row.value=value;else rows.push({name:field.name,value});return;}
        if(change.path.startsWith(N.prefix)){N.write(document,change);return;}
        if(change.path.startsWith('options.')){const option=document.options.find(o=>o.id===change.optionId);if(!option)throw Error('옵션 연결을 확인해 주세요.');option[change.path.slice(8)]=value;return;}
        if(change.path.startsWith('media.')){const key=change.path.slice(6);document.media[key]=document.media[key].filter(v=>(v.optionId??null)!==(change.optionId??null)).concat(value||[]);if(change.optionId){const mode=key==='images'?'imageOverrides':'contentOverrides';(document.media[mode]??={})[change.optionId]=true;}return;}
        if(change.path.startsWith('markets.COUPANG.coupang.options.')){const option=document.markets.COUPANG.coupang?.options.find(o=>o.optionId===change.optionId);if(!option)throw Error('옵션 연결을 확인해 주세요.');option[change.path.split('.').at(-1)]=value;return;}
        const keys=change.path.split('.'),key=keys.pop(),parent=keys.reduce((v,k)=>v[k]??={},document);parent[key]=value;
    }
    function sessionState(reference,session,previous=null){
        const state={reference:normalize(reference),session:clone(session),targets:new Map()};
        for(const target of session.targets||[]){
            const old=previous?.targets.get(target.market),baseline=target.document?normalize(target.document):null,oldBasis=old?.baseline||old?.lastBaseline;
            const entry={...clone(target),baseline,lastBaseline:baseline||oldBasis||null,working:baseline?clone(baseline):old?.working?clone(old.working):null,changes:new Map(),createOverrides:new Map(target.mode==='CREATE'?old?.createOverrides||old?.changes||[]:[]),reviewRequired:new Map(old?.reviewRequired||[]),resolvedConflicts:new Set(),refreshError:null};
            const pending=new Map((old?.baseline?[]:target.pendingChanges||[]).map(c=>[changeKey(c),c]));
            for(const c of (target.mode==='CREATE'?old?.createOverrides||old?.changes:old?.changes)?.values()||[])pending.set(changeKey(c),c);
            for(const conflict of target.conflicts||[])entry.reviewRequired.set(changeKey(conflict),{before:clone(conflict.before),current:clone(conflict.current),desired:clone(conflict.desired)});
            for(const change of pending.values()){
                if(entry.working){try{writeChange(entry.working,change);if(target.mode==='CREATE')entry.createOverrides.set(changeKey(change),clone(change));const key=changeKey(change);if(!baseline||!equal(changeValue(baseline,change),change.value,change.path)){entry.changes.set(key,clone(change));if(target.mode==='UPDATE'&&baseline&&oldBasis&&!equal(changeValue(oldBasis,change),changeValue(baseline,change),change.path))entry.reviewRequired.set(key,{before:clone(changeValue(oldBasis,change)),current:clone(changeValue(baseline,change))});}else entry.reviewRequired.delete(key);}catch{entry.refreshError='옵션 연결이 변경되었습니다. 입력을 확인해 주세요.';}}
            }
            state.targets.set(target.market,entry);
        }
        return state;
    }
    function recordChange(entry,change){
        if(entry.mode==='UPDATE'&&[N.prefix+'options','partner.options'].includes(change.path)){
            const before=changeValue(entry.baseline,change)||[],after=changeValue(entry.working,change)||[],names=change.path.startsWith('partner.')?['name','sku','price','quantity','attributes','usable']:['values','price','stockQuantity','sellerManagerCode','usable'];
            entry.changes.delete(changeKey(change));for(const row of after)if(before.some(o=>o.id===row.id))for(const name of names)recordChange(entry,{path:change.path+'.'+name,optionId:row.id});return;
        }
        if(['markets.COUPANG.coupang.settings','markets.COUPANG.coupang.delivery','markets.COUPANG.coupang.options.registration'].includes(change.path)){
            const before=changeValue(entry.baseline,change)||[],after=changeValue(entry.working,change)||[];
            entry.changes.delete(changeKey(change));entry.createOverrides?.delete(changeKey(change));
            for(const name of new Set([...before,...after].map(row=>row.name))){const field={path:change.path+'.'+name,optionId:change.optionId??null},existing=entry.createOverrides?.get(changeKey(field));if(existing&&equal(existing.value,changeValue(entry.working,field),field.path))continue;recordChange(entry,field);}
            return;
        }
        const proposal={...change,value:clone(changeValue(entry.working,change))};
        if(entry.baseline&&equal(changeValue(entry.baseline,proposal),proposal.value,proposal.path)){entry.changes.delete(changeKey(proposal));entry.createOverrides?.delete(changeKey(proposal));entry.reviewRequired?.delete(changeKey(proposal));}else {entry.changes.set(changeKey(proposal),proposal);if(entry.mode==='CREATE')(entry.createOverrides??=new Map()).set(changeKey(proposal),clone(proposal));}
    }
    function resetChange(entry,change){
        if(!entry.baseline)return;
        if(change.optionId&&(change.path.startsWith(N.prefix+'options.')||change.path.startsWith('partner.options.'))){writeChange(entry.working,{...change,value:changeValue(entry.baseline,change)});recordChange(entry,change);return;}
        if(change.path.startsWith(N.prefix)){
            N.reset(entry.working,entry.baseline,change);
            if(entry.mode==='CREATE')entry.working=N.project(entry.working);
            entry.changes.clear();entry.createOverrides?.clear();
            for(const c of N.changes(entry.baseline.markets.NAVER.naver.editorInput,entry.working.markets.NAVER.naver.editorInput))recordChange(entry,c);
        }else writeChange(entry.working,{...change,value:changeValue(entry.baseline,change)});
        if(change.optionId&&change.path.startsWith('media.')){const mode=change.path==='media.images'?'imageOverrides':'contentOverrides';if(entry.baseline.media[mode]?.[change.optionId]===undefined)delete entry.working.media[mode]?.[change.optionId];else (entry.working.media[mode]??={})[change.optionId]=entry.baseline.media[mode][change.optionId];}
        if(!change.path.startsWith(N.prefix)){entry.changes.delete(changeKey(change));entry.createOverrides?.delete(changeKey(change));}
        entry.reviewRequired?.delete(changeKey(change));
    }
    function applyReference(state,market,changes){
        const entry=state.targets.get(market);if(!entry?.baseline||!entry.working||entry.status!=='READY')throw Error('마켓의 최신 값을 먼저 조회해 주세요.');
        if(market==='LOTTEON'){const L=typeof module!=='undefined'?require('./lotteon-editor-model.js'):window.LotteonEditorModel,before=clone(L.input(entry.working)),after=L.applyCommon(state.reference,before,changes);L.mark(entry.working,before,after);for(const c of L.diff(before,after))recordChange(entry,c);return;}
        if(market==='ELEVENST'){const E=typeof module!=='undefined'?require('./elevenst-editor-model.js'):window.ElevenstEditorModel,before=clone(E.input(entry.working)),after=E.applyCommon(state.reference,before,changes);entry.working.markets.ELEVENST.elevenst.editorInput=after;for(const c of E.diff(before,after))recordChange(entry,c);return;}
        if(market==='NAVER'){
            const before=clone(entry.working.markets.NAVER.naver.editorInput),after=N.applyCommon(state.reference,before,changes);
            N.edited(entry.working,before,after);
            for(const change of N.changes(before,after))recordChange(entry,change);
            return;
        }
        if(market==='COUPANG'){
            const expanded=[];for(const c of changes){const key=c.path.slice(c.path.indexOf('.')+1);
                if(c.path.startsWith('common.')&&['saleStartedAt','saleEndedAt'].includes(key)){expanded.push({path:'markets.COUPANG.coupang.settings.'+key,optionId:null,value:state.reference.common[key]});continue;}
                if(c.path==='delivery.shippingDays'){for(const row of entry.working.markets.COUPANG.coupang.options)expanded.push({path:'markets.COUPANG.coupang.options.registration.outboundShippingTimeDay',optionId:row.optionId,value:state.reference.delivery.shippingDays});continue;}
                if(c.path.startsWith('common.afterService')){let found=false;const S=typeof module!=='undefined'?require('./marketplace-common-settings.js'):window.MarketplaceCommonSettings;for(const row of entry.working.markets.COUPANG.coupang.options){const notices=clone(row.notices||[]);for(const n of notices)if(/A\/S|AS 책임/.test(n.name)){n.content=S.serviceContact(state.reference.common);found=true;}if(notices.some(n=>/A\/S|AS 책임/.test(n.name)))expanded.push({path:'markets.COUPANG.coupang.options.notices',optionId:row.optionId,value:notices});}if(!found)throw Error('쿠팡 상품고시에서 A/S 항목을 먼저 선택해 주세요.');continue;}
                expanded.push(c);
            }changes=expanded.filter((c,n,a)=>a.findIndex(v=>changeKey(v)===changeKey(c))===n);
        }
        changes=changes.map(c=>c.path==='options.originalPrice'&&market==='COUPANG'?{path:'markets.COUPANG.coupang.options.registration.originalPrice',optionId:c.optionId,value:state.reference.options.find(o=>o.id===c.optionId)?.originalPrice}:c);
        const candidate=clone(entry.working);
        for(const change of changes){if(entry.mode==='UPDATE'&&['options.price','options.quantity','productQuantity'].includes(change.path)){const value=changeValue(entry.baseline,change);if(value==null||value==='')throw Error('현재 가격·재고 미확인 항목에는 공통값을 적용할 수 없습니다.');}const referenceValue=Object.hasOwn(change,'value')?change.value:change.path==='markets.COUPANG.overrides.productName'?(state.reference.markets.COUPANG.overrides.productName??state.reference.common.name):changeValue(state.reference,change);writeChange(candidate,{...change,value:referenceValue});}
        entry.working=candidate;
        for(const change of changes)recordChange(entry,change);
    }
    const brandSettingsPath='markets.COUPANG.coupang.settings';
    const settingBrand=d=>d.markets.COUPANG.coupang?.settings?.find(v=>v.name==='brandId')?.value??'';
    const naverBrandPaths=['brandName','brandId'].map(key=>'markets.NAVER.naver.editorInput.fields.originProduct.detailAttribute.naverShoppingSearchInfo.'+key);
    function comparisonRows(entry){
        const naverChanges=naverBrandPaths.map(path=>entry.changes.get(changeKey({path,optionId:null}))).filter(Boolean);
        const rows=[],brandChange=[...entry.changes.values()].find(c=>c.path==='common.brand'),settings=[...entry.changes.values()].find(c=>(c.path===brandSettingsPath||c.path===brandSettingsPath+'.brandId'));
        const brandIdChanged=settings&&!equal(settingBrand(entry.baseline),settingBrand(entry.working));
        if(brandChange||brandIdChanged){const keys=[brandChange,brandIdChanged?settings:null].filter(Boolean).map(changeKey);rows.push({key:'brand',keys,label:'브랜드',brand:true,change:brandChange||settings,before:{이름:entry.baseline.common.brand,ID:settingBrand(entry.baseline)},after:{이름:entry.working.common.brand,ID:settingBrand(entry.working)}});}
        if(entry.market==='NAVER'&&naverChanges.length){const values=document=>({이름:changeValue(document,{path:naverBrandPaths[0]})??'',ID:changeValue(document,{path:naverBrandPaths[1]})??''});rows.push({key:'naver-brand',keys:naverChanges.map(changeKey),label:'브랜드',naverBrand:true,change:naverChanges[0],before:values(entry.baseline),after:values(entry.working)});}
        for(const change of entry.changes.values()){
            if(change===brandChange||naverChanges.includes(change)||change.path===brandSettingsPath+'.brandId')continue;
            const before=changeValue(entry.baseline,change),after=changeValue(entry.working,change),key=changeKey(change);
            if(Array.isArray(before)&&Array.isArray(after)&&[...before,...after].every(v=>v&&typeof v==='object'&&typeof v.name==='string')){
                for(const name of new Set([...before,...after].map(v=>v.name))){if(change.path===brandSettingsPath&&name==='brandId')continue;const old=before.find(v=>v.name===name),next=after.find(v=>v.name===name);if(equal(old,next))continue;rows.push({key:key+':'+name,keys:[key],change,itemName:name,label:name,before:old?.value??old?.content??old,after:next?.value??next?.content??next});}
            }else rows.push({key,keys:[key],change,label:fieldCollection(change)?.name,before,after});
        }
        return rows;
    }
    function resetComparison(entry,row){
        if(row.naverBrand){
            const config=entry.working.markets.NAVER.naver,meta=N.state(config);
            meta.overrides=meta.overrides.filter(key=>!naverBrandPaths.some(path=>key===path.slice(N.prefix.length)));config.editorState=meta;
            for(const path of naverBrandPaths){const change={path,optionId:null};writeChange(entry.working,{...change,value:changeValue(entry.baseline,change)??null});recordChange(entry,change);}
        }else if(row.brand){
            const brandKey=changeKey({path:brandSettingsPath+'.brandId',optionId:null});entry.createOverrides?.delete(brandKey);entry.changes.delete(brandKey);
            entry.working.common.brand=entry.baseline.common.brand;
            entry.working.markets.COUPANG.overrides.brand=entry.baseline.markets.COUPANG.overrides.brand;
            const settings=entry.working.markets.COUPANG.coupang.settings,base=entry.baseline.markets.COUPANG.coupang?.settings?.find(v=>v.name==='brandId');
            entry.working.markets.COUPANG.coupang.settings=settings.filter(v=>v.name!=='brandId');if(base)entry.working.markets.COUPANG.coupang.settings.push(clone(base));const order=(entry.baseline.markets.COUPANG.coupang?.settings||[]).map(v=>v.name);entry.working.markets.COUPANG.coupang.settings.sort((a,b)=>(order.indexOf(a.name)<0?order.length:order.indexOf(a.name))-(order.indexOf(b.name)<0?order.length:order.indexOf(b.name)));
            for(const path of ['common.brand',brandSettingsPath,brandSettingsPath+'.brandId']){if(path.endsWith('.brandId')){entry.changes.delete(changeKey({path,optionId:null}));entry.createOverrides?.delete(changeKey({path,optionId:null}));}else recordChange(entry,{path,optionId:null});}
        }else if(row.itemName!==undefined){const values=changeValue(entry.working,row.change).filter(v=>v.name!==row.itemName),base=changeValue(entry.baseline,row.change).find(v=>v.name===row.itemName);if(base)values.push(clone(base));const order=changeValue(entry.baseline,row.change).map(v=>v.name);values.sort((a,b)=>(order.indexOf(a.name)<0?order.length:order.indexOf(a.name))-(order.indexOf(b.name)<0?order.length:order.indexOf(b.name)));writeChange(entry.working,{...row.change,value:values});recordChange(entry,row.change);}
        else resetChange(entry,row.change);
        for(const key of row.keys)if(!entry.changes.has(key))entry.reviewRequired.delete(key);
    }
    function applyCategorySelections(document,selections){
        for(const [market,value] of Object.entries(selections||{})){
            if(!['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(market)||!document.markets[market])throw Error('카테고리 마켓을 확인해 주세요.');
            if(value!==null&&(!value||typeof value.id!=='string'||!(market==='LOTTEON'?/^[A-Za-z0-9_-]{1,100}$/:/^\d{1,15}$/).test(value.id)))throw Error('카테고리를 검색해서 선택해 주세요.');
            const config=document.markets[market];if(market==='COUPANG'&&config.coupang?.sellerProductId)throw Error('기존 쿠팡 상품의 카테고리는 변경할 수 없습니다.');
            config.categoryCode=value?.id||'';
        }
        return document;
    }
    function proposals(state,selected){return [...state.targets.values()].filter(t=>selected.includes(t.market)).map(t=>({market:t.market,changes:[...t.changes.values()].map(clone)}));}
    function validateChanges(entry){const errors=[];for(const change of entry.changes.values()){const fail=message=>errors.push({market:entry.market,path:change.path,message});if(['options.price','options.quantity','productQuantity'].includes(change.path)){if(!integer(change.value))fail('가격·수량은 0 이상의 정수로 입력해 주세요.');if(entry.mode==='UPDATE'&&(changeValue(entry.baseline,change)==null||changeValue(entry.baseline,change)===''))fail('현재 가격·재고를 먼저 확인해 주세요.');}if(change.path==='media.images')for(const image of change.value||[])if(!image.assetId&&!url(image.url))fail('이미지에 유효한 HTTPS 주소가 필요합니다.');if(change.path==='media.contents')for(const content of change.value||[])if(content.type==='IMAGE'&&!content.assetId&&content.value&&!url(content.value))fail('설명 이미지에 유효한 HTTPS 주소가 필요합니다.');}return errors;}
    function afterResolution(previous,session,market,fields,applied){
        const targets=new Map(previous.targets);if(!applied)return {targets};
        const old=targets.get(market);if(!old)return {targets};const selected=new Set(fields.map(changeKey));
        targets.set(market,{...old,changes:new Map([...old.changes].filter(([key])=>!selected.has(key))),reviewRequired:new Map([...old.reviewRequired].filter(([key])=>!selected.has(key))),createOverrides:new Map([...old.createOverrides||[]].filter(([key])=>!selected.has(key)))});
        // The server owns selected fields and pending intent; keep local edits to all other fields.
        const remote=session.targets.find(t=>t.market===market),entry=targets.get(market);for(const c of remote?.pendingChanges||[])if(selected.has(changeKey(c)))entry.changes.set(changeKey(c),clone(c));
        return {targets};
    }
    const model={afterResolution,comparisonRows,resetComparison,channels,markets,commonKeys,deliveryKeys,uuid,blankOption,config,fresh,normalize,inherits,optionOverride,resolved,brandSelection,brandLocked,setCommonBrand,applyBrandSelections,applyCategorySelections,applyAddressSelections,addressDeliveryValues,toggle,removeOption,materializeMedia,imagesFor,setRepresentative,moveImage,removeImage,url,integer,validate,changeKey,equal,newMarketDocument,createTarget,syncCreateTargets,changeForPath,changeValue,writeChange,sessionState,recordChange,resetChange,applyReference,proposals,validateChanges};
    if(typeof module!=='undefined')module.exports=model;else window.MarketplaceEditorModel=model;
})();
