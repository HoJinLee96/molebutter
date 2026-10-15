/* SmartStore projection of a common reference. Provider IDs never come from this projection. */
(() => {
    'use strict';
    const N=typeof module!=='undefined'?require('./naver-product-editor-model.js'):window.NaverEditorModel;
    const CS=typeof module!=='undefined'?require('./marketplace-common-settings.js'):window.MarketplaceCommonSettings;
    const prefix='markets.NAVER.naver.editorInput.',{P,D,S}=N;
    const copy=v=>v==null?v:structuredClone(v);
    const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
    const escape=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const mapping={name:P+'name',productCode:D+'sellerCodeInfo.sellerManagementCode',manufacturer:D+'naverShoppingSearchInfo.manufacturerName',model:D+'naverShoppingSearchInfo.modelName',afterService:D+'afterServiceInfo.afterServiceGuideContent'};
    const deliveryMapping={...Object.fromEntries(Object.entries(CS.naverPaths).filter(([k])=>['method','carrier','chargeType','charge','freeOver','returnCharge','bundle','jejuCharge','remoteCharge'].includes(k))),carrier:P+'deliveryInfo.deliveryCompany',charge:P+'deliveryInfo.deliveryFee.baseFee',freeOver:P+'deliveryInfo.deliveryFee.freeConditionalAmount',returnCharge:P+'deliveryInfo.claimDeliveryInfo.returnDeliveryFee'};
    const fees={FREE:'FREE',NOT_FREE:'PAID',CHARGE_RECEIVED:'PAID',CONDITIONAL_FREE:'CONDITIONAL_FREE'};
    function state(config){
        if(config.editorState)return {version:1,overrides:[],pricing:'COMMON',optionStructure:'COMMON',imageSource:null,imageOptionId:null,descriptionSource:null,descriptionOptionId:null,unmapped:[],...copy(config.editorState)};
        if(config.editorInput)return {version:1,overrides:Object.keys(config.editorInput.fields||{}).map(p=>'fields.'+p),pricing:'CUSTOM',optionStructure:'CUSTOM',imageSource:'CUSTOM',imageOptionId:null,descriptionSource:'CUSTOM',descriptionOptionId:null,unmapped:[]};
        return {version:1,overrides:[],pricing:'COMMON',optionStructure:'COMMON',imageSource:null,imageOptionId:null,descriptionSource:null,descriptionOptionId:null,unmapped:[]};
    }
    function media(reference,kind,optionId){return (reference.media?.[kind]||[]).filter(i=>(i.optionId??null)===(optionId??null));}
    function description(rows){return rows.map(row=>row.type==='IMAGE'?`<img src="${escape(row.value)}" alt="">`:row.type==='TEXT'?`<p>${escape(row.value).replace(/\n/g,'<br>')}</p>`:row.value||'').join('\n');}
    const discountPrefix=P+'customerBenefit.immediateDiscountPolicy.discountMethod.';
    // Keep exact decimal arithmetic. Do not invent marketplace rounding for a fractional won.
    function discountedBase(base,fields){
        const raw=fields[discountPrefix+'value'];if(raw==null||raw==='')return base;
        const unit=fields[discountPrefix+'unitType'],text=String(raw);
        if(!/^\d+(?:\.\d+)?$/.test(text))return null;
        const decimals=text.split('.')[1]?.length||0,scale=10n**BigInt(decimals),value=BigInt(text.replace('.',''));
        if(value<=0n||!['WON','PERCENT'].includes(unit))return null;
        const denominator=unit==='PERCENT'?scale*100n:scale,numerator=unit==='PERCENT'?BigInt(base)*value:value;
        if(numerator%denominator!==0n)return null;
        const amount=Number(numerator/denominator);return amount<=base?base-amount:null;
    }
    function pricingError(input){
        const base=input.fields[P+'salePrice'],raw=input.fields[discountPrefix+'value'];
        return N.integer(base)&&raw!=null&&raw!==''&&discountedBase(Number(base),input.fields)===null?'즉시할인 금액·비율을 확인해 주세요. 원 미만 금액이 발생하거나 판매가를 초과하면 추가금을 자동 계산할 수 없습니다.':'';
    }
    function priceProjection(reference,input){
        const market=reference.markets.NAVER,rows=reference.options||[],price=o=>market.overrides?.options?.find(v=>v.optionId===o.id)?.price??o.price;
        const normals=rows.map(o=>o.originalPrice).filter(v=>N.integer(v)),sales=rows.map(price).filter(v=>N.integer(v));
        const hasNormal=normals.length>0,current=input.fields[P+'salePrice'];
        const base=hasNormal?Math.max(...normals.map(Number),N.integer(current)?Number(current):0):sales.length?Math.min(...sales.map(Number)):'';
        input.fields[P+'salePrice']=base;
        const discounted=base===''?null:discountedBase(base,input.fields);
        for(const option of input.options){const row=rows.find(o=>o.id===option.id),sale=row?price(row):null;option.price=discounted!==null&&N.integer(sale)?Number(sale)-discounted:'';}
        return input;
    }
    function project(reference){
        const document=copy(reference),market=document.markets.NAVER,config=market.naver??={};
        if(config.editorInput&&!config.editorState)return document;
        const meta=state(config),old=config.editorInput||N.fresh(),input=N.fromEditor({input:old});
        const fields=input.fields,overrides=new Set(meta.overrides),put=(path,value)=>{if(!overrides.has('fields.'+path))fields[path]=value;};
        const resolved=(key)=>market.overrides?.[key]??document.common[key];
        const optionValue=(o,key)=>market.overrides?.options?.find(v=>v.optionId===o.id)?.[key]??o[key];
        for(const [key,path] of Object.entries(mapping))put(path,key==='name'?resolved(key):key==='afterService'?CS.service(document.common):document.common[key]);
        if(document.common.afterServiceTelephone)put(CS.naverPaths.afterServiceTelephone,document.common.afterServiceTelephone);
        for(const key of ['saleStartedAt','saleEndedAt'])if(document.common[key])put(CS.naverPaths[key],document.common[key]+'+09:00');
        put(S+'channelProductName',config.channelProductName?.trim()?config.channelProductName:document.common.name);
        put(D+'naverShoppingSearchInfo.brandName',resolved('brand'));
        put(D+'naverShoppingSearchInfo.brandId',config.brandId??null);
        put(P+'leafCategoryId',market.categoryCode||'');
        for(const [kind,key] of [['outbound','shippingAddressId'],['returns','returnAddressId']])if(market.addressBook?.[kind])put(P+'deliveryInfo.claimDeliveryInfo.'+key,market.addressBook[kind].code);
        if(!config.editorInput&&config.originCode)put(D+'originAreaInfo.originAreaCode',config.originCode);
        put(D+'taxType',document.common.taxType==='FREE'?'DUTYFREE':document.common.taxType||'TAX');
        put(D+'minorPurchasable',document.common.adultOnly!=='ADULT_ONLY');
        if(!config.editorInput&&config.afterServiceTelephone)put(D+'afterServiceInfo.afterServiceTelephoneNumber',config.afterServiceTelephone);
        const legacy={status:P+'statusType',saleType:P+'saleType',naverShoppingRegistration:S+'naverShoppingRegistration',channelProductDisplayStatusType:S+'channelProductDisplayStatusType'};
        for(const [key,path] of Object.entries(legacy))if(!config.editorInput&&config[key]!=null&&config[key]!=='')put(path,config[key]);
        const delivery=document.delivery||{};
        for(const key of ['returnCharge'])if(delivery[key]!=null&&delivery[key]!=='')put(deliveryMapping[key],delivery[key]);
        const managedUnmapped=['method','carrier','chargeType','outboundCode','returnCode','initialReturnCharge','remoteArea','shippingDays','bundle'].map(k=>'delivery.'+k),unmapped=new Set((meta.unmapped||[]).filter(k=>!managedUnmapped.includes(k))),carriers=['CJGLS','HYUNDAI','HANJIN','KGB','EPOST','KDEXP'];
        if(carriers.includes(delivery.carrier))put(deliveryMapping.carrier,delivery.carrier);else if(delivery.carrier)unmapped.add('delivery.carrier');
        if(delivery.method&&!['SEQUENCIAL','VENDOR_DIRECT'].includes(delivery.method))unmapped.add('delivery.method');
        if(delivery.chargeType&&!fees[delivery.chargeType])unmapped.add('delivery.chargeType');
        for(const key of ['outboundCode','returnCode','initialReturnCharge','remoteArea','shippingDays'])if(delivery[key])unmapped.add('delivery.'+key);
        meta.unmapped=[...unmapped];
        if(delivery.bundle)put(P+'deliveryInfo.deliveryBundleGroupUsable',delivery.bundle==='UNION_DELIVERY');
        if(delivery.jejuCharge!==''&&delivery.jejuCharge!=null||delivery.remoteCharge!==''&&delivery.remoteCharge!=null){put(P+'deliveryInfo.deliveryFee.deliveryFeeByArea.deliveryAreaType','AREA_3');if(delivery.jejuCharge!==''&&delivery.jejuCharge!=null)put(CS.naverPaths.jejuCharge,delivery.jejuCharge);if(delivery.remoteCharge!==''&&delivery.remoteCharge!=null)put(CS.naverPaths.remoteCharge,delivery.remoteCharge);}
        if(delivery.method==='SEQUENCIAL'){put(P+'deliveryInfo.deliveryType','DELIVERY');put(P+'deliveryInfo.deliveryAttributeType','NORMAL');}
        if(delivery.method==='VENDOR_DIRECT')put(P+'deliveryInfo.deliveryType','DIRECT');
        if(fees[delivery.chargeType]){put(P+'deliveryInfo.deliveryFee.deliveryFeeType',fees[delivery.chargeType]);put(P+'deliveryInfo.deliveryFee.baseFee',delivery.chargeType==='FREE'?0:delivery.charge||'');put(P+'deliveryInfo.deliveryFee.deliveryFeePayType',delivery.chargeType==='CHARGE_RECEIVED'?'COLLECT':'PREPAID');put(P+'deliveryInfo.deliveryFee.freeConditionalAmount',delivery.chargeType==='CONDITIONAL_FREE'?delivery.freeOver||'':0);}
        if(config.editorInput==null)meta.unmapped=[...new Set([...(meta.unmapped||[]),...['deliveryTemplateId','channelId'].filter(k=>config[k]),...['attributes','notices'].filter(k=>config[k]?.length)])];
        const rows=document.options||[],previous=new Map(input.options.map(o=>[o.id,o]));
        if(meta.optionStructure==='COMMON'){
            input.optionMode='COMBINATION';input.optionNames=['옵션'];
            input.options=rows.map(o=>{const existing=previous.get(o.id)||{},pick=(key,value)=>overrides.has('options.'+o.id+'.'+key)?existing[key]:value;return {id:o.id,values:pick('values',[optionValue(o,'name')]),price:existing.price??0,stockQuantity:pick('stockQuantity',document.stockMode==='PRODUCT'?(rows.length===1?document.productQuantity:''):optionValue(o,'quantity')),sellerManagerCode:pick('sellerManagerCode',optionValue(o,'sku')||''),usable:pick('usable',existing.usable!==false)};});
        }else if(input.optionMode==='COMBINATION'){
            const retained=rows.map(r=>input.options.find(o=>o.id===r.id)).filter(Boolean);
            for(const o of rows)if(!retained.some(r=>r.id===o.id))retained.push({id:o.id,values:input.optionNames.map(()=>''),price:'',stockQuantity:document.stockMode==='PRODUCT'?'':o.quantity,sellerManagerCode:optionValue(o,'sku')||'',usable:true});
            input.options=retained;
            for(const o of input.options){const ref=rows.find(r=>r.id===o.id);for(const [key,value] of [['stockQuantity',document.stockMode==='PRODUCT'?(rows.length===1?document.productQuantity:''):optionValue(ref,'quantity')],['sellerManagerCode',optionValue(ref,'sku')||'']])if(!overrides.has('options.'+o.id+'.'+key))o[key]=value;}
        }
        if(meta.pricing==='COMMON'){
            priceProjection(document,input);
        }
        if(meta.optionStructure==='COMMON'){const usable=input.options.filter(o=>o.usable!==false);fields[P+'stockQuantity']=usable.every(o=>N.integer(o.stockQuantity))?usable.reduce((sum,o)=>sum+Number(o.stockQuantity),0):'';}
        if(input.optionMode==='NONE'&&!overrides.has('fields.'+P+'stockQuantity'))fields[P+'stockQuantity']=document.stockMode==='PRODUCT'?document.productQuantity:rows.length===1?rows[0].quantity:'';
        if(!config.editorInput&&market.overrides?.imageIds!==null&&market.overrides?.imageIds!==undefined){const ids=market.overrides.imageIds;input.images=document.media.images.filter(i=>ids.includes(i.id)).sort((a,b)=>ids.indexOf(a.id)-ids.indexOf(b.id)).map((i,n)=>({id:i.id,assetId:i.assetId??null,url:i.url,representative:!!i.representative,order:n}));meta.imageSource='CUSTOM';meta.imageOptionId=null;}
        if(!config.editorInput&&market.overrides?.description!=null){input.description=market.overrides.description;meta.descriptionSource='CUSTOM';meta.descriptionOptionId=null;}
        for(const kind of ['image','description']){
            const source=kind+'Source',option=kind+'OptionId',collection=kind==='image'?'images':'contents';
            if(meta[source]==null&&media(document,collection,null).length)meta[source]='COMMON';
            if(meta[source]==='CUSTOM'||meta[source]==null)continue;
            if(meta[source]==='OPTION'&&!rows.some(o=>o.id===meta[option])){meta.unmapped=[...new Set([...(meta.unmapped||[]),'선택한 '+(kind==='image'?'이미지':'설명')+' 옵션이 삭제되었습니다. 원본을 다시 선택하거나 현재 값을 유지해 주세요.'])];continue;}
            const selected=meta[source]==='OPTION'?meta[option]:null,items=meta[source]?media(document,collection,selected):[];
            if(kind==='image'){input.images=items.slice().sort((a,b)=>a.order-b.order).map((i,n)=>({id:i.id,assetId:i.assetId??null,url:i.url||'',representative:!!i.representative,order:n}));}
            else input.description=description(items);
        }
        config.editorInput=input;config.editorState=meta;
        return document;
    }
    function changes(before,after){
        const result=[];
        for(const key of new Set([...Object.keys(before.fields||{}),...Object.keys(after.fields||{})]))if(!same(before.fields?.[key]??null,after.fields?.[key]??null))result.push({path:prefix+'fields.'+key,optionId:null,value:copy(after.fields?.[key]??null)});
        for(const key of ['optionMode','optionNames','options','images','description'])if(!same(before[key],after[key]))result.push({path:prefix+key,optionId:null,value:copy(after[key])});
        return result;
    }
    function value(document,path){const input=document.markets.NAVER.naver.editorInput,key=path.slice(prefix.length);return key.startsWith('fields.')?input.fields[key.slice(7)]:input[key];}
    function write(document,change){const config=document.markets.NAVER.naver??={},input=config.editorInput??=N.fresh(),key=change.path.slice(prefix.length);if(change.optionId&&key.startsWith('options.')){const row=input.options.find(o=>o.id===change.optionId);if(!row)throw Error('옵션 연결을 확인해 주세요.');row[key.slice(8)]=copy(change.value);}else if(key.startsWith('fields.'))input.fields[key.slice(7)]=copy(change.value);else input[key]=copy(change.value);}
    function edited(document,before,after,reference=null){
        const config=document.markets.NAVER.naver,meta=state(config),overrides=new Set(meta.overrides);
        for(const change of changes(before,after)){
            const key=change.path.slice(prefix.length);
            if(key==='fields.'+P+'salePrice'){if((document.options||[]).some(o=>N.integer(o.originalPrice)))overrides.add(key);else meta.pricing='CUSTOM';}
            else if(key.startsWith('fields.'))overrides.add(key);
            else if(key==='images')meta.imageSource='CUSTOM';
            else if(key==='description')meta.descriptionSource='CUSTOM';
            else if(key==='optionMode'||key==='optionNames')meta.optionStructure='CUSTOM';
        }
        const old=new Map((before.options||[]).map(o=>[o.id,o]));
        for(const o of after.options||[]){const b=old.get(o.id);if(!b){meta.optionStructure='CUSTOM';meta.pricing='CUSTOM';continue;}if(!same(b.price,o.price))meta.pricing='CUSTOM';for(const key of ['values','stockQuantity','sellerManagerCode','usable'])if(!same(b[key],o[key]))overrides.add('options.'+o.id+'.'+key);}
        if((before.options||[]).some(o=>!(after.options||[]).some(row=>row.id===o.id)))meta.optionStructure='CUSTOM';
        // An explicit discount edit in common registration reapplies the common sale targets,
        // including drafts with individually edited deltas. Other edits retain custom pricing.
        if(reference&&['value','unitType'].some(key=>!same(before.fields[discountPrefix+key],after.fields[discountPrefix+key]))){
            priceProjection(reference,after);meta.pricing='COMMON';
        }
        meta.overrides=[...overrides];config.editorState=meta;config.editorInput=copy(after);
        return meta;
    }
    function reset(document,baseline,change){
        const config=document.markets.NAVER.naver,meta=state(config),key=change.path.slice(prefix.length),initial=state(baseline.markets.NAVER.naver);
        write(document,{...change,value:value(baseline,change.path)});
        if(key.startsWith('fields.'))meta.overrides=meta.overrides.filter(p=>p!==key);
        if(key==='fields.'+P+'salePrice')meta.pricing=initial.pricing;
        if(key==='images'){meta.imageSource=initial.imageSource;meta.imageOptionId=initial.imageOptionId;}
        if(key==='description'){meta.descriptionSource=initial.descriptionSource;meta.descriptionOptionId=initial.descriptionOptionId;}
        if(['options','optionNames','optionMode'].includes(key)){meta.optionStructure=initial.optionStructure;meta.overrides=meta.overrides.filter(p=>!p.startsWith('options.'));if(key==='options')meta.pricing=initial.pricing;}
        config.editorState=meta;
    }
    function selectSource(document,kind,source,optionId=null){const config=document.markets.NAVER.naver,meta=state(config);meta[kind+'Source']=source;meta[kind+'OptionId']=source==='OPTION'?optionId:null;meta.unmapped=(meta.unmapped||[]).filter(v=>!v.startsWith('선택한 '+(kind==='image'?'이미지':'설명')));config.editorState=meta;return project(document);}
    function inherit(document,group){const config=document.markets.NAVER.naver,meta=state(config);if(group==='pricing')meta.pricing='COMMON';else if(group==='all'){meta.overrides=[];meta.pricing='COMMON';meta.optionStructure='COMMON';}else meta.overrides=meta.overrides.filter(p=>p!==group);config.editorState=meta;return project(document);}
    function baseline(reference){
        const d=copy(reference),config=d.markets.NAVER.naver??={},old=state(config);
        config.editorInput=null;config.editorState={version:1,overrides:[],pricing:'COMMON',optionStructure:'COMMON',imageSource:old.imageSource==='OPTION'?'OPTION':'COMMON',imageOptionId:old.imageOptionId,descriptionSource:old.descriptionSource==='OPTION'?'OPTION':'COMMON',descriptionOptionId:old.descriptionOptionId,unmapped:[]};
        return project(d);
    }
    function commonValues(reference){const d=copy(reference),config=d.markets.NAVER.naver??={};config.editorState={...state(config),overrides:[],pricing:'COMMON',optionStructure:'COMMON'};return project(d).markets.NAVER.naver.editorInput;}
    function applyCommon(reference,before,selected){
        const mapped=commonValues(reference),after=copy(before);
        for(const change of selected){
            const key=change.path.slice(change.path.indexOf('.')+1);
            if(change.path.startsWith('common.')){
                const path=mapping[key]||CS.naverPaths[key]||({brand:D+'naverShoppingSearchInfo.brandName',taxType:D+'taxType',adultOnly:D+'minorPurchasable'})[key];
                if(!path)throw Error('이 공통 항목은 스마트스토어 탭에서 직접 입력해 주세요.');
                after.fields[path]=copy(mapped.fields[path]);
                if(key==='brand')after.fields[D+'naverShoppingSearchInfo.brandId']=copy(mapped.fields[D+'naverShoppingSearchInfo.brandId']);
            }else if(['options.price','options.originalPrice'].includes(change.path)){
                priceProjection(reference,after);
            }else if(change.path.startsWith('options.')){
                const dst=after.options.find(o=>o.id===change.optionId),src=mapped.options.find(o=>o.id===change.optionId);if(!dst||!src)throw Error('연결된 옵션을 확인해 주세요.');
                if(key==='name'){if(after.optionNames.length!==1)throw Error('다중 옵션 속성은 스마트스토어 탭에서 직접 수정해 주세요.');dst.values=copy(src.values);}
                else if(key==='quantity')dst.stockQuantity=src.stockQuantity;
                else if(key==='sku')dst.sellerManagerCode=src.sellerManagerCode;
            }else if(change.path==='media.images')after.images=media(reference,'images',change.optionId).slice().sort((a,b)=>a.order-b.order).map((i,n)=>({id:i.id,assetId:i.assetId??null,url:i.url||'',representative:!!i.representative,order:n}));
            else if(change.path==='media.contents')after.description=description(media(reference,'contents',change.optionId));
            else if(change.path==='productQuantity'){if(after.optionMode!=='NONE')throw Error('조합형 상품은 옵션별 재고를 입력해 주세요.');after.fields[P+'stockQuantity']=reference.productQuantity;}
            else if(change.path.startsWith('delivery.')){const path=deliveryMapping[key];if(!path)throw Error('이 배송 항목은 스마트스토어에서 직접 선택해 주세요.');after.fields[path]=copy(mapped.fields[path]);if(key==='bundle'&&after.fields[path]===false)after.fields[P+'deliveryInfo.deliveryBundleGroupId']=null;if(['jejuCharge','remoteCharge'].includes(key))after.fields[P+'deliveryInfo.deliveryFee.deliveryFeeByArea.deliveryAreaType']='AREA_3';if(['chargeType','charge','freeOver','method'].includes(key))for(const related of [P+'deliveryInfo.deliveryAttributeType',P+'deliveryInfo.deliveryType',P+'deliveryInfo.deliveryFee.deliveryFeeType',P+'deliveryInfo.deliveryFee.baseFee',P+'deliveryInfo.deliveryFee.freeConditionalAmount',P+'deliveryInfo.deliveryFee.deliveryFeePayType'])if(mapped.fields[related]!==undefined)after.fields[related]=copy(mapped.fields[related]);}
            else throw Error('이 항목은 스마트스토어 탭에서 직접 입력해 주세요.');
        }
        return after;
    }
    const api={pricingError,priceProjection,discountedBase,discountPrefix,baseline,prefix,mapping,project,state,reset,changes,value,write,edited,selectSource,inherit,commonValues,applyCommon};
    if(typeof module!=='undefined')module.exports=api;else window.CommonNaverEditorModel=api;
})();
