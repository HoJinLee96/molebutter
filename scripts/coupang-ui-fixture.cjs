const C=require('../molebutter-app/src/main/resources/static/js/coupang-editor-model.js');
const fields = o => Object.entries(o).map(([name, value]) => ({
  name,
  value
}));
const rules = {
  categoryCode: '123',
  allowSingleItem: true,
  attributes: [{
    name: '색상',
    exposed: 'EXPOSED',
    required: 'MANDATORY',
    groupNumber: 'NONE'
  }],
  certifications: [{
    type: 'NOT_REQUIRED',
    name: '인증 대상 아님',
    required: 'OPTIONAL'
  }, {
    type: 'PRESENTED_IN_DETAIL_PAGE',
    name: '상세페이지 별도표기',
    required: 'OPTIONAL'
  }, {
    type: 'TEST',
    name: '합성 인증',
    required: 'OPTIONAL',
    dataType: 'CODE'
  }],
  documents: [{
    name: '합성 서류',
    required: 'OPTIONAL'
  }],
  notices: [],
  offerConditions: ['NEW']
};
function fixture(mode) {
  const native = C.fresh();
  Object.assign(native.basic, {
    sellerProductName: '합성 상품',
    brand: '브랜드',
    displayCategoryCode: '123'
  });
  Object.assign(native.settings, {
    manufacture: 'LF',
    brandId: 'KR-TEST',
    'bundleInfo.bundleType': 'SINGLE',
    saleStartedAt: '2026-10-13T10:00:00',
    saleEndedAt: '2099-12-31T23:59:59'
  });
  Object.assign(native.delivery, {
    deliveryMethod: 'SEQUENCIAL',
    deliveryCompanyCode: 'CJGLS',
    deliveryChargeType: 'FREE',
    deliveryCharge: '0',
    freeShipOverAmount: '0',
    deliveryChargeOnReturn: '3000',
    returnCharge: '3000',
    remoteAreaDeliverable: 'N',
    unionDeliveryType: 'NOT_UNION_DELIVERY',outboundShippingPlaceCode:'123',returnCenterCode:'456',returnChargeName:'반품지',companyContactNumber:'010-1234-5678',returnZipCode:'12345',returnAddress:'서울',returnAddressDetail:'상세'
  });
  const option = native.options[0];
  Object.assign(option, {
    itemName: '브라운',
    attributes: [{
      name: '색상',
      value: '브라운',
      exposed: 'EXPOSED'
    }],
    certifications: [{
      type: 'NOT_REQUIRED',
      code: '',
      attachments: []
    }]
  });
  Object.assign(option.registration, {
    originalPrice: '100000',
    salePrice: '80000',
    maximumBuyCount: '9',
    maximumBuyForPerson: '0',
    maximumBuyForPersonPeriod: '1',
    unitCount: '1',
    outboundShippingTimeDay: '1',
    parallelImported: 'NOT_PARALLEL_IMPORTED',
    adultOnly: 'EVERYONE',
    taxType: 'TAX',
    overseasPurchased: 'NOT_OVERSEAS_PURCHASED',
    externalVendorSku:'SKU',
    pccNeeded: 'false'
  });
  option.images=[{id:require('node:crypto').randomUUID(),order:0,type:'REPRESENTATION',url:'https://image.example.test/a.jpg'}];option.contents=[{id:require('node:crypto').randomUUID(),type:'HTML',detailType:'TEXT',content:'설명'}];
  const source = {
    basic: {
      ...native.basic,
      sellerProductId: '123',
      statusName: '승인완료'
    },
    limits: {
      categoryReadOnly: true,
      optionStructureReadOnly: true,
      purchaseAttributesReadOnly: true
    },
    options: [{
      ...option,
      sellerProductItemId: '111',
      vendorItemId: '222',
      current: {
        salePrice: 80000,
        amountInStock: 9,
        onSale: true
      },
      separateCurrentChanges: true,
      registration: fields(option.registration)
    }],
    delivery: fields(native.delivery),
    settings: fields(native.settings),
    documents: []
  };
  const doc = require('../molebutter-app/src/main/resources/static/js/coupang-working-copy-model.js').flatten(native);
  return {
    native,
    doc,
    source
  };
}
module.exports={fixture,rules};
