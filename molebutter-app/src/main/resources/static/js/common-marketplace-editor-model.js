/* Internal sales drafts. These documents are never external marketplace payloads. */
(() => {
    'use strict';
    const channels=globalThis.MarketplaceChannels||[],markets=channels.filter(c=>c.commonDraft).map(c=>[c.id,c.label]);
    const uuid=typeof module!=='undefined'?require('./app-ui.js').uuid:AppUI.uuid;
    const commonKeys=['productCode','name','productName','brand','manufacturer','origin','material','model','afterService','taxType','adultOnly'];
    const deliveryKeys=['method','carrier','chargeType','charge','freeOver','returnCharge','initialReturnCharge','remoteArea','bundle','outboundCode','returnCode','returnName','returnContact','returnZip','returnAddress','returnAddressDetail'];
    const blankOption=()=>({id:uuid(),name:'',sku:'',price:'',quantity:'',attributes:[]});
    const config=()=>({categoryCode:'',overrides:{name:null,productName:null,brand:null,options:[],imageIds:null,description:null},coupang:null,naver:null,esm:null});
    function fresh(market='COUPANG'){
        return {id:null,revision:null,common:Object.fromEntries(commonKeys.map(k=>[k,k==='taxType'?'TAX':k==='adultOnly'?'EVERYONE':''])),options:[blankOption()],stockMode:'OPTION',productQuantity:'',services:[],media:{images:[],contents:[]},delivery:Object.fromEntries(deliveryKeys.map(k=>[k,''])),selectedMarkets:markets.some(([key])=>key===market)?[market]:['COUPANG'],markets:Object.fromEntries(markets.map(([key])=>[key,config()]))};
    }
    function normalize(document){
        const d=structuredClone(document),blank=fresh();
        d.common={...blank.common,...d.common};d.delivery={...blank.delivery,...d.delivery};d.media={images:[],contents:[],...d.media};d.options=(d.options||[]).map(o=>({...o,attributes:o.attributes||[]}));d.services=d.services||[];d.selectedMarkets=d.selectedMarkets||[];d.markets=d.markets||{};
        for(const [key] of markets){const c=d.markets[key]||{};d.markets[key]={...config(),...c,overrides:{...config().overrides,...c.overrides,options:c.overrides?.options||[]}};}
        return d;
    }
    function optionOverride(d,market,optionId,create=false){
        const list=d.markets[market].overrides.options;let entry=list.find(o=>o.optionId===optionId);
        if(!entry&&create){entry={optionId,name:null,sku:null,price:null,quantity:null};list.push(entry);}return entry;
    }
    const inherits=v=>v===null||v===undefined;
    function resolved(d,market,key,optionId){const o=optionId?d.options.find(o=>o.id===optionId):d.common;const v=optionId?optionOverride(d,market,optionId)?.[key]:d.markets[market].overrides[key];return inherits(v)?o?.[key]:v;}
    function toggle(d,market,key,on,optionId){const obj=optionId?optionOverride(d,market,optionId,true):d.markets[market].overrides;obj[key]=on?resolved(d,market,key,optionId)??'':null;}
    function removeOption(d,id){
        if(d.options.length<=1)return false;
        d.options=d.options.filter(o=>o.id!==id);
        for(const c of Object.values(d.markets)){if(c.overrides)c.overrides.options=(c.overrides.options||[]).filter(o=>o.optionId!==id);if(c.coupang)c.coupang.options=(c.coupang.options||[]).filter(o=>o.optionId!==id);}
        const removedImages=new Set(d.media.images.filter(i=>i.optionId===id).map(i=>i.id));
        d.media.images=d.media.images.filter(i=>!removedImages.has(i.id));d.media.contents=d.media.contents.filter(c=>c.optionId!==id);
        for(const c of Object.values(d.markets))if(c.overrides?.imageIds)c.overrides.imageIds=c.overrides.imageIds.filter(imageId=>!removedImages.has(imageId));
        return true;
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
        d.options.forEach((o,n)=>{if(ids.has(o.id))fail('options.'+n,'옵션 식별자를 확인해 주세요.');ids.add(o.id);if(complete&&!o.name.trim())fail('options.'+n+'.name','옵션명을 입력해 주세요.');for(const key of ['price','quantity']){const v=o[key];if(key==='quantity'&&d.stockMode==='PRODUCT')continue;if(!inherits(v)&&v!==''&&!integer(v))fail('options.'+n+'.'+key,'가격·수량은 0 이상의 정수로 입력해 주세요.');if(complete&&(v===null||v===''))fail('options.'+n+'.'+key,(key==='price'?'판매가':'수량')+'를 입력해 주세요.');}});
        if(d.stockMode==='PRODUCT'){if(d.productQuantity!==''&&!integer(d.productQuantity))fail('productQuantity','상품 공통 수량은 0 이상의 정수로 입력해 주세요.');if(complete&&!d.productQuantity)fail('productQuantity','상품 공통 수량을 입력해 주세요.');}
        for(const [key,c] of Object.entries(d.markets)){if(!d.selectedMarkets.includes(key))continue;for(const o of c.overrides.options||[])for(const field of ['price','quantity'])if(!inherits(o[field])&&o[field]!==''&&!integer(o[field]))errors.push({market:key,path:'markets.'+key+'.overrides.options',message:'마켓별 가격·수량은 0 이상의 정수로 입력해 주세요.'});}
        for(const [n,i] of d.media.images.entries())if(!i.assetId&&!url(i.url))fail('media.images.'+n,'이미지에 HTTPS 주소가 필요합니다.');
        for(const [n,c] of d.media.contents.entries())if(c.type==='IMAGE'&&c.value&&!url(c.value))fail('media.contents.'+n+'.value','설명 이미지에 HTTPS 주소가 필요합니다.');
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
    function changeValue(document,change){
        if(change.path.startsWith('options.'))return document.options.find(o=>o.id===change.optionId)?.[change.path.slice(8)];
        if(change.path.startsWith('media.'))return (document.media[change.path.slice(6)]||[]).filter(v=>(v.optionId??null)===(change.optionId??null));
        if(change.path.startsWith('markets.COUPANG.coupang.options.'))return document.markets.COUPANG.coupang?.options.find(o=>o.optionId===change.optionId)?.[change.path.split('.').at(-1)];
        return change.path.split('.').reduce((v,k)=>v?.[k],document);
    }
    function writeChange(document,change){
        const value=clone(change.value);
        if(change.path.startsWith('options.')){const option=document.options.find(o=>o.id===change.optionId);if(!option)throw Error('옵션 연결을 확인해 주세요.');option[change.path.slice(8)]=value;return;}
        if(change.path.startsWith('media.')){const key=change.path.slice(6);document.media[key]=document.media[key].filter(v=>(v.optionId??null)!==(change.optionId??null)).concat(value||[]);return;}
        if(change.path.startsWith('markets.COUPANG.coupang.options.')){const option=document.markets.COUPANG.coupang?.options.find(o=>o.optionId===change.optionId);if(!option)throw Error('옵션 연결을 확인해 주세요.');option[change.path.split('.').at(-1)]=value;return;}
        const keys=change.path.split('.'),key=keys.pop(),parent=keys.reduce((v,k)=>v[k]??={},document);parent[key]=value;
    }
    function sessionState(reference,session,previous=null){
        const state={reference:normalize(reference),session:clone(session),targets:new Map()};
        for(const target of session.targets||[]){
            const old=previous?.targets.get(target.market),baseline=target.document?normalize(target.document):null;
            const entry={...clone(target),baseline,working:baseline?clone(baseline):old?.working?clone(old.working):null,changes:new Map(),reviewRequired:new Map(old?.reviewRequired||[]),refreshError:null};
            if(old)for(const change of old.changes.values()){
                if(entry.working){try{writeChange(entry.working,change);const key=changeKey(change);if(!baseline||!equal(changeValue(baseline,change),change.value,change.path)){entry.changes.set(key,clone(change));if(target.mode==='UPDATE'&&baseline&&old.baseline&&!equal(changeValue(old.baseline,change),changeValue(baseline,change),change.path))entry.reviewRequired.set(key,{before:clone(changeValue(old.baseline,change)),current:clone(changeValue(baseline,change))});}else entry.reviewRequired.delete(key);}catch{entry.refreshError='옵션 연결이 변경되었습니다. 입력을 확인해 주세요.';}}
            }
            state.targets.set(target.market,entry);
        }
        return state;
    }
    function recordChange(entry,change){
        const proposal={...change,value:clone(changeValue(entry.working,change))};
        if(entry.baseline&&equal(changeValue(entry.baseline,proposal),proposal.value,proposal.path)){entry.changes.delete(changeKey(proposal));entry.reviewRequired?.delete(changeKey(proposal));}else entry.changes.set(changeKey(proposal),proposal);
    }
    function resetChange(entry,change){if(!entry.baseline)return;writeChange(entry.working,{...change,value:changeValue(entry.baseline,change)});entry.changes.delete(changeKey(change));entry.reviewRequired?.delete(changeKey(change));}
    function applyReference(state,market,changes){
        const entry=state.targets.get(market);if(!entry?.baseline||!entry.working||entry.status!=='READY')throw Error('마켓의 최신 값을 먼저 조회해 주세요.');
        const candidate=clone(entry.working);
        for(const change of changes){if(entry.mode==='UPDATE'&&['options.price','options.quantity','productQuantity'].includes(change.path)){const value=changeValue(entry.baseline,change);if(value==null||value==='')throw Error('현재 가격·재고 미확인 항목에는 공통값을 적용할 수 없습니다.');}const referenceValue=change.path==='markets.COUPANG.overrides.productName'?resolved(state.reference,'COUPANG','productName'):changeValue(state.reference,change);writeChange(candidate,{...change,value:referenceValue});}
        entry.working=candidate;
        for(const change of changes)recordChange(entry,change);
    }
    function proposals(state,selected){return [...state.targets.values()].filter(t=>selected.includes(t.market)).map(t=>({market:t.market,changes:[...t.changes.values()].map(clone)}));}
    function validateChanges(entry){const errors=[];for(const change of entry.changes.values()){const fail=message=>errors.push({market:entry.market,path:change.path,message});if(['options.price','options.quantity','productQuantity'].includes(change.path)){if(!integer(change.value))fail('가격·수량은 0 이상의 정수로 입력해 주세요.');if(entry.mode==='UPDATE'&&(changeValue(entry.baseline,change)==null||changeValue(entry.baseline,change)===''))fail('현재 가격·재고를 먼저 확인해 주세요.');}if(change.path==='media.images')for(const image of change.value||[])if(!image.assetId&&!url(image.url))fail('이미지에 유효한 HTTPS 주소가 필요합니다.');if(change.path==='media.contents')for(const content of change.value||[])if(content.type==='IMAGE'&&content.value&&!url(content.value))fail('설명 이미지에 유효한 HTTPS 주소가 필요합니다.');}return errors;}
    const model={channels,markets,commonKeys,deliveryKeys,uuid,blankOption,config,fresh,normalize,inherits,optionOverride,resolved,toggle,removeOption,imagesFor,setRepresentative,moveImage,removeImage,url,integer,validate,changeKey,equal,changeForPath,changeValue,writeChange,sessionState,recordChange,resetChange,applyReference,proposals,validateChanges};
    if(typeof module!=='undefined')module.exports=model;else window.MarketplaceEditorModel=model;
})();
