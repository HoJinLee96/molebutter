"use strict";
window.CoupangPricing = (()=>{
  function calculate(settings,purchaseCost,salePrice=null){
    const d=Number(settings.shippingCost),r=Number(settings.commissionPercent)/100,m=Number(settings.marginPercent)/100,x=Number(purchaseCost),denominator=1-1.1*(r+m);
    if(![d,r,m,x].every(Number.isFinite)||x<0||!Number.isSafeInteger(x)||d<0||r<0||m<0||denominator<=0)throw Error('사입가와 계산 설정을 확인해 주세요.');
    const calculatedPrice=Math.round((x+d)/denominator);let suggestedPrice=Math.floor(calculatedPrice/1000)*1000;if(suggestedPrice%10000===0)suggestedPrice-=1000;
    const y=salePrice==null?suggestedPrice:Number(salePrice);
    if(!Number.isSafeInteger(suggestedPrice)||suggestedPrice<=0||!Number.isSafeInteger(y)||y<=0||y%10!==0)throw Error('유효한 10원 단위 판매가격을 입력해 주세요.');
    const purchaseSupply=x/1.1,purchaseVat=x-purchaseSupply,shippingSupply=d/1.1,shippingVat=d-shippingSupply,saleSupply=y/1.1,saleVat=y-saleSupply,commission=y*r,commissionVat=commission*.1,payableVat=saleVat-purchaseVat-shippingVat-commissionVat;
    const profit=saleSupply-commission-purchaseSupply-shippingSupply;
    return {calculatedPrice,suggestedPrice,profit,marginPercent:profit/y*100,purchaseSupply,purchaseVat,shippingSupply,shippingVat,saleSupply,saleVat,commission,commissionVat,payableVat};
  }
  return {calculate};
})();
