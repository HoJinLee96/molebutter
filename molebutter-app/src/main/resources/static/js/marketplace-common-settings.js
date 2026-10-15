(() => {
    'use strict';
    const carriers={CJGLS:'CJ대한통운',HYUNDAI:'롯데택배',HANJIN:'한진택배',KGB:'로젠택배',EPOST:'우체국택배',KDEXP:'경동택배'};
    const codes={ELEVENST:{CJGLS:'00034',HYUNDAI:'00012',HANJIN:'00011',KGB:'00002',EPOST:'00007',KDEXP:'00026'},LOTTEON:{CJGLS:'0002',HYUNDAI:'0001',HANJIN:'0006',KGB:'0005',EPOST:'0004',KDEXP:'0024'}};
    const carrier=(market,key)=>codes[market]?.[key]??(codes[market]?'':key||'');
    const service=c=>[c.afterServiceManager,c.afterServiceTelephone,c.afterService].filter(v=>v!=null&&v!=='').join(' / ');
    const serviceContact=c=>[c.afterServiceManager,c.afterServiceTelephone].filter(Boolean).join(' / ')||c.afterService||'';
    const compactDate=value=>(value||'').replaceAll(/[-:T]/g,'').padEnd(14,'0');
    const naverPaths={afterServiceManager:'originProduct.detailAttribute.afterServiceInfo.afterServiceGuideContent',afterServiceTelephone:'originProduct.detailAttribute.afterServiceInfo.afterServiceTelephoneNumber',saleStartedAt:'originProduct.saleStartDate',saleEndedAt:'originProduct.saleEndDate',method:'originProduct.deliveryInfo.deliveryType',carrier:'originProduct.deliveryInfo.deliveryCompany',chargeType:'originProduct.deliveryInfo.deliveryFee.deliveryFeeType',charge:'originProduct.deliveryInfo.deliveryFee.baseFee',freeOver:'originProduct.deliveryInfo.deliveryFee.freeConditionalAmount',returnCharge:'originProduct.deliveryInfo.claimDeliveryInfo.returnDeliveryFee',bundle:'originProduct.deliveryInfo.deliveryBundleGroupUsable',jejuCharge:'originProduct.deliveryInfo.deliveryFee.deliveryFeeByArea.area2extraFee',remoteCharge:'originProduct.deliveryInfo.deliveryFee.deliveryFeeByArea.area3extraFee'};
    const lottePaths={taxType:'tdfDvsCd',saleStartedAt:'slStrtDttm',saleEndedAt:'slEndDttm',afterService:'asContents',afterServiceManager:'asContents',afterServiceTelephone:'asContents',carrier:'hdcCd',shippingDays:'sndBgtNday',bundle:'cmbnDvPsbYn',remoteArea:'dvRgsprGrpCd',method:'dvMnsCd'};
    const elevenPaths={taxType:'suplDtyfrPrdClfCd',afterService:'asDetail',afterServiceManager:'asDetail',afterServiceTelephone:'asDetail',saleStartedAt:'aplBgnDy',saleEndedAt:'aplEndDy',carrier:'dlvEtprsCd',remoteArea:'dlvCnAreaCd',bundle:'bndlDlvCnYn',method:'dlvWyCd',jejuCharge:'jejuDlvCst',remoteCharge:'islandDlvCst',returnCharge:'rtngdDlvCst',charge:'dlvCst1',chargeType:'dlvCstInstBasiCd',freeOver:'PrdFrDlvBasiAmt'};
    function partnerFields(market,d){
        const c=d.common,v=d.delivery||{},out={},put=(key,value)=>{if(value!==undefined&&value!==null&&value!=='')out[key]=value;};
        const paths=market==='LOTTEON'?lottePaths:elevenPaths;
        put(paths.taxType,['FREE','DUTYFREE'].includes(c.taxType)?'02':c.taxType==='TAX'?'01':'');put(paths.afterService,service(c));put(paths.carrier,carrier(market,v.carrier));
        put(paths.bundle,v.bundle?v.bundle==='UNION_DELIVERY'?'Y':'N':'');
        if(market==='LOTTEON'){
            put(paths.shippingDays,v.shippingDays);put(paths.saleStartedAt,c.saleStartedAt?compactDate(c.saleStartedAt):'');put(paths.saleEndedAt,c.saleEndedAt?compactDate(c.saleEndedAt):'');
            put(paths.method,({SEQUENCIAL:'DPCL',VENDOR_DIRECT:'DGNN_DV'})[v.method]); // Region codes require the seller's current code list.
        }else{
            put(paths.remoteArea,v.remoteArea?v.remoteArea==='Y'?'01':'02':'');put(paths.method,({SEQUENCIAL:'01',VENDOR_DIRECT:'03'})[v.method]);
            put(paths.jejuCharge,v.jejuCharge);put(paths.remoteCharge,v.remoteCharge);put(paths.returnCharge,v.returnCharge);
            put(paths.chargeType,({FREE:'01',NOT_FREE:'02',CONDITIONAL_FREE:'03',CHARGE_RECEIVED:'02'})[v.chargeType]);
            put('dlvCstPayTypCd',v.chargeType?v.chargeType==='CHARGE_RECEIVED'?'02':'03':'');
            put(paths.charge,v.chargeType==='FREE'?'0':v.charge);put(paths.freeOver,v.chargeType==='CONDITIONAL_FREE'?v.freeOver:'0');
            // 11st only represents days. Do not silently discard a requested time.
            if(c.saleStartedAt)put(paths.saleStartedAt,/T00:00(?::00)?$/.test(c.saleStartedAt)?c.saleStartedAt.slice(0,10).replaceAll('-','/'):c.saleStartedAt);
            if(c.saleEndedAt)put(paths.saleEndedAt,/T23:59(?::00|:59)?$/.test(c.saleEndedAt)?c.saleEndedAt.slice(0,10).replaceAll('-','/'):c.saleEndedAt);if(c.saleStartedAt||c.saleEndedAt){put('selTermUseYn','Y');put('selPrdClfCd','0:100');}
        }
        return out;
    }
    const model={carriers,carrier,service,serviceContact,compactDate,naverPaths,lottePaths,elevenPaths,partnerFields};
    if(typeof module!=='undefined')module.exports=model;else window.MarketplaceCommonSettings=model;
})();
