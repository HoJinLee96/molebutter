"use strict";

/* Editor-only draft and validation. These objects are never Coupang write payloads. */
(() => {
  'use strict';

  function currentRules(rules, code, now = Date.now()) {
    const checked = Date.parse(rules?.checkedAt);
    return !!rules && rules.categoryCode === code && Number.isFinite(checked) && checked <= now && now < checked + 600000
      && Array.isArray(rules.attributes) && Array.isArray(rules.notices) && Array.isArray(rules.certifications) && Array.isArray(rules.documents);
  }
  const spec = {
    sellerProductName: ['등록상품명', 'text', 100, true],
    displayProductName: ['노출상품명', 'text', 100],
    generalProductName: ['제품명', 'text', null],
    brand: ['브랜드', 'text', null],
    productGroup: ['상품군', 'text', null],
    displayCategoryCode: ['카테고리 코드', 'id', 15, true],
    deliveryMethod: ['배송 방식', 'select', null, true, ['SEQUENCIAL', 'COLD_FRESH', 'MAKE_ORDER', 'AGENT_BUY', 'VENDOR_DIRECT']],
    deliveryCompanyCode: ['택배사 코드', 'text', null, true],
    deliveryChargeType: ['배송비 유형', 'select', null, true, ['FREE', 'NOT_FREE', 'CHARGE_RECEIVED', 'CONDITIONAL_FREE']],
    deliveryCharge: ['배송비', 'number', null, true],
    freeShipOverAmount: ['무료배송 기준', 'number', null, true],
    deliveryChargeOnReturn: ['초도 반품 배송비', 'number', null, true],
    remoteAreaDeliverable: ['도서산간 배송', 'select', null, true, ['Y', 'N']],
    unionDeliveryType: ['묶음 배송', 'select', null, true, ['UNION_DELIVERY', 'NOT_UNION_DELIVERY']],
    outboundShippingPlaceCode: ['출고지 코드', 'id', 30, true],
    returnCenterCode: ['반품지 코드', 'text', null, true],
    returnChargeName: ['반품지명', 'text', null, true],
    companyContactNumber: ['반품 연락처', 'text', null, true],
    returnZipCode: ['반품 우편번호', 'text', null, true],
    returnAddress: ['반품 주소', 'text', null, true],
    returnAddressDetail: ['반품 상세 주소', 'text', null, true],
    returnCharge: ['반품 비용', 'number', null, true],
    saleStartedAt: ['판매 시작일시', 'datetime-local', null, true],
    saleEndedAt: ['판매 종료일시', 'datetime-local', null, true],
    manufacture: ['제조사', 'text', null],
    extraInfoMessage: ['추가 설정', 'text', null],
    externalVendorSku: ['판매자 상품코드', 'text', null],
    originalPrice: ['등록 정상가', 'number', null, true],
    salePrice: ['등록 판매가', 'number', null, true],
    maximumBuyCount: ['등록 판매 수량', 'number', null, true],
    maximumBuyForPerson: ['인당 구매 제한', 'number', null, true],
    maximumBuyForPersonPeriod: ['구매 제한 기간', 'number', null, true],
    outboundShippingTimeDay: ['출고 소요일', 'number', null, true],
    unitCount: ['판매 단위', 'number', null, true],
    adultOnly: ['성인 상품', 'select', null, true, ['EVERYONE', 'ADULT_ONLY']],
    taxType: ['과세 유형', 'select', null, true, ['TAX', 'FREE']],
    parallelImported: ['병행 수입', 'select', null, true, ['PARALLEL_IMPORTED', 'NOT_PARALLEL_IMPORTED']],
    overseasPurchased: ['해외 구매', 'select', null, true, ['OVERSEAS_PURCHASED', 'NOT_OVERSEAS_PURCHASED']],
    pccNeeded: ['개인통관부호', 'select', null, true, ['true', 'false']],
    bestPriceGuaranteed3P: ['최저가 보장', 'select', null, false, ['true', 'false']],
    barcode: ['바코드', 'text', null],
    emptyBarcode: ['바코드 없음', 'select', null, false, ['true', 'false']],
    emptyBarcodeReason: ['바코드 미등록 사유', 'text', 100],
    modelNo: ['모델번호', 'text', null],
    searchTags: ['검색어', 'text', null],
    offerCondition: ['상품 상태', 'select', null, false, ['NEW', 'REFURBISHED', 'USED_BEST', 'USED_GOOD', 'USED_NORMAL']],
    offerDescription: ['상태 설명', 'text', 700],
    'autoPricingInfo.minSalePrice': ['자동 가격 최소 판매가', 'number'],
    'autoPricingInfo.active': ['자동 가격 사용', 'select', null, false, ['true', 'false']]
  };
  const deliveryKeys = ['deliveryMethod', 'deliveryCompanyCode', 'deliveryChargeType', 'deliveryCharge', 'freeShipOverAmount', 'deliveryChargeOnReturn', 'remoteAreaDeliverable', 'unionDeliveryType', 'outboundShippingPlaceCode', 'returnCenterCode', 'returnChargeName', 'companyContactNumber', 'returnZipCode', 'returnAddress', 'returnAddressDetail', 'returnCharge'];
  const settingsKeys = ['saleStartedAt', 'saleEndedAt', 'manufacture', 'extraInfoMessage'];
  const optionKeys = ['externalVendorSku', 'originalPrice', 'salePrice', 'maximumBuyCount', 'maximumBuyForPerson', 'maximumBuyForPersonPeriod', 'outboundShippingTimeDay', 'unitCount', 'adultOnly', 'taxType', 'parallelImported', 'overseasPurchased', 'pccNeeded', 'bestPriceGuaranteed3P', 'barcode', 'emptyBarcode', 'emptyBarcodeReason', 'modelNo', 'searchTags', 'offerCondition', 'offerDescription', 'autoPricingInfo.minSalePrice', 'autoPricingInfo.active'];
  const uuid = typeof module !== 'undefined' ? require('./app-ui.js').uuid : AppUI.uuid;
  const productIdentity = name => ['Manufacturer Part Number', 'Global Trade Item Number'].includes(name);
  const registrationOptionName = o => {
    const attrs = (o.attributes || []).filter(a => a.exposed === 'EXPOSED' && !productIdentity(a.name));
    if (!attrs.length) return '단일 상품';
    return [...attrs].sort((a, b) => (a.name === '색상' ? 0 : a.name === '사이즈' ? 1 : 2) - (b.name === '색상' ? 0 : b.name === '사이즈' ? 1 : 2)).map(a => String(a.value || '').trim()).filter(Boolean).join(' ');
  };
  const blankOption = () => ({
    id: uuid(),
    sellerProductItemId: null,
    vendorItemId: null,
    itemName: '',
    current: null,
    currentError: null,
    separateCurrentChanges: false,
    attributes: [],
    images: [],
    contents: [],
    notices: [],
    registration: {},
    certifications: [],
    currentChanges: {},
    noticeCategory: ''
  });
  const fresh = () => ({
    basic: {
      sellerProductName: '',
      displayProductName: '',
      generalProductName: '',
      brand: '',
      productGroup: '',
      displayCategoryCode: ''
    },
    limits: {
      categoryReadOnly: false,
      optionStructureReadOnly: false,
      purchaseAttributesReadOnly: false
    },
    options: [blankOption()],
    delivery: {},
    settings: {},
    documents: []
  });
  const url = value => {
    try {
      const u = new URL(value);
      return u.protocol === 'https:' && !u.username && !u.password ? u.href : null;
    } catch {
      return null;
    }
  };
  const required = r => r === 'MANDATORY';
  const requiredDocument = (rule, draft) => required(rule.required) || rule.required === 'MANDATORY_PARALLEL_IMPORTED' && draft.options.some(o => o.registration.parallelImported === 'PARALLEL_IMPORTED') || rule.required === 'MANDATORY_OVERSEAS_PURCHASED' && draft.options.some(o => o.registration.overseasPurchased === 'OVERSEAS_PURCHASED');
  const validDate = v => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return false;
    const d = new Date(v + 'T00:00:00Z');
    return Number.isFinite(d.getTime()) && d.toISOString().slice(0, 10) === v;
  };
  // Parse wall-clock components without using browser timezone or DST normalization.
  function validDateTime(text) {
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(text);
    if (!m || Number(m[1]) < 1 || Number(m[1]) > 2099 || Number(m[4]) > 23 || Number(m[5]) > 59 || Number(m[6] || 0) > 59) return false;
    return validDate(m.slice(1, 4).join('-'));
  }
  const colorToneChoices=['그린 톤','다색','레드 톤','메탈릭 톤','밝은 톤','브라운 톤','블랙 톤','블루 톤','어스 톤','중성 톤','파스텔 톤','핑크 톤','화이트/오프 화이트 톤','회색 톤'];
  const colorToneWords=['그린|녹색|초록색?','다색|멀티\\s*컬러|멀티\\s*색상|배색','레드|빨강|빨간색|적색|버건디|와인색','메탈릭|실버|골드|은색|금색','밝은\\s*톤','(?:다크|라이트|딥)?브라운|갈색','블랙|검정색?|검은색|흑색','블루|네이비|파랑|파란색|청색|남색','어스\\s*톤','중성\\s*톤','파스텔\\s*톤','핑크|분홍색?','화이트|오프\\s*화이트|흰색|백색|아이보리','그레이|회색|차콜'];
  const colorToneAttribute=a=>a.exposed==='NONE'&&String(a.name||'').replace(/[^가-힣A-Za-z0-9]/g,'')==='패션의류잡화색상계열';
  function suggestColorTone(...titles){
    for(const source of titles){const title=String(source||'').normalize('NFKC'),found=new Set();
      colorToneWords.forEach((pattern,i)=>{if(new RegExp('(?<![가-힣A-Za-z])(?:'+pattern+')(?:\\s*톤)?(?![가-힣A-Za-z])','i').test(title))found.add(colorToneChoices[i]);});
      if(found.has('다색'))return '다색';if(found.size>1)return null;if(found.size===1)return [...found][0];
    }return null;
  }
  function addSearchFields(draft, rules) {
    for (const o of draft.options) for (const r of rules?.attributes || []) {
      if (r.exposed === 'NONE' && !productIdentity(r.name) && !o.attributes.some(a => sameAttribute(a, r)))
        o.attributes.push({name:r.name,value:'',exposed:'NONE',_automatic:true});
    }
  }
  function applyRules(draft, rules) {
    for (const o of draft.options) {
      for (const a of rules.attributes) if (!(grouped(a) && rules.attributes.some(r => grouped(r) && r.groupNumber === a.groupNumber && o.attributes.some(v => sameAttribute(v, r)))) && !o.attributes.some(v => sameAttribute(v, a))) o.attributes.push({
        name: a.name,
        value: '',
        exposed: a.exposed
      });
      if (!o.noticeCategory && rules.notices.length === 1) o.noticeCategory = rules.notices[0]?.name || '';
      addNotices(o, rules);
      for (const c of rules.certifications.filter(c => required(c.required))) if (!o.certifications.some(v => v.type === c.type)) o.certifications.push({
        type: c.type,
        code: '',
        attachments: []
      });
    }
    for (const d of rules.documents.filter(d => requiredDocument(d, draft))) if (!draft.documents.some(v => v.templateName === d.name)) draft.documents.push({
      templateName: d.name,
      path: ''
    });
  }
  const isPurchase = a => a.exposed === 'EXPOSED' && !productIdentity(a.name);
  const sameAttribute = (a, b) => a.name === b.name && a.exposed === b.exposed;
  const grouped = a => isPurchase(a) && a.groupNumber && a.groupNumber !== 'NONE';
  function purchaseColumns(rules, options = []) {
    const result = [],
      groups = new Set(),
      metadata = (rules?.attributes || []).filter(isPurchase);
    for (const r of metadata) {
      const used = options.some(o => o.attributes?.some(a => sameAttribute(a, r) && (a.value !== '' && a.value != null || o.originalAttributes?.some(v => sameAttribute(v, a)) || o.attributePermissions?.some(v => sameAttribute(v, a)))));
      if (r.required !== 'MANDATORY' && !used) continue;
      if (grouped(r)) {
        if (groups.has(r.groupNumber)) continue;
        groups.add(r.groupNumber);
        const choices = metadata.filter(a => a.groupNumber === r.groupNumber);
        result.push({
          name: choices.map(a => a.name).join(' / ') + ' (택1)',
          group: r.groupNumber,
          choices,
          required: r.required === 'MANDATORY'
        });
      } else result.push({
        ...r,
        choices: [r]
      });
    }
    // Preserve visible legacy purchase fields even after category renewal.
    for (const o of options) for (const a of o.attributes || []) if (isPurchase(a) && !metadata.some(r => r.name === a.name) && !result.some(r => r.name === a.name)) result.push({
      ...a,
      choices: [a]
    });
    const order = r => r.choices.some(a => a.name === '색상') ? 0 : r.choices.some(a => a.name === '사이즈') ? 1 : 2;
    return result.sort((a, b) => (a.required === 'MANDATORY' || a.required === true ? 0 : 1) - (b.required === 'MANDATORY' || b.required === true ? 0 : 1) || order(a) - order(b));
  }
  function purchaseAttribute(option, column) {
    return column.choices.find(r => option.attributes?.some(a => sameAttribute(a, r) && (a.value !== '' && a.value != null || option.attributePermissions?.some(p => sameAttribute(p, a))))) || column.choices.find(r => option.attributes?.some(a => sameAttribute(a, r))) || column.choices[0];
  }
  function canEditPurchase(option, name, existing) {
    if (!existing) return true;
    const attribute = {name, exposed: 'EXPOSED'},
      permission = option.attributePermissions?.find(p => sameAttribute(p, attribute));
    return permission ? permission.editable === true : !(option.originalAttributes || option.attributes).some(a => sameAttribute(a, attribute));
  }
  function categoryRemovals(draft, rules) {
    const labels = new Set();
    for (const o of draft.options) {
      for (const a of o.attributes) if (a.value && !rules.attributes.some(r => sameAttribute(r, a))) labels.add(a.name);
      for (const n of o.notices) if (n.content && !rules.notices.some(r => r.name === n.category && r.fields.some(f => f.name === n.name))) labels.add(n.category + ' · ' + n.name);
      for (const c of o.certifications) if (c.code && !rules.certifications.some(r => r.type === c.type)) labels.add('인증 ' + c.type);
    }
    for (const d of draft.documents) if ((d.path || d.vendorPath) && !rules.documents.some(r => r.name === d.templateName)) labels.add(d.templateName);
    return [...labels];
  }
  function replaceRules(draft, rules) {
    for (const o of draft.options) {
      const columns = purchaseColumns(rules, [o]),
        selectedGroups = new Map(columns.filter(c => c.group).map(c => [c.group, purchaseAttribute(o, c).name]));
      o.attributes = rules.attributes.filter(r => !grouped(r) || selectedGroups.get(r.groupNumber) === r.name).map(r => ({
        name: r.name,
        exposed: r.exposed,
        value: o.attributes.find(a => sameAttribute(a, r))?.value ?? ''
      }));
      const category = rules.notices.find(n => n.name === o.noticeCategory) || rules.notices[0];
      o.noticeCategory = category?.name || '';
      o.notices = (category?.fields || []).map(f => ({
        category: category.name,
        name: f.name,
        content: o.notices.find(n => n.category === category.name && n.name === f.name)?.content || ''
      }));
      o.certifications = o.certifications.filter(c => rules.certifications.some(r => r.type === c.type));
      o.itemName = registrationOptionName(o);
    }
    draft.documents = draft.documents.filter(d => rules.documents.some(r => r.name === d.templateName));
    applyRules(draft, rules);
  }
  function addNotices(o, rules) {
    for (const f of rules?.notices.find(n => n.name === o.noticeCategory)?.fields || []) if (!o.notices.some(v => v.category === o.noticeCategory && v.name === f.name)) o.notices.push({
      category: o.noticeCategory,
      name: f.name,
      content: ''
    });
  }
  const integer = v => /^\d+$/.test(String(v)) && Number.isSafeInteger(Number(v));
  function validate(draft, rules, mode = 'new') {
    const errors = [];
    const fail = (path, message) => errors.push({
      path,
      message
    });
    function field(path, key, v, skipRequired = false) {
      const [label, type, max, must, choices] = spec[key] || [key, 'text'];
      const text = ['originalPrice','salePrice'].includes(key)?(typeof module!=='undefined'?require('./app-ui.js'):AppUI).normalizeCoupangPrice(v):String(v ?? '').trim();
      if (!text) {
        if (must && !skipRequired) fail(path, label + '을 입력해 주세요.');
        return;
      }
      if (max && Array.from(text).length > max) fail(path, label + '은 ' + max + '자 이하로 입력해 주세요.');
      if (type === 'number' && !integer(text)) fail(path, label + '은 0 이상의 안전한 정수로 입력해 주세요.');
      if (type === 'id' && !/^\d+$/.test(text)) fail(path, label + '을 숫자로 입력해 주세요.');
      if (type === 'select' && choices && !choices.includes(text)) fail(path, label + '의 값을 확인해 주세요.');
      if (type === 'datetime-local' && !validDateTime(text)) fail(path, label + '을 유효한 2099년 이하 일시로 입력해 주세요.');
    }
    if (mode === 'new' && (!draft.basic.brand || !draft.settings.brandId)) fail('basic.brand', '브랜드를 검색해서 선택해 주세요.');
    for (const k of ['sellerProductName', 'displayProductName', 'generalProductName', 'brand', 'productGroup', 'displayCategoryCode']) field('basic.' + k, k, draft.basic[k]);
    if (mode === 'new' && (!rules || rules.categoryCode !== draft.basic.displayCategoryCode)) fail('basic.displayCategoryCode', '현재 카테고리의 입력 규격을 조회해 주세요.');
    for (const k of deliveryKeys) field('delivery.' + k, k, draft.delivery[k]);
    for (const k of settingsKeys) field('settings.' + k, k, draft.settings[k]);
    if (draft.settings.saleStartedAt && draft.settings.saleEndedAt && draft.settings.saleEndedAt < draft.settings.saleStartedAt) fail('settings.saleEndedAt', '판매 종료일시는 시작일시 이후로 입력해 주세요.');
    if (draft.delivery.deliveryChargeType === 'FREE' && Number(draft.delivery.freeShipOverAmount) !== 0) fail('delivery.freeShipOverAmount', '무료배송 기준은 0으로 입력해 주세요.');
    if (draft.delivery.deliveryChargeType === 'CONDITIONAL_FREE' && (!integer(draft.delivery.freeShipOverAmount) || Number(draft.delivery.freeShipOverAmount) < 100 || Number(draft.delivery.freeShipOverAmount) % 100 !== 0)) fail('delivery.freeShipOverAmount', '조건부 무료배송 기준은 100원 단위로 입력해 주세요.');
    if (!draft.options.length) fail('options', '옵션을 한 개 이상 입력해 주세요.');
    const names = new Set();
    draft.options.forEach((o, n) => {
      const prefix = 'options.' + n;
      if (!o.itemName?.trim() || Array.from(o.itemName).length > 150) fail(prefix + '.itemName', '옵션명은 1~150자로 입력해 주세요.');else if (names.has(o.itemName.trim())) fail(prefix + '.itemName', '옵션명은 중복되지 않게 입력해 주세요.');else names.add(o.itemName.trim());
      for (const k of optionKeys) field(prefix + '.registration.' + k, k, o.registration[k], o.separateCurrentChanges && ['originalPrice', 'salePrice', 'maximumBuyCount'].includes(k));
      if (integer(o.registration.maximumBuyCount) && Number(o.registration.maximumBuyCount) > 99999) fail(prefix + '.registration.maximumBuyCount', '등록 판매 수량은 99999 이하로 입력해 주세요.');
      if (integer(o.registration.unitCount) && Number(o.registration.unitCount) < 1) fail(prefix + '.registration.unitCount', '판매 단위는 1 이상으로 입력해 주세요.');
      const tags = String(o.registration.searchTags || '').split(',').map(t => t.trim()).filter(Boolean);
      if (tags.length > 20 || tags.some(t => Array.from(t).length > 20)) fail(prefix + '.registration.searchTags', '검색어는 쉼표로 구분하여 20개 이하, 각 20자 이하로 입력해 주세요.');
      for (const [k, v] of Object.entries(o.currentChanges || {})) if (v !== '' && v != null) {
        if (!o.current) fail(prefix + '.currentChanges.' + k, '현재 값을 먼저 확인해야 합니다.');else if (!integer(v)) fail(prefix + '.currentChanges.' + k, '현재 가격·재고 변경값은 0 이상의 안전한 정수로 입력해 주세요.');
      }
      const groups = new Map();
      for (const a of rules?.attributes || []) {
        const index = o.attributes.findIndex(v => sameAttribute(v, a)),
          value = String(o.attributes[index]?.value ?? '').trim(),
          path = prefix + '.attributes.' + index + '.value';
        if (mode === 'edit' && isPurchase(a)) {
          const old = o.originalAttributes?.find(v => sameAttribute(v, a));
          if (old && String(old.value ?? '').trim() === value || !old && !value) continue;
        }
        if (required(a.required)) {
          if (grouped(a)) {
            const group = groups.get(a.groupNumber) || [];
            group.push({
              index,
              value
            });
            groups.set(a.groupNumber, group);
          } else if (!value && mode === 'new') fail(path, a.name + '을 입력해 주세요.');
        }
        if (value && Array.from(value).length > 30) fail(path, a.name + '은 30자 이하로 입력해 주세요.');
        if (value && a.dataType === 'NUMBER' && !/^-?\d+(?:\.\d+)?(?:\s*[^\d\s]+)?$/.test(value)) fail(path, a.name + '의 숫자 형식을 확인해 주세요.');
        if (value && a.dataType === 'DATE' && !validDate(value)) fail(path, a.name + '의 날짜 형식을 확인해 주세요.');
      }
      for (const entries of groups.values()) if (mode === 'new' && entries.filter(a => a.value).length !== 1) fail(prefix + '.attributes.' + entries[0].index + '.value', '그룹 필수 속성 중 한 개만 입력해 주세요.');
      const notice = rules?.notices.find(c => c.name === o.noticeCategory);
      if (rules?.notices.length && !notice) fail(prefix + '.noticeCategory', '상품고시 유형을 선택해 주세요.');
      for (const f of notice?.fields || []) {
        const index = o.notices.findIndex(v => v.category === notice.name && v.name === f.name);
        if (required(f.required) && !o.notices[index]?.content?.trim()) fail(prefix + '.notices.' + index + '.content', f.name + '을 입력해 주세요.');
      }
      if (!o.images.some(i => i.type === 'REPRESENTATION')) fail(prefix + '.images', '대표 이미지를 지정해 주세요.');
      if (o.images.filter(i => i.type === 'REPRESENTATION').length > 1) fail(prefix + '.images', '대표 이미지는 한 개만 지정해 주세요.');
      if (o.images.filter(i => i.type === 'USED_PRODUCT').length > 4) fail(prefix + '.images', '중고 이미지는 4개 이하로 지정해 주세요.');
      if (o.images.filter(i => i.type === 'DETAIL').length > 9) fail(prefix + '.images', '추가 이미지는 9개 이하로 지정해 주세요.');
      o.images.forEach((i, k) => {
        if (!i.assetId && !url(i.url)) fail(prefix + '.images.' + k, '이미지에 유효한 HTTPS 주소가 필요합니다.');
      });
      if (!o.contents.length || !o.contents.some(c => c.content?.trim())) fail(prefix + '.contents', '상품 설명을 입력해 주세요.');
      o.contents.forEach((c, k) => {
        if (c.detailType === 'IMAGE' && c.content?.trim() && !descriptionUrl(c.content)) fail(prefix + '.contents.' + k + '.content', '설명 이미지에 유효한 HTTPS 주소가 필요합니다.');
      });
      for (const c of rules?.certifications || []) {
        const index = o.certifications.findIndex(v => v.type === c.type),
          entry = o.certifications[index];
        if (required(c.required) && (!entry || c.dataType === 'CODE' && !entry.code?.trim())) fail(prefix + '.certifications.' + Math.max(0, index) + '.code', c.name + '의 인증 정보를 입력해 주세요.');
      }
      if (o.registration.emptyBarcode === 'true' && !o.registration.emptyBarcodeReason?.trim()) fail(prefix + '.registration.emptyBarcodeReason', '바코드 미등록 사유를 입력해 주세요.');
      if (draft.delivery.deliveryMethod === 'AGENT_BUY' && o.registration.pccNeeded !== 'true') fail(prefix + '.registration.pccNeeded', '구매대행에는 개인통관부호 사용이 필요합니다.');
      const min = o.registration['autoPricingInfo.minSalePrice'];
      if (min && o.registration.salePrice && Number(min) >= Number(o.registration.salePrice)) fail(prefix + '.registration.autoPricingInfo.minSalePrice', '자동 가격 최소 판매가는 등록 판매가보다 작아야 합니다.');
    });
    for (const d of rules?.documents || []) {
      const i = draft.documents.findIndex(v => v.templateName === d.name);
      if (requiredDocument(d, draft) && !draft.documents[i]?.path?.trim() && !draft.documents[i]?.vendorPath?.trim()) fail('documents.' + Math.max(0, i) + '.path', d.name + '의 문서 주소를 입력해 주세요.');
    }
    return errors;
  }
  function descriptionUrl(v) {
    let s = String(v ?? '').trim();
    if (/^(?:\/?image\/)?vendor_inventory\//.test(s)) s = 'https://img1a.coupangcdn.com/image/' + s.replace(/^\/?image\//, '');
    if (s.startsWith('//')) s = 'https:' + s;
    try {
      const u = new URL(s);
      if (u.protocol === 'http:' && u.hostname.endsWith('.coupangcdn.com')) u.protocol = 'https:';
      return url(u.href);
    } catch {
      return null;
    }
  }
  function preview(html) {
    const text = String(html ?? '').replace(/http:\/\/[a-z0-9.-]+\.coupangcdn\.com(?=[:/])/gi, s => s.replace(/^http:/i, 'https:'));
    return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: ${typeof location !== 'undefined' ? location.origin : ''}; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"><meta name="referrer" content="no-referrer"><style>body{max-width:800px;margin:16px auto;padding:0;box-sizing:border-box;overflow-wrap:anywhere}img{display:block;max-width:100%;height:auto;margin-inline:auto}</style></head><body>${text}</body></html>`;
  }
  // Shared controls match attributes, notices and certifications by identity, not array position.
  function sharedTargets(draft, path, create = false) {
    const parts = path.split('.'),
      source = draft.options[Number(parts[1])],
      kind = parts[2];
    return draft.options.map(o => {
      if (kind === 'registration') return [o.registration, path.split('.registration.')[1]];
      if (kind === 'noticeCategory') return [o, 'noticeCategory'];
      const index = Number(parts[3]),
        entry = source[kind]?.[index];
      if (!entry) return null;
      if (kind === 'attributes' && productIdentity(entry.name)) return o === source ? [entry, parts[4]] : null;
      const matches = v => kind === 'attributes' ? v.name === entry.name && v.exposed === entry.exposed : kind === 'notices' ? v.name === entry.name && v.category === entry.category : v.type === entry.type;
      let target = o[kind].find(matches);
      if (!target && create) {
        target = structuredClone(entry);
        o[kind].push(target);
      }
      return target ? [target, parts[4]] : null;
    });
  }
  function sharedValues(draft, path) {
    return sharedTargets(draft, path).map(t => t ? t[0][t[1]] : undefined);
  }
  function setSharedValue(draft, path, value) {
    for (const t of sharedTargets(draft, path, true)) if (t) t[0][t[1]] = value;
  }
  function inheritShared(draft, option) {
    const source = draft.options[0];
    if (!source) return option;
    for (const [key, value] of Object.entries(source.registration)) if (!['originalPrice', 'salePrice', 'maximumBuyCount', 'externalVendorSku'].includes(key)) option.registration[key] = value;
    option.attributes.push(...structuredClone(source.attributes.filter(a => a.exposed === 'NONE' && !productIdentity(a.name))));
    option.notices = structuredClone(source.notices);
    option.noticeCategory = source.noticeCategory;
    option.contents.push(...structuredClone((source.contents||[]).filter(c=>c.purpose==='BRAND_LOGO')).map(c=>({...c,id:uuid()})));
    option.certifications = structuredClone(source.certifications);
    return option;
  }
  const imageLimits = {
    REPRESENTATION: 1,
    DETAIL: 9,
    USED_PRODUCT: 4
  };
  const nextImageType = images => !images.some(i => i.type === 'REPRESENTATION') ? 'REPRESENTATION' : images.filter(i => i.type === 'DETAIL').length < 9 ? 'DETAIL' : null;
  function moveImage(images, from, type, before = null) {
    if (!images[from] || !Object.hasOwn(imageLimits, type) || before === from) return false;
    const entries = images.map(i => ({
        image: i,
        type: i.type
      })),
      moving = entries[from];
    if (type === 'REPRESENTATION') for (const i of entries) if (i.type === 'REPRESENTATION') i.type = 'DETAIL';
    moving.type = type;
    if (Object.entries(imageLimits).some(([k, max]) => entries.filter(i => i.type === k).length > max)) return false;
    const target = before == null ? null : entries[before];
    entries.splice(from, 1);
    const at = type === 'REPRESENTATION' ? 0 : target ? entries.indexOf(target) : entries.length;
    entries.splice(at < 0 ? entries.length : at, 0, moving);
    entries.forEach((i, n) => {
      i.image.type = i.type;
      i.image.order = n;
    });
    images.splice(0, images.length, ...entries.map(i => i.image));
    return true;
  }
  const courierNames = {
    HYUNDAI: '롯데택배',
    KGB: '로젠택배',
    EPOST: '우체국',
    HANJIN: '한진택배',
    CJGLS: 'CJ대한통운',
    KOREX: '대한통운[합병]',
    KDEXP: '경동택배',
    DIRECT: '업체직송',
    ILYANG: '일양택배',
    CHUNIL: '천일특송',
    AJOU: '아주택배'
  };
  function longSalePeriod(now = new Date()) {
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23'
    }).formatToParts(now).map(p => [p.type, p.value]));
    return {
      saleStartedAt: `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}`,
      saleEndedAt: '2099-12-31T23:59:00'
    };
  }
  const choiceNames = {
    SINGLE: '단일 상품 구성',
    AB: '혼합 구성',
    SEQUENCIAL: '일반배송',
    COLD_FRESH: '신선·냉동',
    MAKE_ORDER: '주문제작',
    AGENT_BUY: '구매대행',
    VENDOR_DIRECT: '직접 전달·설치',
    FREE: '무료',
    NOT_FREE: '유료',
    CHARGE_RECEIVED: '착불',
    CONDITIONAL_FREE: '조건부 무료',
    Y: '가능',
    N: '불가',
    UNION_DELIVERY: '묶음 배송',
    NOT_UNION_DELIVERY: '개별 배송',
    EVERYONE: '전체 이용',
    ADULT_ONLY: '성인 전용',
    TAX: '과세',
    PARALLEL_IMPORTED: '병행 수입',
    NOT_PARALLEL_IMPORTED: '해당 없음',
    OVERSEAS_PURCHASED: '해외 구매',
    NOT_OVERSEAS_PURCHASED: '해당 없음',
    true: '예',
    false: '아니요',
    NEW: '새 상품',
    REFURBISHED: '리퍼',
    USED_BEST: '중고 최상',
    USED_GOOD: '중고 상',
    USED_NORMAL: '중고 중'
  };
  const model = {
    choiceNames,
    longSalePeriod,
    validDateTime,
    courierNames,
    purchaseColumns,
    purchaseAttribute,
    canEditPurchase,
    isPurchase,
    sameAttribute,
    addSearchFields,
    colorToneChoices,
    colorToneAttribute,
    suggestColorTone,
    productIdentity,
    registrationOptionName,
    categoryRemovals,
    replaceRules,
    spec,
    currentRules,
    deliveryKeys,
    settingsKeys,
    optionKeys,
    blankOption,
    fresh,
    url,
    applyRules,
    addNotices,
    validate,
    preview,
    descriptionUrl,
    requiredDocument,
    sharedValues,
    setSharedValue,
    inheritShared,
    imageLimits,
    nextImageType,
    moveImage
  };
  if (typeof module !== 'undefined') module.exports = model;else window.CoupangEditorModel = model;
})();
