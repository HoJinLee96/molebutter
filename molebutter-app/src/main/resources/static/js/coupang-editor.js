"use strict";

(() => {
  'use strict';

  const {
      $,
      escape: e
    } = AppUI,
    M = window.CoupangEditorModel,
    F = window.CoupangProductForm;
  const editMatch = /^\/coupang\/products\/(\d{1,30})\/edit$/.exec(location.pathname),
    copyMatch = /^\/coupang\/working-copies\/(\d+)\/edit$/.exec(location.pathname);
  let mode = editMatch ? 'edit' : 'new';
  let entry = null, initialDocument = null, rememberedChanges = [], submissionUI = null, recoveryInput = null;
  let draft = null,
    rules = null,
    selected = 0,
    dirty = false,
    loading = false,
    ruleLoading = false,
    loadSequence = 0,
    ruleSequence = 0;
  let saveBusy = false,
    localSaveBusy = false,
    submissionBusy = false,
    imageReading = false,
    ruleError = '';

  let searchTagContext=null, searchTagColorsBase='', searchColorEdited=false, generatedSearchTags=null, searchTagsEdited=false, tagMessage='', tagGenerating=false, tagRequestSequence=0;
  let editRead = null,
    categoryRead = null;
  const startRead = (url, options) => window.CoupangRead.start(url, options);
  const codeNames = M.choiceNames;
  // Registration keys contain dots; field paths use the key as a single segment.
  const split = path => path.includes('.registration.') ? [...path.split('.registration.')[0].split('.'), 'registration', path.split('.registration.')[1]] : path.startsWith('settings.') ? ['settings', path.slice(9)] : path.split('.');
  const get = path => split(path).reduce((v, k) => v?.[k], draft);
  function set(path, v) {
    const keys = split(path),
      last = keys.pop();
    keys.reduce((o, k) => o[k], draft)[last] = v;
  }
  const id = path => 'field-' + path.replaceAll('.', '-');
  const returnKeys = ['deliveryChargeOnReturn', 'returnCenterCode', 'returnChargeName', 'companyContactNumber', 'returnZipCode', 'returnAddress', 'returnAddressDetail', 'returnCharge'];
  const saleKeys = ['externalVendorSku', 'originalPrice', 'salePrice', 'maximumBuyCount'];
  const majorKeys = ['unitCount', 'parallelImported', 'overseasPurchased', 'pccNeeded', 'adultOnly', 'maximumBuyForPerson', 'maximumBuyForPersonPeriod', 'taxType'];
  const courierNames = M.courierNames;
  function field(path, label, type = 'text', value = '', required = false, readonly = false, choices = [], max = null, compact = false) {
    const attrs = `id="${e(id(path))}" data-field="${e(path)}" aria-label="${e(label)}" ${required ? 'aria-required="true"' : ''} ${max ? `maxlength="${max}"` : ''} ${readonly ? 'disabled' : ''}`;
    const text = String(value ?? '');
    if (type === 'datetime-local' && text && !M.validDateTime(text)) type = 'text';
    const plainNumber = type === 'number' && /^options\.\d+\.(registration\.(originalPrice|salePrice|maximumBuyCount)|currentChanges\.(salePrice|amountInStock))$/.test(path);
    const labelFor = c => path.endsWith('.taxType') && c === 'FREE' ? '면세' : path === 'delivery.deliveryCompanyCode' ? courierNames[c] || c : codeNames[c] || c;
    const control = type === 'textarea' ? `<textarea ${attrs} rows="4">${e(text)}</textarea>` : type === 'select' ? `<select ${attrs}><option value="">선택</option>${[...new Set([...choices, ...(text ? [text] : [])])].map(c => `<option value="${e(c)}" ${c === text ? 'selected' : ''}>${e(labelFor(c))}</option>`).join('')}</select>` : `<input ${attrs} type="${type === 'id' || plainNumber ? 'text' : type}" ${['id', 'number'].includes(type) ? 'inputmode="numeric"' : ''} ${plainNumber ? 'data-plain-number="true"' : type === 'number' ? 'min="0" step="1"' : ''} ${type === 'datetime-local' ? 'step="1"' : ''} value="${e(text)}">`;
    const error = `<small class="editor-field-error" data-error-for="${e(path)}" hidden></small>`;
    return F.field({
      caption: e(label) + (required ? ' <span class="editor-required" aria-label="필수">*</span>' : ''),
      control: control + error,
      wide: type === 'textarea',
      compact
    });
  }
  const grid = F.grid,
    row = F.row;
  let purchaseLimitMode = null,
    certificationSelection = null;
  const certificationBackups = new WeakMap();
  let certificationEntryType = "",
    certificationEntryCode = "";
  function choices(path, label, pairs, value, disabled = false, action = null) {
    const values = path?.startsWith('options.') ? M.sharedValues(draft, path) : [value];
    const mixed = values.some(v => String(v ?? '') !== String(values[0] ?? ''));
    const current = mixed ? '' : String(value ?? '');
    return F.choices({
      label,
      pairs,
      value: current,
      disabled,
      mixed,
      attrs: v => `name="${e(path || action)}" id="${e(id(path || action) + '-' + v)}" ${path ? `data-field="${e(path)}"` : `data-settings-action="${e(action)}"`}`,
      error: path ? `<small class="editor-field-error" data-error-for="${e(path)}" hidden></small>` : ''
    });
  }
  const certificationMode = o => F.certificationMode(o.certifications);
  function settingAction(action, value) {
    if (action === 'purchase-limit') {
      purchaseLimitMode = value;
      if (value === 'OFF') for (const o of draft.options) {
        o.registration.maximumBuyForPerson = '0';
        o.registration.maximumBuyForPersonPeriod = '1';
      }
    } else if (action === 'certification') {
      if (value !== 'TARGET' && (!rules?.certifications.some(c => c.type === value) || rules.certifications.some(c => c.required === 'MANDATORY'))) return;
      certificationSelection = value;
      for (const o of draft.options) {
        if (certificationMode(o) === 'TARGET') certificationBackups.set(o, structuredClone(o.certifications));
        o.certifications = value === 'TARGET' ? structuredClone(certificationBackups.get(o) || []) : [{
          type: value,
          code: '',
          attachments: []
        }];
      }
    } else return;
    changed();
    renderSettings();
    markSharedFields();
  }
  function configured(prefix, keys, values, readonly = false) {
    return keys.map(k => {
      const [baseLabel, baseType, max, required, baseChoices] = M.spec[k] || [k, 'text'];
      const label = k === 'deliveryCompanyCode' ? '택배사' : k === 'deliveryMethod' ? '배송방법' : k === 'deliveryChargeType' ? '배송비 종류' : k === 'modelNo' ? '모델번호' : k === 'unitCount' ? '구성 단위수량' : k === 'extraInfoMessage' ? '주문제작 입력 안내' : baseLabel;
      const type = k === 'deliveryCompanyCode' ? 'select' : baseType,
        choices = k === 'deliveryCompanyCode' ? Object.keys(courierNames) : baseChoices;
      return field(prefix + '.' + k, label, type, values[k], required, typeof readonly === 'function' ? readonly(k) : readonly, choices, max);
    }).join('');
  }
  function attributeField(o, n, k, compact = false) {
    const a = o.attributes[k],
      r = rules?.attributes.find(v => M.sameAttribute(v, a)),
      locked = !['NONE', 'EXPOSED'].includes(a.exposed) || mode === 'edit' && M.isPurchase(a) && !M.canEditPurchase(o, a.name, true);
    if(M.colorToneAttribute(a)){
      const legacy=a.value&&!M.colorToneChoices.includes(a.value);
      return field(`options.${n}.attributes.${k}.value`,a.name,'select',a.value,r?.required==='MANDATORY',locked,M.colorToneChoices)+`<div><button type="button" class="btn" data-color-tone-path="options.${n}.attributes.${k}.value" ${locked?'disabled':''}>제목으로 색상계열 선택</button><small id="color-tone-status-${n}-${k}" class="product-meta" role="status">${legacy?'기존 값이 선택 목록과 다릅니다. 변경할 때 목록에서 선택해 주세요.':''}</small></div>`;
    }
    return field(`options.${n}.attributes.${k}.value`, a.name === 'Manufacturer Part Number' ? '품번 (MPN)' : a.name === 'Global Trade Item Number' ? '바코드 (GTIN)' : a.name || '속성', 'text', a.value, r?.required === 'MANDATORY' && (!r.groupNumber || r.groupNumber === 'NONE'), locked, [], 30, compact);
  }
  function syncOptionPickers() {
    $('editor-option-tabs').innerHTML = draft.options.map((o, n) => `<button type="button" role="tab" id="editor-option-tab-${n}" aria-controls="editor-option-panel" aria-selected="${n === selected}" tabindex="${n === selected ? 0 : -1}" data-option-tab="${n}">${e(o.itemName || '옵션 ' + (n + 1))}</button>`).join('');
    $('editor-option-panel').setAttribute('aria-labelledby', 'editor-option-tab-' + selected);
  }
  function selectOption(n) {
    clearImageDrag();
    selected = Math.max(0, Math.min(Number(n), draft.options.length - 1));
    syncOptionPickers();
    renderMedia();
  }
  function render() {
    $('editor-load-working-copy').hidden=mode!=='new';
    const b = draft.basic;
    $('editor-mode').textContent = mode === 'edit' ? `상품 수정 · ${b.sellerProductId} · ${b.statusName || '미확인'}` : '상품 등록';
    $('editor-title').textContent = mode === 'edit' ? b.sellerProductName || '쿠팡 상품 수정' : '쿠팡 상품 등록';
    const categoryControls = $('editor-category-load').parentElement;
    categoryControls.remove();
    $('editor-basic-fields').innerHTML = F.basic({
      mode,
      field: k => {
        const [label, type, max, required] = M.spec[k] || [k, 'text'];
        return field('basic.' + k, {
          sellerProductId: '등록상품 ID',
          productId: '노출상품 ID'
        }[k] || label, type, b[k], required, mode === 'edit' && ['displayCategoryCode', 'generalProductName', 'sellerProductId', 'productId'].includes(k), [], max);
      }
    });
    window.CoupangBrandPicker.mount($('field-basic-brand'), {
      name: b.brand,
      id: draft.settings.brandId,
      locked: mode === 'edit',
      onSelect: brand => {
        draft.basic.brand = brand.brandName;
        draft.settings.brandId = brand.brandId;
        changed();
        render();
      }
    });
    $('editor-basic-fields').firstElementChild.classList.add('coupang-basic-grid');
    const categoryLabel = $('field-basic-displayCategoryCode').closest('label'),
      categoryRow = document.createElement('div');
    categoryRow.className = categoryLabel.className;
    categoryRow.append(...categoryLabel.childNodes);
    categoryLabel.replaceWith(categoryRow);
    const categoryCaption = document.createElement('label');
    categoryCaption.className = 'coupang-row-label';
    categoryCaption.htmlFor = 'field-basic-displayCategoryCode';
    categoryCaption.innerHTML = categoryRow.firstElementChild.innerHTML;
    categoryRow.firstElementChild.replaceWith(categoryCaption);
    $('field-basic-displayCategoryCode').parentElement.classList.add('coupang-category-inline');
    $('field-basic-displayCategoryCode').parentElement.append(categoryControls);
    categoryControls.hidden = mode === 'edit' && !ruleError;
    categoryControls.querySelector('[data-category-select]')?.remove();
    if(mode==='new'){const select=document.createElement('button');select.dataset.categorySelect='true';select.type='button';select.className='btn';select.textContent='카테고리 검색';select.onclick=()=>window.CoupangCategoryPicker.open({name:draft.basic.displayCategoryCode,selection:null,onApply:({selection})=>{if(!selection)throw Error('카테고리를 선택해 주세요.');draft.basic.displayCategoryCode=selection.id;changed();render();category();}});categoryControls.append(select);}
    FormActions.bindEnter($('field-basic-displayCategoryCode'), $('editor-category-load'));
    renderOptionTable();
    $('editor-option-add').hidden = mode === 'edit';
    renderDelivery();
    syncOptionPickers();
    renderMedia();
    renderSettings();
    renderNotices();
    markSharedFields();
    $('coupang-editor-form').hidden = false;
    updateBusy();
  }
  function purchaseCell(o, n, column) {
    const r = M.purchaseAttribute(o, column);
    let k = o.attributes.findIndex(a => M.sameAttribute(a, r));
    if (k < 0) {
      k = o.attributes.length;
      o.attributes.push({
        name: r.name,
        value: '',
        exposed: 'EXPOSED',_automatic:true
      });
    }
    const group = column.group ? `<select aria-label="구매옵션 택1" data-purchase-group="${n}" data-purchase-choices="${e(column.choices.map(r => r.name).join('|'))}" ${mode === 'edit' && column.choices.some(r => (o.originalAttributes || []).some(a => M.sameAttribute(a, r))) ? 'disabled' : ''}>${column.choices.map(a => `<option value="${e(a.name)}" ${a.name === r.name ? 'selected' : ''}>${e(a.name)}</option>`).join('')}</select>` : '';
    return group + attributeField(o, n, k, true) + (r.usableUnits?.length ? '<small>단위: ' + e(r.usableUnits.join(', ')) + '</small>' : '');
  }
  function renderOptionTable() {
    const identity = (o, n, name, label) => {
      const k = o.attributes.findIndex(a => a.name === name);
      return k < 0 ? `<span class="product-meta" aria-label="${e(label)} 미확인">미확인</span>` : attributeField(o, n, k, true);
    };
    const tableInput = (o, n, k, label) => {
      const [defaultLabel, type, max, required, choices] = M.spec[k];
      return field(`options.${n}.registration.${k}`, label || defaultLabel, type, o.registration[k], required, false, choices, max, true);
    };
    const liveInput = (o, n, key, label) => o.separateCurrentChanges ? field(`options.${n}.currentChanges.${key}`, label, 'number', o.currentChanges[key] ?? o.current?.[key], false, o.current?.[key] == null, [], undefined, true) + (o.current?.[key] == null ? '<small class="form-error">미확인</small>' : '') : tableInput(o, n, key === 'amountInStock' ? 'maximumBuyCount' : key, label);
    const columns = [['옵션 아이디', o => `<span class="coupang-option-id">${AppUI.coupangOptionLink(draft.basic.productId,o.vendorItemId,mode === 'new' ? '등록 후 발급' : '미확인')}</span>${o.currentError ? `<small class="form-error">${e(o.currentError)}</small>` : ''}`, 144], ...M.purchaseColumns(rules, draft.options).map(column => [column.name, (o, n) => purchaseCell(o, n, column), 140]), ['품번 (MPN)', (o, n) => identity(o, n, 'Manufacturer Part Number', '품번 (MPN)'), 176], ['바코드 (GTIN)', (o, n) => identity(o, n, 'Global Trade Item Number', '바코드 (GTIN)'), 176], ['정상가', (o, n) => tableInput(o, n, 'originalPrice', '등록 정상가'), 126], ['판매가', (o, n) => liveInput(o, n, 'salePrice', '판매가'), 126], ['재고', (o, n) => liveInput(o, n, 'amountInStock', '재고'), 96], ['판매자상품코드', (o, n) => tableInput(o, n, 'externalVendorSku'), 176]];
    if (mode === 'new') columns.push(['작업', (o, n) => `<button class="btn small" type="button" data-remove-option="${n}" ${draft.options.length === 1 ? 'disabled' : ''}>삭제</button>`, 80]);
    $('editor-option-list').innerHTML = F.optionTable({
      headers: columns.map(([label]) => label),
      widths: columns.map(([,, width]) => width),
      rows: draft.options.map((o, n) => `<tr class="editor-option-card" role="row">${columns.map(([label, render]) => `<td role="cell" data-label="${e(label)}">${render(o, n)}</td>`).join('')}</tr>`).join('')
    });
  }
  function imageFigure(i, n, total) {
    const type = i.type === 'REPRESENTATION' ? '대표' : i.type === 'DETAIL' ? '추가' : i.type === 'USED_PRODUCT' ? '중고 상태' : i.type || '이미지';
    return F.imageCard({
      attrs: `data-image-index="${n}" draggable="true" tabindex="0" aria-label="${e(i.type === 'REPRESENTATION' ? '대표 이미지' : '이미지')} ${n + 1}" aria-description="끌어서 순서 또는 대표·추가 영역을 변경합니다. 키보드 좌우 방향키로 순서 변경, R로 대표, D로 추가로 이동합니다."`,
      preview: i.url ? `<img draggable="false" src="${e(i.assetId && i.url === '/api/coupang/assets/' + i.assetId ? i.url : M.url(i.url) || '')}" alt="옵션 이미지 ${n + 1}" referrerpolicy="no-referrer">` : '<span>이미지 없음</span>',
      caption: `${e(type)} · ${n + 1}`,
      actions: `<button type="button" class="btn small" data-image-action="edit" data-index="${n}">이미지 편집</button><button type="button" class="btn small" data-image-action="remove" data-index="${n}">삭제</button>`
    });
  }
  let outboundPlace = null,
    outboundRead = null,
    outboundStatus = '',
    shippingTimeMode = null,
    addressRead = null,
    addressSequence = 0,
    addressKind = 'outbound',
    addressPage = 1,
    addressResult = null;
  const addressText = a => [a?.zipCode, a?.address, a?.detail].filter(Boolean).join(' ');
  function addressSummary(returns) {
    const d = draft.delivery,
      code = returns ? d.returnCenterCode : d.outboundShippingPlaceCode;
    const title = returns ? d.returnChargeName || '반품/교환지' : outboundPlace && outboundPlace.code === code ? outboundPlace.name : '상품출고지';
    const text = returns ? [d.returnZipCode, d.returnAddress, d.returnAddressDetail].filter(Boolean).join(' ') : outboundPlace && outboundPlace.code === code ? addressText(outboundPlace.addresses[0]) : outboundStatus || '주소록에서 출고지를 선택해 주세요.';
    return F.address({
      returns,
      title,
      text,
      code,
      phone: d.companyContactNumber,
      buttonAttrs: `data-address-book="${returns ? 'return' : 'outbound'}"`
    });
  }
  function shippingTimes() {
    return F.shippingTimes({
      mode,
      selectionMode: shippingTimeMode,
      options: draft.options.map(o => ({
        name: o.itemName,
        days: o.registration.outboundShippingTimeDay
      })),
      choice: choices,
      field: (n, label, value) => field(`options.${n}.registration.outboundShippingTimeDay`, label, 'number', value, true)
    });
  }
  function returnTotal() {
    const d = draft.delivery,
      initial = d.deliveryChargeType === 'FREE' ? d.deliveryChargeOnReturn : d.deliveryCharge;
    const valid = [initial, d.returnCharge].every(v => String(v ?? '').trim() !== '' && /^\d+$/.test(String(v)));
    const total = valid ? Number(initial) + Number(d.returnCharge) : null;
    $('editor-return-total').textContent = total !== null && Number.isSafeInteger(total) ? `고객 사유로 반품 시 왕복 배송비는 ${d.deliveryChargeType === 'FREE' ? '초도배송비' : '기본배송비'} + 반품배송비의 합계인 ${total.toLocaleString('ko-KR')}원입니다.` : '배송비를 입력하면 왕복 반품 배송비 합계가 표시됩니다.';
  }
  function renderDelivery() {
    const d = draft.delivery;
    $('editor-delivery-fields').innerHTML = F.shipping({
      address: addressSummary(false),
      choice: choices,
      configured: keys => configured('delivery', keys, d),
      values: d,
      shippingTimes: shippingTimes()
    });
    $('editor-return-fields').innerHTML = F.returns({
      address: addressSummary(true),
      field: (key, label, value) => field('delivery.' + key, label, 'number', value, true),
      values: d,
      prefix: 'editor'
    });
    returnTotal();
  }
  async function loadOutboundSummary(seq) {
    const code = draft.delivery.outboundShippingPlaceCode;
    if (!code || outboundPlace?.code === code) return;
    outboundStatus = '출고지 주소 조회 중…';
    renderDelivery();
    try {
      const read = startRead('/api/coupang/shipping-places/outbound/' + encodeURIComponent(code));
      outboundRead = read;
      const result = await read.promise;
      if (seq !== loadSequence || draft.delivery.outboundShippingPlaceCode !== code || outboundPlace?.code === code) return;
      const place = result?.items?.find(p => p.code === code);
      if (!place?.addresses?.length) throw Error('현재 출고지 주소를 찾지 못했습니다. 주소록에서 확인해 주세요.');
      outboundPlace = place;
      outboundStatus = '';
      renderDelivery();
    } catch (err) {
      if (seq === loadSequence && draft.delivery.outboundShippingPlaceCode === code && !outboundPlace) {
        outboundStatus = err.message;
        renderDelivery();
      }
    } finally {
      outboundRead = null;
    }
  }
  async function loadAddressPage(page) {
    addressRead?.cancel();
    const seq = ++addressSequence;
    addressPage = page;
    addressResult = null;
    $('editor-address-list').innerHTML = '<p role="status">주소록 조회 중…</p>';
    $('editor-address-prev').disabled = true;
    $('editor-address-next').disabled = true;
    try {
      const read = startRead('/api/coupang/shipping-places/' + addressKind + '?page=' + page);
      addressRead = read;
      const result = await read.promise;
      if (seq !== addressSequence) return;
      if (!Array.isArray(result?.items)) throw Error('주소록 응답을 확인해 주세요.');
      addressResult = result;
      $('editor-address-list').innerHTML = result.items.flatMap((place, n) => place.addresses.map((a, k) => `<button type="button" class="coupang-address-choice" data-address-place="${n}" data-address-index="${k}" ${!place.usable ? 'disabled' : ''}><strong>${e(place.name)}</strong><span>${e(addressText(a))}</span><small>${e(place.code)} · ${e(a.addressType)}${a.phone ? ' · ' + e(a.phone) : ''}${!place.usable ? ' · 사용 불가' : ''}</small></button>`)).join('') || '<p>등록된 주소가 없습니다.</p>';
      $('editor-address-prev').disabled = page <= 1;
      $('editor-address-next').disabled = !result.hasNext;
      $('editor-address-page').textContent = `${page} 페이지 · 총 ${result.totalCount}곳`;
    } catch (err) {
      if (seq === addressSequence) $('editor-address-list').textContent = err.message;
    } finally {
      if (seq === addressSequence) addressRead = null;
    }
  }
  function closeAddressBook() {
    addressSequence++;
    addressRead?.cancel();
    addressRead = null;
    $('editor-address-dialog').close();
  }
  $('editor-address-dialog').addEventListener('cancel', ev => {
    ev.preventDefault();
    closeAddressBook();
  });
  $('editor-address-dialog').addEventListener('click', ev => {
    if (ev.target.closest('[data-address-close]')) {
      closeAddressBook();
      return;
    }
    if (ev.target.closest('#editor-address-prev')) {
      loadAddressPage(addressPage - 1);
      return;
    }
    if (ev.target.closest('#editor-address-next')) {
      loadAddressPage(addressPage + 1);
      return;
    }
    if (ev.target.closest('#editor-address-retry')) {
      loadAddressPage(addressPage);
      return;
    }
    const button = ev.target.closest('[data-address-place]');
    if (!button || button.disabled) return;
    const place = addressResult?.items[Number(button.dataset.addressPlace)],
      a = place?.addresses[Number(button.dataset.addressIndex)];
    if (!a || !place.usable) return;
    if (addressKind === 'outbound') {
      outboundPlace = {
        ...place,
        addresses: [a]
      };
      draft.delivery.outboundShippingPlaceCode = place.code;
    } else Object.assign(draft.delivery, {
      returnCenterCode: place.code,
      returnChargeName: place.name,
      companyContactNumber: a.phone,
      returnZipCode: a.zipCode,
      returnAddress: a.address,
      returnAddressDetail: a.detail
    });
    changed();
    renderDelivery();
    closeAddressBook();
  });
  let logoSettings=null,logoBusy=false,logoStatus='',logoSequence=0,logoUrlInputs=new Map();
  const logoContext=()=>draft&&entry?JSON.stringify([entry.accountKey,entry.workingCopyId,entry.revision,mode,draft.options[selected]?.id,draft.basic.displayProductName,draft.options[selected]?.contents]):'';
  function renderLogo(){
    const panel=$('editor-brand-logo');if(!panel)return;panel.hidden=mode!=='new'||!draft;if(panel.hidden)return;
    const row=window.CoupangBrandLogo.current(draft.options[selected]),logos=logoSettings?.brandLogos||[],matches=logos.map((v,i)=>v.imageUrl===row?.content?i:-1).filter(i=>i>=0),value=!row?'':matches.length===1?String(matches[0]):'custom';
    panel.innerHTML=`<h3>브랜드 로고</h3><p class="product-meta">현재 옵션에만 적용합니다. 공통 안내 → 로고 → 상품 이미지 순서이며, 제목 수정만으로 바꾸지 않습니다.</p>
      <label class="field"><span>브랜드 로고 선택</span><select id="editor-logo-select"><option value="">로고 없음</option>${logos.map((v,i)=>`<option value="${i}" ${value===String(i)?'selected':''}>${e(v.brandName)}</option>`).join('')}<option value="custom" ${value==='custom'?'selected':''}>직접 지정</option></select></label>
      <label class="field"><span>로고 이미지 URL</span><input type="url" id="editor-logo-url" maxlength="2048" placeholder="https://assets.molebutter.link/common/daks_logo.png" value="${e(logoUrlInputs.get(draft.options[selected].id)??row?.content??'')}"></label>
      <div class="toolbar"><button type="button" class="btn" id="editor-logo-apply">URL 적용</button><button type="button" class="btn" id="editor-logo-find">제목으로 로고 찾기</button><button type="button" class="btn" id="editor-logo-reload">로고 목록 다시 조회</button></div>
      <p id="editor-logo-status" role="status">${e(logoStatus)}</p>${row?`<p class="product-meta">아래 ‘브랜드 로고’ 설명 행에서 미리보기를 확인할 수 있습니다.</p>`:''}`;
    $('editor-logo-url').addEventListener('input',ev=>{logoUrlInputs.set(draft.options[selected].id,ev.target.value);cacheInput();});
    $('editor-logo-select').addEventListener('change',ev=>{if(ev.target.value==='custom'){$('editor-logo-url').focus();return;}void chooseLogo(ev.target.value===''?'':logos[Number(ev.target.value)].imageUrl);});
    FormActions.bindExplicitSubmit($('coupang-editor-form'),$('editor-logo-apply'),()=>chooseLogo($('editor-logo-url').value.trim()));
    FormActions.bindExplicitSubmit($('coupang-editor-form'),$('editor-logo-find'),findLogo);
    FormActions.bindExplicitSubmit($('coupang-editor-form'),$('editor-logo-reload'),loadLogoSettings);
  }
  function logoControls(){for(const id of ['editor-logo-select','editor-logo-url','editor-logo-apply','editor-logo-find','editor-logo-reload'])if($(id))$(id).disabled=logoBusy||loading||saveBusy||imageReading||!!entry?.recoveryMessage;}
  async function loadLogoSettings(){
    if(mode!=='new'||!entry||logoBusy)return;const seq=++logoSequence,account=entry.accountKey;logoBusy=true;logoStatus='브랜드 로고 설정 조회 중…';renderLogo();logoControls();
    try{const result=await apiGet('/api/settings/coupang-registration');if(seq!==logoSequence||entry?.accountKey!==account)return;if(result.accountKey!==account)throw Error('쿠팡 계정이 변경되었습니다. 다시 진입해 주세요.');logoSettings=result;logoStatus='저장된 로고 '+(result.brandLogos||[]).length+'개 · 자동 변경하지 않습니다.';}
    catch(err){if(seq===logoSequence)logoStatus=err.message||'로고 목록을 읽지 못했습니다. 입력은 유지했습니다.';}
    finally{if(seq===logoSequence){logoBusy=false;renderLogo();updateBusy();}}
  }
  async function checkedLogo(url){if(!url)return '';const image=await apiGet('/api/coupang/r2-images/resolve?'+new URLSearchParams({url}));if(typeof image?.url!=='string'||!image.url||!image?.key?.startsWith('common/'))throw Error('브랜드 로고는 설정된 R2 공개 버킷의 common/ 이미지를 지정해 주세요.');return image.url;}
  function commitLogo(url){
    const option=draft.options[selected],old=window.CoupangBrandLogo.current(option);
    if(old&&old.content!==url&&!confirm(url?'현재 브랜드 로고를 교체할까요?':'현재 브랜드 로고를 제거할까요?')){logoStatus='기존 로고와 입력을 유지했습니다.';return;}
    if(window.CoupangBrandLogo.apply(option,url,AppUI.uuid)){logoUrlInputs.delete(option.id);changed();logoStatus=url?'현재 옵션의 브랜드 로고를 적용했습니다.':'현재 옵션의 브랜드 로고를 제거했습니다.';}else logoStatus='현재 로고와 같습니다.';
  }
  async function chooseLogo(url){
    if(mode!=='new'||logoBusy||loading||saveBusy||imageReading||entry?.recoveryMessage)return;
    const context=logoContext(),seq=++logoSequence;logoBusy=true;logoStatus='R2 로고 확인 중…';$('editor-logo-status').textContent=logoStatus;logoControls();
    try{const checked=await checkedLogo(url);if(seq!==logoSequence)return;if(context!==logoContext()||loading||saveBusy||imageReading||entry?.recoveryMessage){logoStatus='옵션이나 입력이 바뀌어 적용하지 않았습니다. 다시 선택해 주세요.';return;}commitLogo(checked);renderMedia();}
    catch(err){if(seq===logoSequence){logoStatus=err.message||'로고를 확인하지 못했습니다. 입력은 유지했습니다.';renderLogo();}}
    finally{if(seq===logoSequence){logoBusy=false;if($('editor-logo-status'))$('editor-logo-status').textContent=logoStatus;updateBusy();}}
  }
  async function findLogo(){
    if(mode!=='new'||logoBusy||loading||saveBusy||imageReading||entry?.recoveryMessage)return;
    const context=logoContext(),seq=++logoSequence;logoBusy=true;logoStatus='제목으로 로고 찾는 중…';$('editor-logo-status').textContent=logoStatus;logoControls();
    try{const result=await apiPost('/api/coupang/working-copies/brand-logo',{title:draft.basic.displayProductName||''});if(seq!==logoSequence)return;if(result.accountKey!==entry.accountKey)throw Error('쿠팡 계정이 변경되었습니다. 다시 진입해 주세요.');
      if(!['MATCHED','AMBIGUOUS','NOT_FOUND'].includes(result.status)||result.status==='MATCHED'&&!result.logo?.imageUrl||result.status==='NOT_FOUND'&&result.logo)throw Error('로고 조회 응답을 확인하지 못했습니다. 입력은 유지했습니다.');
      if(result.status==='AMBIGUOUS'){logoStatus='여러 브랜드가 일치합니다. 기존 로고는 유지했습니다. 직접 선택해 주세요.';return;}
      const url=await checkedLogo(result.logo?.imageUrl||'');if(seq!==logoSequence)return;if(context!==logoContext()||loading||saveBusy||imageReading||entry?.recoveryMessage){logoStatus='제목·옵션·설명이 바뀌어 적용하지 않았습니다. 다시 찾아 주세요.';return;}
      if(result.status==='NOT_FOUND'&&!window.CoupangBrandLogo.current(draft.options[selected]))logoStatus='제목과 일치하는 브랜드 로고가 없습니다.';else commitLogo(url);renderMedia();
    }catch(err){if(seq===logoSequence)logoStatus=err.message||'로고를 찾지 못했습니다. 입력은 유지했습니다.';}
    finally{if(seq===logoSequence){logoBusy=false;if($('editor-logo-status'))$('editor-logo-status').textContent=logoStatus;updateBusy();}}
  }
  function renderMedia() {
    const o = draft.options[selected],
      p = 'options.' + selected;
    const images = type => o.images.map((i, n) => i.type === type ? imageFigure(i, n, o.images.length) : '').join('');
    $('editor-media-fields').innerHTML = F.imageControls({
      prefix: 'editor'
    }) + F.imageGroups({
      images,
      count: type => o.images.filter(i => i.type === type).length
    }) + `<div data-error-for="${p}.images" class="editor-field-error" hidden></div>`;
    $('editor-description-fields').innerHTML = F.description({
      cards: o.contents.map((c, n) => F.contentCard({
        label: c.purpose==='BRAND_LOGO'?'브랜드 로고':c.type === 'HTML' ? 'HTML 설명' : c.detailType === 'IMAGE' ? '설명 이미지' : c.type || c.detailType || '콘텐츠',
        index: n,
        field: field(`${p}.contents.${n}.content`, c.detailType === 'IMAGE' ? '설명 이미지 주소' : '설명 원문', c.detailType === 'IMAGE' ? 'text' : 'textarea', c.content),
        preview: `<div class="editor-preview" data-preview="${n}"></div>`,
        remove: `<button class="btn small" type="button" data-remove-content="${n}">설명 삭제</button>` + (c.detailType === 'IMAGE' ? `<button class="btn small" type="button" data-crop-content="${n}">설명 이미지 편집</button>` : window.ImageCropEditor.htmlButtons(c.content,index=>`data-crop-html="${n}" data-crop-image="${index}"`))
      })).join(''),
      error: `<div data-error-for="${p}.contents" class="editor-field-error" hidden></div>`
    });
    FormActions.bindEnter($('editor-image-url'), $('editor-media-fields').querySelector('[data-add-image]'));
    renderLogo();previews();
    updateBusy();
  }
  const noticeReference = '상품 상세페이지 참조',
    noticeBackups = new WeakMap();
  function noticeReferenceChecked() {
    const notices = draft.options.flatMap(o => o.notices.filter(v => v.category === o.noticeCategory));
    return notices.length > 0 && notices.every(v => String(v.content ?? '').trim() === noticeReference);
  }
  function updateNoticeReference() {
    $('editor-notice-reference').checked = noticeReferenceChecked();
  }
  function renderNotices() {
    const o = draft.options[0], p = 'options.0';
    const available = rules?.categoryCode === draft.basic.displayCategoryCode && !ruleError;
    const mixed = draft.options.some(option => option.noticeCategory !== o.noticeCategory);
    const missing = available && draft.options.some(option => option.noticeCategory && !rules.notices.some(n => n.name === option.noticeCategory));
    const status = ruleLoading ? '카테고리 규격 조회 중… 현재 입력을 유지합니다.' : ruleError || (!available ? '카테고리 규격을 조회해 주세요.' : missing ? '현재 고시유형이 최신 규격에 없습니다. 기존 입력은 유지되며 유형 변경 시 유효한 항목을 선택해 주세요.' : '');
    const fields = (option, index) => option.notices.map((v, n) => {
      if (v.category !== option.noticeCategory) return '';
      const r = available && rules.notices.find(c => c.name === v.category)?.fields.find(f => f.name === v.name);
      return field(`options.${index}.notices.${n}.content`, v.name || '고시 항목', 'textarea', v.content, r?.required === 'MANDATORY');
    }).join('');
    $('editor-notice-fields').innerHTML = F.notices({
      referenceAttrs: 'id="editor-notice-reference"',
      checked: noticeReferenceChecked(),
      selector: field(p + '.noticeCategory', '고시 유형', 'select', o.noticeCategory, true, ruleLoading || !available || !rules.notices.length, available ? rules.notices.map(n => n.name) : []) +
        `<div role="status" aria-live="polite">${e(status)}</div><button type="button" class="btn small" id="editor-notice-retry" ${ruleLoading || loading ? 'disabled' : ''}>고시 규격 다시 조회</button>` +
        (mixed ? '<p class="product-meta">옵션별 고시유형이 다릅니다. 유형을 선택하면 모든 옵션에 적용됩니다.</p>' : ''),
      fields: mixed ? draft.options.map((option, index) => `<section class="coupang-notice-option"><h3>${e(option.itemName || '옵션 ' + (index + 1))} · ${e(option.noticeCategory || '유형 미선택')}</h3>${fields(option, index)}</section>`).join('') : fields(o, 0)
    });
    const selector = $('field-options-0-noticeCategory');
    selector.options[0].disabled = true;
    for (const option of selector.options) if (option.value && !rules?.notices.some(n => n.name === option.value)) option.disabled = true;
    FormActions.bindExplicitSubmit($('coupang-editor-form'), $('editor-notice-retry'), category);
  }
  function previews() {
    const o = draft.options[selected];
    document.querySelectorAll('[data-preview]').forEach(el => {
      const c = o.contents[Number(el.dataset.preview)];
      if (!c) return;
      el.innerHTML = c.type === 'HTML' ? `<iframe class="coupang-html" sandbox="" referrerpolicy="no-referrer" title="상품 설명 미리보기" srcdoc="${e(M.preview(c.content))}"></iframe>` : c.detailType === 'IMAGE' && M.descriptionUrl(c.content) ? `<img src="${e(M.descriptionUrl(c.content))}" alt="상품 설명 이미지" referrerpolicy="no-referrer">` : `<p class="coupang-content-text">${e(c.content || '')}</p>`;
    });
  }
  const colorName=name=>['색상','컬러','제품색상','색상계열'].includes(String(name||'').replace(/[^가-힣A-Za-z]/g,''));
  function colorRows(){return draft.options.flatMap(o=>{
    const attributes=(o.attributes||[]).filter(a=>colorName(a.name));
    return attributes.length?attributes.map(a=>({option:o.id,name:a.name,value:a.value||''})):(o.notices||[]).filter(n=>n.category===o.noticeCategory&&colorName(n.name)).map(n=>({option:o.id,name:n.name,value:n.content||''}));
  }).filter(r=>r.value.trim());}
  function tagInput(){
    const rows=colorRows(),same=JSON.stringify(rows)===searchTagColorsBase;
    const colors=rows.length||!same||searchColorEdited||draft.basic.displayCategoryCode!==initialDocument?.basic?.displayCategoryCode?rows.flatMap(r=>r.value.split(/[,/;|]/).map(v=>v.trim()).filter(Boolean)):[...(searchTagContext?.colors||[])];
    return {title:draft.basic.displayProductName||'',brand:draft.basic.brand||'',context:{gender:searchTagContext?.gender||'',material:searchTagContext?.material||'',category:draft.basic.displayCategoryCode===initialDocument?.basic?.displayCategoryCode?searchTagContext?.category||'':'',colors:[...new Set(colors)]}};
  }
  const tagValues=()=>draft.options.map(o=>o.registration.searchTags||'');
  const tagFingerprint=()=>JSON.stringify({input:tagInput(),tags:tagValues(),account:entry?.accountKey,session:entry?.sessionId,revision:entry?.revision,workingCopy:entry?.workingCopyId});
  async function regenerateTags(){
    if(!draft||loading||saveBusy||imageReading||ruleLoading||tagGenerating||entry?.recoveryMessage)return;
    const current=tagValues();
    if((searchTagsEdited||(generatedSearchTags==null?current.some(Boolean):current.some((v,i)=>v!==generatedSearchTags[i])))&&!confirm('직접 작성한 검색어 또는 옵션별 검색어를 새 검색어로 교체할까요? 다른 입력은 유지됩니다.'))return;
    const seq=++tagRequestSequence,load=loadSequence,before=tagFingerprint();
    tagGenerating=true;tagMessage='검색어 생성 중…';renderTags();updateBusy();
    try{
      const result=await apiRequest('/api/coupang/working-copies/search-tags',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(tagInput())});
      if(seq!==tagRequestSequence||load!==loadSequence)return;
      if(before!==tagFingerprint()){tagMessage='생성 중 입력이 변경되어 결과를 적용하지 않았습니다. 다시 만들어 주세요.';return;}
      if(!Array.isArray(result.tags)||result.tags.length>20||result.tags.some(t=>typeof t!=='string'||Array.from(t).length>20)||!Array.isArray(result.excluded))throw Error('검색어 생성 응답을 확인할 수 없습니다.');
      M.setSharedValue(draft,'options.0.registration.searchTags',result.tags.join(', '));generatedSearchTags=tagValues();searchTagsEdited=false;
      tagMessage='검색어 '+result.tags.length+'개를 만들었습니다.'+(result.excluded.length?' 제외: '+result.excluded.map(v=>v.value+' ('+(v.reason==='OVER_COUNT'?'20개 한도 초과':'20자 초과')+')').join(', '):'');
      $('field-options-0-registration-searchTags').value=draft.options[0].registration.searchTags;delete $('field-options-0-registration-searchTags').dataset.mixed;
      changed();
    }catch(error){if(seq===tagRequestSequence&&load===loadSequence)tagMessage=error.message+' 기존 검색어는 유지했습니다.';}
    finally{if(seq===tagRequestSequence&&load===loadSequence){tagGenerating=false;renderTags();markSharedFields();updateBusy();}}
  }
  function renderTags() {
    const state = F.tagState(draft.options[0].registration.searchTags);
    if($('editor-tag-generation-status'))$('editor-tag-generation-status').textContent=tagMessage;
    $('editor-tag-list').innerHTML = state.html;
    $('editor-tag-count').textContent = state.count;
    $('editor-tag-limit').hidden = !state.invalid;
    $('editor-tag-limit').textContent = state.invalid ? '검색어는 최대 20개, 각 20자 이하로 입력해 주세요.' : '';
    $('field-options-0-registration-searchTags').setAttribute('aria-invalid', String(state.invalid));
  }
  function renderSettings() {
    const extraOpen = document.getElementById('editor-extra-settings')?.open,
      o = draft.options[0],
      p = 'options.0';
    const extras = Object.keys(o.registration).filter(k => !M.optionKeys.includes(k)),
      additional = M.optionKeys.filter(k => !saleKeys.includes(k) && !majorKeys.includes(k) && k !== 'searchTags' && k !== 'outboundShippingTimeDay');
    const certMode = certificationSelection ?? certificationMode(o),
      certMixed = certificationSelection === null && draft.options.some(v => certificationMode(v) !== certMode),
      types = rules?.certifications || [];
    const limit = purchaseLimitMode ?? (draft.options.some(v => String(v.registration.maximumBuyForPerson ?? '') !== String(o.registration.maximumBuyForPerson ?? '')) ? '' : o.registration.maximumBuyForPerson == null ? '' : Number(o.registration.maximumBuyForPerson) === 0 ? 'OFF' : 'ON');
    $('editor-settings-fields').innerHTML = F.settings({
      mode,
      types,
      certMode,
      certMixed,
      limit,
      bundle: draft.settings['bundleInfo.bundleType'],
      extraId: 'editor-extra-settings',
      choice: choices,
      configured: (scope, keys) => configured(scope === 'settings' ? 'settings' : p + '.registration', keys, scope === 'settings' ? draft.settings : o.registration),
      registrationChoice: (key, label, pairs) => choices(p + '.registration.' + key, label, pairs, o.registration[key]),
      certification: F.certification({
        types,
        entries: o.certifications,
        prefix: 'editor',
        entryType: certificationEntryType,
        entryCode: certificationEntryCode,
        entryField: (c, n, r) => field(`${p}.certifications.${n}.code`, '인증번호', 'text', c.code, r?.required === 'MANDATORY' && r.dataType === 'CODE')
      }),
      extra: configured('settings', ['extraInfoMessage'], draft.settings) + configured(p + '.registration', ['unitCount', 'overseasPurchased', 'pccNeeded', ...additional], o.registration) + extras.map(k => field(p + '.registration.' + k, k, 'text', o.registration[k], false, true)).join('') + Object.keys(draft.settings).filter(k => !M.settingsKeys.includes(k) && k !== 'bundleInfo.bundleType' && k !== 'brandId' && k !== 'vendorUserId').map(k => field('settings.' + k, k, 'text', draft.settings[k], false, true)).join('')
    });
    FormActions.bindExplicitSubmit($('coupang-editor-form'), document.querySelector('[data-coupang-long-period]'), () => {
      if (saveBusy || imageReading || loading) return;
      Object.assign(draft.settings, M.longSalePeriod());
      changed(); renderSettings(); markSharedFields(); updateBusy();
    });
    $('editor-document-fields').innerHTML = F.documents({
      items: draft.documents,
      rules: rules?.documents || [],
      prefix: 'editor',
      url: M.url,
      required: d => rules?.documents.some(r => r.name === d.templateName && M.requiredDocument(r, draft)),
      field: (d, n, required) => field(`documents.${n}.path`, d.templateName || '서류 주소', 'text', d.path || d.vendorPath, required)
    });
    if (extraOpen) $('editor-extra-settings').open = true;
    FormActions.bindEnter($('editor-cert-code'), $('editor-settings-fields').querySelector('[data-add-cert]'));
    $('editor-search-tags-fields').innerHTML = field(p + '.registration.searchTags', '검색어', 'text', o.registration.searchTags) + `<button type="button" class="btn" id="editor-regenerate-tags">검색어 다시 만들기</button><p id="editor-tag-generation-status" class="product-meta" role="status"></p><small id="editor-tag-count" class="product-meta"></small><p id="editor-tag-limit" class="form-error" role="alert" hidden></p><div id="editor-tag-list" class="coupang-tag-list" aria-label="입력한 검색어"></div>`;
    $('editor-regenerate-tags').addEventListener('click',regenerateTags);
    $('editor-regenerate-tags').disabled=tagGenerating||loading||saveBusy||imageReading||ruleLoading;
    renderTags();
    renderSearchFilters();
  }
  function renderSearchFilters(){
    M.addSearchFields(draft,rules);
    const o=draft.options[0],p='options.0';
    const filters=o.attributes.map((a,k)=>a.exposed==='NONE'&&!M.productIdentity(a.name)?attributeField(o,0,k):'').join('')+o.attributes.map((a,n)=>!['NONE','EXPOSED'].includes(a.exposed)&&!M.productIdentity(a.name)?field(`${p}.attributes.${n}.value`,a.name||'미분류 속성','text',a.value,false,true):'').join('');
    const filterStatus=ruleLoading?'검색필터 규격을 조회 중입니다.':ruleError?'검색필터 규격을 확인하지 못했습니다. 다시 조회해 주세요.':!rules?'카테고리 규격을 조회하면 검색필터를 입력할 수 있습니다.':filters?'쿠팡 카테고리의 검색용 속성입니다. 확인 가능한 값만 입력해 주세요.':'이 카테고리는 API에서 제공하는 검색필터가 없습니다.';
    $('editor-search-filter-fields').innerHTML='<p id="editor-search-filter-status" class="product-meta" role="status">'+e(filterStatus)+'</p>'+grid(filters)+(ruleError?'<button type="button" class="btn" id="editor-search-filter-retry">검색필터 규격 다시 조회</button>':'');
    $('editor-search-filter-retry')?.addEventListener('click',()=>{if(!loading&&!saveBusy&&!imageReading)category();});
  }
  const sharedSections = '#editor-settings [data-field],#editor-search-tags [data-field],#editor-search-filters [data-field],#editor-notices [data-field]';
  function markSharedFields() {
    document.querySelectorAll(sharedSections).forEach(input => {
      const path = input.dataset.field;
      if (input.type === 'radio' || !path.startsWith('options.') || input.closest('.coupang-notice-option')) return;
      const values = M.sharedValues(draft, path),
        mixed = values.some(v => String(v ?? '') !== String(values[0] ?? ''));
      if (mixed) {
        input.value = '';
        input.dataset.mixed = 'true';
        input.setAttribute('placeholder', '옵션별 값 다름');
        if (path.endsWith('.registration.searchTags')) {
          $('editor-tag-list').innerHTML = '';
          $('editor-tag-count').textContent = '옵션별 값 다름';
          $('editor-tag-limit').hidden = true;
        }
        if (input.tagName === 'SELECT') input.options[0].textContent = '옵션별 값 다름';
      }
    });
  }
  function updateField(input, path) {
    if(path.endsWith('.registration.searchTags'))searchTagsEdited=true;
    if (/\.(?:registration\.(?:originalPrice|salePrice)|currentChanges\.salePrice)$/.test(path)) input.value=AppUI.normalizeCoupangPrice(input.value);
    if (path.endsWith('.registration.outboundShippingTimeDay') && (shippingTimeMode || (draft.options.every(o => String(o.registration.outboundShippingTimeDay ?? '') === String(draft.options[0].registration.outboundShippingTimeDay ?? '')) ? 'COMMON' : 'OPTIONS')) === 'COMMON') {
      for (const o of draft.options) o.registration.outboundShippingTimeDay = input.value;
      return;
    }
    if (!input.closest('.coupang-notice-option') && input.closest('#editor-settings,#editor-search-tags,#editor-search-filters,#editor-notices') && path.startsWith('options.')) M.setSharedValue(draft, path, input.value);else set(path, input.value);
    delete input.dataset.mixed;
  }
  let prefillOwner = null;
  const prefillToken = new URLSearchParams(location.search).get('prefill');
  const cacheKey=e=>'molebutter.coupang.working-copy.input.'+e.accountKey+'.'+(e.workingCopyId||e.productId||(prefillToken?'prefill.'+prefillToken:'new'));
  function cacheInput(){if(!entry||!draft)return;try{sessionStorage.setItem(cacheKey(entry),JSON.stringify({format:4,ownerId:prefillOwner,revision:entry.revision,appliedRevision:entry.appliedRevision,logoUrlInputs:[...logoUrlInputs],searchTagState:{context:searchTagContext,colorsBase:searchTagColorsBase,generated:generatedSearchTags,colorEdited:searchColorEdited,tagsEdited:searchTagsEdited},document:window.CoupangWorkingCopyModel.flatten(draft),baseDocument:initialDocument,changes:window.CoupangWorkingCopyModel.changes(initialDocument,window.CoupangWorkingCopyModel.flatten(draft),rememberedChanges)}));}catch{}}
  function recovery(message,input){recoveryInput=input;entry.recoveryMessage=message;$('editor-load-error').textContent=message;$('editor-load-error').hidden=false;$('editor-recovery').hidden=false;$('editor-reprepare').disabled=!entry.recoveryBase;}
  function changed(){if(draft&&JSON.stringify(colorRows())!==searchTagColorsBase)searchColorEdited=true;cacheInput();submissionUI?.changed();dirty=true;$('editor-validation').hidden=true;}
  function updateBusy() {
    saveBusy = localSaveBusy || submissionBusy;
    for (const control of $('coupang-editor-form').querySelectorAll('input,select,textarea,button')) {
      if (saveBusy || imageReading) {
        if (!control.dataset.editorDisabled) control.dataset.editorDisabled = control.disabled ? 'yes' : 'no';
        control.disabled = true;
      } else if (control.dataset.editorDisabled) {
        control.disabled = control.dataset.editorDisabled === 'yes';
        delete control.dataset.editorDisabled;
      }
    }
    if($('editor-regenerate-tags'))$('editor-regenerate-tags').disabled=tagGenerating||loading||saveBusy||imageReading||ruleLoading||!!entry?.recoveryMessage;
    $('editor-category-load').disabled = ruleLoading || loading || saveBusy || imageReading;
    $('editor-validate').disabled = ruleLoading || loading || saveBusy || imageReading;
    $('editor-save-working-copy').disabled = ruleLoading || loading || saveBusy || imageReading;
    logoControls();
    submissionUI?.update();
  }
  let appliedCategory = '';
  $('coupang-editor-form').addEventListener('change', ev => {
    const t = ev.target;
    if (!t.hasAttribute('data-purchase-group') || t.disabled || saveBusy) return;
    const o = draft.options[Number(t.dataset.purchaseGroup)],
      choices = t.dataset.purchaseChoices.split('|');
    o.attributes = o.attributes.filter(a => !M.isPurchase(a) || !choices.includes(a.name) || a.name === t.value);
    if (!o.attributes.some(a => M.isPurchase(a) && a.name === t.value)) o.attributes.push({
      name: t.value,
      value: '',
      exposed: 'EXPOSED'
    });
    changed();
    syncRegistrationNames();
    renderOptionTable();
  });
  function syncRegistrationNames() {
    if (mode !== 'new') return;
    for (const o of draft.options) o.itemName = M.registrationOptionName(o);
    for (const control of document.querySelectorAll('[data-field$=".itemName"]')) control.value = get(control.dataset.field);
    syncOptionPickers();
  }
  async function category() {
    if (ruleLoading || !draft) return;
    const code = String(draft.basic.displayCategoryCode || '').trim();
    if (!/^\d{1,15}$/.test(code)) {
      $('editor-category-status').textContent = '유효한 카테고리 코드를 입력해 주세요.';
      return;
    }
    const seq = ++ruleSequence;
    ruleLoading = true;
    renderSearchFilters();renderNotices(); markSharedFields();
    updateBusy();
    if (mode !== 'edit') $('editor-category-status').textContent = '카테고리 규격 조회 중…';
    try {
      categoryRead = startRead('/api/coupang/categories/' + encodeURIComponent(code) + '/rules');
      const data = await categoryRead.promise;
      if (seq !== ruleSequence || draft.basic.displayCategoryCode !== code) return;
      if (data.categoryCode !== code || !Array.isArray(data.attributes) || !Array.isArray(data.notices) || !Array.isArray(data.certifications) || !Array.isArray(data.documents)) throw Error('카테고리 규격을 확인할 수 없습니다.');
      if (mode === 'new' && code !== appliedCategory) {
        const removed = M.categoryRemovals(draft, data);
        if (removed.length && !window.confirm('새 카테고리에서 제외되는 입력: ' + removed.join(', ') + '\n이 항목을 제외하고 카테고리를 변경할까요?')) {
          draft.basic.displayCategoryCode = appliedCategory;
          render();
          $('editor-category-status').textContent = '카테고리 변경을 취소했습니다.';
          return;
        }
        M.replaceRules(draft, data);
        appliedCategory = code;
        changed();
      }
      rules = data;
      ruleError = '';
      render();
      if (mode !== 'edit') $('editor-category-status').textContent = '카테고리 규격 조회 완료';
    } catch (err) {
      if (seq === ruleSequence) {
        ruleError = err.message;
        if (mode === 'edit') {
          render();
          $('editor-category-status').textContent = err.message;
        } else $('editor-category-status').textContent = err.message;
      }
    } finally {
      if (seq === ruleSequence) {
        categoryRead = null;
        ruleLoading = false;
        renderSearchFilters();renderNotices(); markSharedFields();
        updateBusy();
      }
    }
  }
  async function initialize() {
    if (loading) return;
    const seq = ++loadSequence;++tagRequestSequence;tagGenerating=false;tagMessage='';++logoSequence;logoBusy=false;logoSettings=null;logoStatus='';logoUrlInputs=new Map();
    let restoredInput = false;
    loading = true;
    $('editor-loading').hidden = false;
    $('editor-load-error').hidden = true;
    $('editor-retry').hidden = true;
    $('editor-recovery').hidden=true;recoveryInput=null;
    try {
      const endpoint = prefillToken && !copyMatch && !editMatch ? '/api/coupang/working-copies/prefill/'+encodeURIComponent(prefillToken) : copyMatch ? '/api/coupang/working-copies/' + copyMatch[1] : '/api/coupang/working-copies/entry' + (editMatch ? '?productId=' + editMatch[1] : '');
      editRead = startRead(endpoint);const loaded = await editRead.promise;
      if(seq!==loadSequence)return;
      entry = prefillToken && !copyMatch && !editMatch ? loaded.entry : loaded;prefillOwner=loaded.ownerId||null;
      const provenance=$('editor-prefill');
      if(provenance){provenance.hidden=!(prefillToken && loaded.notes);if(!provenance.hidden)provenance.innerHTML='<h2>자동 입력 확인</h2><p>조회 근거로 준비한 입력입니다. 저장·전송 전 확인해 주세요. <a href="/settings?tab=coupang-registration" target="_blank" rel="noopener">쿠팡 등록 기본설정</a></p><details><summary>입력 출처·이전 확인값·누락·확인 항목 '+loaded.notes.length+'개</summary><ul>'+loaded.notes.map(n=>'<li><strong>'+e(M.spec[n.field]?.[0]||n.field)+'</strong> · '+e(n.source)+' · '+e(n.message)+(n.checkedAt?' · 확인 '+e(n.checkedAt):'')+'</li>').join('')+'</ul></details>';}

      mode = entry.mode === 'UPDATE' ? 'edit' : 'new';
      rememberedChanges = entry.changes || [];initialDocument = structuredClone(entry.document);
      draft = window.CoupangWorkingCopyModel.inflate(entry);
      searchTagsEdited=false;searchColorEdited=false;searchTagContext=loaded.searchTagContext||null;searchTagColorsBase=JSON.stringify(colorRows());generatedSearchTags=searchTagContext?tagValues():null;
      if (!entry.workingCopyId && mode === 'new' && !prefillToken) {
        Object.assign(draft.delivery,{deliveryMethod:'SEQUENCIAL',deliveryCompanyCode:'CJGLS',deliveryChargeType:'FREE',deliveryCharge:'0',freeShipOverAmount:'0',deliveryChargeOnReturn:'0',returnCharge:'0',remoteAreaDeliverable:'N',unionDeliveryType:'NOT_UNION_DELIVERY'});
        Object.assign(draft.settings,{'bundleInfo.bundleType':'SINGLE'});
        Object.assign(draft.options[0].registration,{maximumBuyForPerson:'0',maximumBuyForPersonPeriod:'1',unitCount:'1',outboundShippingTimeDay:'1',parallelImported:'NOT_PARALLEL_IMPORTED',overseasPurchased:'NOT_OVERSEAS_PURCHASED',pccNeeded:'false',adultOnly:'EVERYONE',taxType:'TAX'});
      }
      let cached;
      try{
        cached=JSON.parse(sessionStorage.getItem(cacheKey(entry)));
        if(cached){
          if(Array.isArray(cached.logoUrlInputs))logoUrlInputs=new Map(cached.logoUrlInputs);if(cached.searchTagState){searchTagsEdited=!!cached.searchTagState.tagsEdited;searchColorEdited=!!cached.searchTagState.colorEdited;searchTagContext=cached.searchTagState.context||null;searchTagColorsBase=cached.searchTagState.colorsBase||'';generatedSearchTags=cached.searchTagState.generated||null;}
          if(cached.revision!==entry.revision){entry.recoveryBase=entry.document;recovery('다른 작업에서 revision이 변경되었습니다. 보관된 입력을 확인하고 다시 준비해 주세요.',cached.document);draft=window.CoupangWorkingCopyModel.inflate({...entry,document:cached.document});}
          else if((cached.changes||[]).some(window.CoupangWorkingCopyModel.legacyList)){entry.recoveryBase=entry.document;recovery('이전 목록 입력을 자동 추정하지 않았습니다. 입력을 보관한 뒤 다시 준비해 주세요.',cached.document);draft=window.CoupangWorkingCopyModel.inflate({...entry,document:cached.document});}
          else {let delta=[];
            if(cached.baseDocument)delta=window.CoupangWorkingCopyModel.changes(cached.baseDocument,cached.document,[]);
            else if(entry.mode!=='CREATE')throw Error('이전 입력의 전송 완료 여부를 확인할 수 없습니다. 입력을 보관하고 다시 준비해 주세요.');
            const merged=entry.mode==='CREATE'&&!entry.workingCopyId?cached.document:window.CoupangWorkingCopyModel.apply(entry.document,delta);
            const changes=[...(entry.changes||[]),...delta];
            restoredInput=true;draft=window.CoupangWorkingCopyModel.inflate({...entry,document:merged});rememberedChanges=window.CoupangWorkingCopyModel.changes(entry.document,merged,changes);
          }
        }
      }catch(error){entry.recoveryBase=entry.document;recovery(error.message,cached?.document||draft&&window.CoupangWorkingCopyModel.flatten(draft));}
      if(entry.recoveryMessage)recovery(entry.recoveryMessage,recoveryInput||entry.document);
      appliedCategory = draft.basic.displayCategoryCode;
      rules = mode === 'new' && M.currentRules(entry.categoryRules, draft.basic.displayCategoryCode) ? entry.categoryRules : null;
      ruleError = mode === 'new' && draft.basic.displayCategoryCode && !rules
        ? '카테고리 규격 확인이 만료되었거나 없습니다. 입력을 유지한 채 규격을 다시 조회해 주세요.' : '';
      $('editor-save-working-copy').hidden=false;
      render();
      dirty = restoredInput;
      if(mode==='new')void loadLogoSettings();
      $('editor-validate').textContent = '입력 검증';
      if(entry.blocked){$('editor-load-error').textContent=entry.observation?'이전 전송의 결과를 먼저 확인해 주세요. 입력은 유지됩니다.':'쿠팡 최신 상품을 조회하지 못했습니다. 저장 입력을 유지했습니다. 다시 조회해 주세요.';$('editor-load-error').hidden=false;$('editor-retry').hidden=!!entry.observation;}

      submissionUI.workingCopy(entry.workingCopyId ? {id:entry.workingCopyId,revision:entry.revision,options:draft.options} : null);
    } catch (err) {
      // A token cannot restore server inputs after expiry. Recover only this actor's
      // local draft after fresh authentication and a fresh account-bound CREATE session.
      if(prefillToken && !copyMatch && !editMatch && !draft){
        try{
          const [me,fresh]=await Promise.all([apiGet('/api/auth/me'),apiGet('/api/coupang/working-copies/entry')]);
          const cached=JSON.parse(sessionStorage.getItem(cacheKey(fresh)));
          if(seq===loadSequence && cached?.format===4 && cached.ownerId && String(cached.ownerId)===String(me.id)){
            if(Array.isArray(cached.logoUrlInputs))logoUrlInputs=new Map(cached.logoUrlInputs);if(cached.searchTagState){searchTagsEdited=!!cached.searchTagState.tagsEdited;searchColorEdited=!!cached.searchTagState.colorEdited;searchTagContext=cached.searchTagState.context||null;searchTagColorsBase=cached.searchTagState.colorsBase||'';generatedSearchTags=cached.searchTagState.generated||null;}
            entry=fresh;prefillOwner=cached.ownerId;mode='new';initialDocument=structuredClone(fresh.document);rememberedChanges=[];
            draft=window.CoupangWorkingCopyModel.inflate({...fresh,document:cached.document});render();
            recovery('등록 준비 정보가 만료되거나 사라졌습니다. 동일 사용자·계정의 브라우저 입력을 보존했습니다. 내용을 다시 확인하고 계속해 주세요.',cached.document);
            const panel=$('editor-prefill');panel.hidden=false;panel.innerHTML='<h2>보관한 등록 입력</h2><p>자동 입력 근거의 유효 시간이 지났습니다. 보관한 값은 바꾸지 않았습니다. 전송 전 가격·재고·필수 항목을 다시 확인해 주세요.</p><button type="button" class="btn" id="editor-prefill-resume">보관한 입력으로 계속</button>';
            $('editor-prefill-resume').addEventListener('click',()=>{entry.recoveryMessage=null;entry.recoveryBase=null;recoveryInput=null;rememberedChanges=window.CoupangWorkingCopyModel.changes(initialDocument,window.CoupangWorkingCopyModel.flatten(draft),[]);dirty=true;cacheInput();$('editor-recovery').hidden=true;$('editor-load-error').hidden=true;$('editor-prefill-resume').hidden=true;submissionUI.changed();});
          }
        }catch{/* Do not expose another actor/account's cache or replace the original error. */}
      }
      if (seq === loadSequence && !entry?.recoveryMessage) {
        $('editor-load-error').textContent = err.message;
        $('editor-load-error').hidden = false;
        $('editor-retry').hidden = false;
      }
    } finally {
      if (seq === loadSequence) {
        editRead = null;
        loading = false;
        $('editor-loading').hidden = true;
        if (draft) updateBusy();
      }
    }
    if (draft && seq === loadSequence && (mode === 'edit' || draft.basic.displayCategoryCode)) {
      await new Promise(resolve => setTimeout(resolve, 1100));
      if (seq === loadSequence) {
        if(mode==='edit')await category();
        else {
          if(!M.currentRules(rules,draft.basic.displayCategoryCode)) {
            rules=null;ruleError='카테고리 규격 확인이 만료되었거나 없습니다. 입력을 유지한 채 규격을 다시 조회해 주세요.';
          }
          $('editor-category-status').textContent=ruleError || '카테고리 규격 조회 완료 · 확인 '+rules.checkedAt;
          renderSearchFilters();renderNotices();markSharedFields();
        }
        if (seq === loadSequence) await loadOutboundSummary(seq);
      }
    }
  }
  $('coupang-editor-form').addEventListener('input', ev => {
    if (ev.target.dataset.certEntry) {
      if (ev.target.dataset.certEntry === 'type') certificationEntryType = ev.target.value;else certificationEntryCode = ev.target.value;
      return;
    }
    const path = ev.target.dataset.field;
    if (!path || ev.target.disabled || ev.target.type === 'radio' && !ev.target.checked) return;
    updateField(ev.target, path);
    changed();
    if (mode === 'new' && path.includes('.attributes.')) syncRegistrationNames();
    if (path === 'basic.displayCategoryCode') {
      ruleSequence++;
      categoryRead?.cancel();
      categoryRead = null;
      ruleLoading = false;
      rules = null;
      ruleError = '';
      renderNotices(); markSharedFields();
      updateBusy();
      $('editor-category-status').textContent = '카테고리 규격을 조회해 주세요.';
    }
    if (path === 'basic.sellerProductName' && mode === 'edit') $('editor-title').textContent = ev.target.value || '쿠팡 상품 수정';
    if (path.endsWith('.registration.searchTags')) renderTags();
    if (path.endsWith('.itemName')) syncOptionPickers();
    if (path.includes('.notices.')) updateNoticeReference();
    if (['delivery.deliveryCharge', 'delivery.deliveryChargeOnReturn', 'delivery.returnCharge'].includes(path)) returnTotal();
    if (path.startsWith('documents.')) {
      const n = Number(path.split('.')[1]);
      const button = document.querySelector(`[data-view-document="${n}"]`);
      if (button) button.disabled = !M.url(ev.target.value);
    }
  });
  $('coupang-editor-form').addEventListener('change', ev => {
    if (ev.target.disabled) return;
    if (ev.target.id === 'editor-notice-reference') {
      for (const o of draft.options) for (const notice of o.notices.filter(v => v.category === o.noticeCategory)) {
        if (ev.target.checked) {
          if (notice.content !== noticeReference) noticeBackups.set(notice, notice.content);
          notice.content = noticeReference;
        } else {
          const previous = noticeBackups.get(notice);
          if (notice.content === noticeReference) notice.content = previous && previous !== noticeReference ? previous : '';
          noticeBackups.delete(notice);
        }
      }
      changed();
      renderNotices();
      markSharedFields();
      return;
    }
    if (ev.target.dataset.settingsAction === 'shipping-time' && ev.target.checked) {
      shippingTimeMode = ev.target.value;
      renderDelivery();
      return;
    }
    if (ev.target.dataset.settingsAction && ev.target.checked) {
      settingAction(ev.target.dataset.settingsAction, ev.target.value);
      return;
    }
    if (ev.target.dataset.certEntry) {
      if (ev.target.dataset.certEntry === 'type') certificationEntryType = ev.target.value;else certificationEntryCode = ev.target.value;
      return;
    }
    const path = ev.target.dataset.field;
    if (path && (ev.target.type !== 'radio' || ev.target.checked)) {
      updateField(ev.target, path);
      changed();
      if (path.endsWith('.noticeCategory')) {
        for (const option of draft.options) M.addNotices(option, rules);
        renderNotices();
        markSharedFields();
      }
    }
    if (mode === 'new' && path?.includes('.attributes.')) syncRegistrationNames();
    if (path === 'delivery.deliveryChargeType') {
      if (draft.delivery.deliveryChargeType === 'FREE') {
        draft.delivery.deliveryCharge = '0';
        draft.delivery.freeShipOverAmount = '0';
      } else if (draft.delivery.deliveryChargeType !== 'CONDITIONAL_FREE') draft.delivery.freeShipOverAmount = '0';
      renderDelivery();
    }
    if (path && rules && (path.endsWith('.parallelImported') || path.endsWith('.overseasPurchased'))) {
      M.applyRules(draft, rules);
      renderSettings();
      markSharedFields();
    }
  });
  $('editor-option-tabs').addEventListener('click', ev => {
    const button = ev.target.closest('[data-option-tab]');
    if (button) selectOption(Number(button.dataset.optionTab));
  });
  $('editor-option-tabs').addEventListener('keydown', ev => {
    const button = ev.target.closest('[data-option-tab]');
    if (!button || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(ev.key)) return;
    ev.preventDefault();
    const n = Number(button.dataset.optionTab),
      last = draft.options.length - 1;
    selectOption(ev.key === 'Home' ? 0 : ev.key === 'End' ? last : (n + (ev.key === 'ArrowRight' ? 1 : -1) + draft.options.length) % draft.options.length);
    $('editor-option-tab-' + selected).focus();
  });
  $('editor-retry').addEventListener('click', initialize);
  $('editor-option-add').addEventListener('click', () => {
    if (mode !== 'new') return;
    draft.options.push(M.inheritShared(draft, M.blankOption()));
    if (rules) M.applyRules(draft, rules);
    syncRegistrationNames();
    changed();
    render();
  });
  $('coupang-editor-form').addEventListener('click', ev => {
    const target = ev.target.closest('button');
    if (!target) return;
    const data = target.dataset,
      o = draft.options[selected];
    if (data.addressBook) {
      outboundRead?.cancel();
      addressKind = data.addressBook;
      $('editor-address-title').textContent = addressKind === 'return' ? '반품/교환지 선택' : '출고지 선택';
      $('editor-address-dialog').showModal();
      loadAddressPage(1);
      return;
    }
    if (data.removeOption !== undefined && mode === 'new' && draft.options.length > 1) {
      const n = Number(data.removeOption);
      draft.options.splice(n, 1);
      selected = Math.min(selected, draft.options.length - 1);
      changed();
      render();
    }
    if (data.r2Images !== undefined) {
      void addR2Images();
      return;
    }
    if (data.addImage !== undefined) {
      void addR2Images('images', $('editor-image-url').value.trim());
      return;
    }
    if (data.imageAction === 'edit' || data.cropContent !== undefined || data.cropHtml !== undefined) {
      if(imageReading||saveBusy)return;
      const image=data.imageAction==='edit'?o.images[Number(data.index)]:data.cropContent!==undefined?o.contents[Number(data.cropContent)]:null;
      const content=data.cropHtml!==undefined?o.contents[Number(data.cropHtml)]:null;
      const url=content?window.ImageCropEditor.htmlImages(content.content)[Number(data.cropImage)]?.url:image?.url||image?.content;
      imageReading=true;updateBusy();
      window.ImageCropEditor.open({url,assetId:image?.assetId||null,square:data.imageAction==='edit'&&image?.type==='REPRESENTATION'}).then(result=>{if(!result)return;if(content)content.content=window.ImageCropEditor.replaceHtml(content.content,Number(data.cropImage),result.url);else if(data.imageAction==='edit')Object.assign(image,result);else {image.content=result.url;image.assetId=result.assetId;}changed();renderMedia();}).catch(error=>imageError(error.message)).finally(()=>{imageReading=false;updateBusy();});return;
    }
    if (data.imageAction === 'remove') {
      const n = Number(data.index),
        i = o.images[n];
      o.images.splice(n, 1);
      o.images.forEach((v, k) => v.order = k);
      changed();
      renderMedia();
    }
    if (data.addContent === 'IMAGE') {
      void addR2Images('contents');
      return;
    }
    if (data.addContent) {
      o.contents.push({
        type: data.addContent,
        detailType: data.addContent === 'IMAGE' ? 'IMAGE' : 'TEXT',
        content: ''
      });
      changed();
      renderMedia();
    }
    if (data.removeContent !== undefined) {
      o.contents.splice(Number(data.removeContent), 1);
      changed();
      renderMedia();
    }
    if (data.addCert !== undefined) {
      const type = $('editor-cert-type').value,
        code = $('editor-cert-code').value.trim(),
        rule = rules?.certifications.find(c => c.type === type);
      const error = message => {
        $('editor-cert-error').textContent = message;
        $('editor-cert-error').hidden = false;
      };
      if (!rule || ['NOT_REQUIRED', 'PRESENTED_IN_DETAIL_PAGE'].includes(type)) {
        error('인증 유형을 선택해 주세요.');
        return;
      }
      if (rule.dataType === 'CODE' && !code) {
        error('인증번호를 입력해 주세요.');
        return;
      }
      if (draft.options[0].certifications.some(c => c.type === type)) {
        error('이미 추가된 인증 유형입니다. 아래 인증번호 입력칸에서 수정해 주세요.');
        return;
      }
      for (const option of draft.options) {
        const existing = option.certifications.find(c => c.type === type);
        if (existing) existing.code = code;else option.certifications.push({
          type,
          code,
          attachments: []
        });
      }
      certificationEntryType = '';
      certificationEntryCode = '';
      changed();
      renderSettings();
      markSharedFields();
    }
    if (data.removeCert !== undefined) {
      if (rules?.certifications.some(c => c.type === data.removeCert && c.required === 'MANDATORY')) return;
      for (const option of draft.options) option.certifications = option.certifications.filter(c => c.type !== data.removeCert);
      changed();
      renderSettings();
      markSharedFields();
    }
    if(data.colorTonePath){
      if(loading||saveBusy||ruleLoading||imageReading||target.disabled)return;
      const path=data.colorTonePath,value=M.suggestColorTone(draft.basic.displayProductName,draft.basic.sellerProductName),keys=split(path),status=$('color-tone-status-'+keys[1]+'-'+keys[3]);
      if(!value){status.textContent='제목에서 하나의 색상계열을 판단할 수 없습니다. 직접 선택해 주세요.';return;}
      if(M.sharedValues(draft,path).some(v=>v&&v!==value)&&!confirm('선택한 색상계열을 '+value+'으로 바꿀까요?'))return;
      M.setSharedValue(draft,path,value);$(id(path)).value=value;delete $(id(path)).dataset.mixed;status.textContent='제목에서 '+value+'을 선택했습니다. 전송 전 확인해 주세요.';changed();
    }
    if (data.removeTag !== undefined) {
      searchTagsEdited=true;
      const tags = String(draft.options[0].registration.searchTags || '').split(',').map(t => t.trim()).filter(Boolean);
      tags.splice(Number(data.removeTag), 1);
      M.setSharedValue(draft, 'options.0.registration.searchTags', tags.join(', '));
      $('field-options-0-registration-searchTags').value = tags.join(', ');
      changed();
      renderTags();
    }
    if (data.viewDocument !== undefined) {
      const d = draft.documents[Number(data.viewDocument)],
        url = M.url(d?.path || d?.vendorPath);
      if (url) window.open(url, '_blank', 'noopener,noreferrer');
    }
    if (data.removeDocument !== undefined) {
      draft.documents.splice(Number(data.removeDocument), 1);
      changed();
      renderSettings();
      markSharedFields();
    }
    if (data.addDocument !== undefined) {
      const name = $('editor-document-type').value;
      if (name && !draft.documents.some(d => d.templateName === name)) {
        draft.documents.push({
          templateName: name,
          path: ''
        });
        changed();
        renderSettings();
        markSharedFields();
      }
    }
  });
  function imageError(message) {
    $('editor-image-error').textContent = message;
    $('editor-image-error').hidden = false;
  }
  const imageLimitMessage = images => '이미지 개수 제한을 확인해 주세요. 대표 1개 · 추가 9개' + (images.some(i => i.type === 'USED_PRODUCT') ? ' · 중고 상태 4개' : '');
  let imageDrag = null,
    touchDrag = null;
  const imageArea = $('editor-media-fields');
  function clearDropPreview() {
    document.querySelectorAll('.image-drop-marker').forEach(el => el.remove());
    document.querySelectorAll('.image-drop-target').forEach(el => el.classList.remove('image-drop-target'));
  }
  function clearImageDrag() {
    clearDropPreview();
    document.querySelectorAll('.image-dragging,.image-drop-target').forEach(el => el.classList.remove('image-dragging', 'image-drop-target'));
    touchDrag?.ghost?.remove();
    imageDrag = null;
    touchDrag = null;
  }
  function imageDestination(target, x, y) {
    const group = target?.closest('[data-image-type]');
    if (!group || !imageArea.contains(group)) return null;
    const cards = [...group.querySelectorAll('[data-image-index]')];
    // Gaps and wrapped rows resolve to the nearest card, using the same destination for preview and drop.
    const distance = card => {
      const b = card.getBoundingClientRect();
      return Math.max(b.left - x, 0, x - b.right) ** 2 + Math.max(b.top - y, 0, y - b.bottom) ** 2;
    };
    const card = target.closest('[data-image-index]') || cards.reduce((nearest, c) => !nearest || distance(c) < distance(nearest) ? c : nearest, null);
    let before = null;
    if (card) {
      const box = card.getBoundingClientRect();
      before = x < box.x + box.width / 2 ? Number(card.dataset.imageIndex) : cards[cards.indexOf(card) + 1]?.dataset.imageIndex ?? null;
      if (before !== null) before = Number(before);
    }
    return {
      group,
      type: group.dataset.imageType,
      before
    };
  }
  function showDropPreview(dest) {
    clearDropPreview();
    if (!dest) return;
    dest.group.classList.add('image-drop-target');
    const cards = [...dest.group.querySelectorAll('[data-image-index]')];
    const anchor = dest.before === null ? cards.at(-1) : cards.find(c => Number(c.dataset.imageIndex) === dest.before);
    const marker = document.createElement('span');
    marker.className = 'image-drop-marker';
    marker.setAttribute('aria-hidden', 'true');
    marker.dataset.label = dest.type === 'REPRESENTATION' ? '대표 이미지로 변경' : '여기에 놓기';
    const bounds = dest.group.getBoundingClientRect();
    if (anchor) {
      const box = anchor.getBoundingClientRect();
      marker.style.left = (dest.before === null ? box.right : box.left) - bounds.left - 2 + 'px';
      marker.style.top = box.top - bounds.top + 'px';
      marker.style.height = box.height + 'px';
    } else {
      marker.classList.add('image-drop-marker-empty');
    }
    dest.group.append(marker);
  }
  function dropImage(destination) {
    const drag = imageDrag;
    if (!drag || !destination || draft.options[selected] !== drag.option) {
      clearImageDrag();
      return;
    }
    const from = drag.option.images.indexOf(drag.image),
      ok = M.moveImage(drag.option.images, from, destination.type, destination.before);
    clearImageDrag();
    if (ok) {
      changed();
      renderMedia();
    } else if (from >= 0 && destination.before !== from) imageError(imageLimitMessage(drag.option.images));
  }
  imageArea.addEventListener('dragstart', ev => {
    const card = ev.target.closest('[data-image-index]');
    if (!card || ev.target.closest('button')) {
      ev.preventDefault();
      return;
    }
    const option = draft.options[selected];
    imageDrag = {
      option,
      image: option.images[Number(card.dataset.imageIndex)]
    };
    ev.dataTransfer.effectAllowed = 'move';
    ev.dataTransfer.setData('text/plain', card.dataset.imageIndex);
    card.classList.add('image-dragging');
  });
  imageArea.addEventListener('dragover', ev => {
    if (!imageDrag) return;
    const dest = imageDestination(ev.target, ev.clientX, ev.clientY);
    showDropPreview(dest);
    if (!dest) return;
    ev.preventDefault();
    ev.dataTransfer.dropEffect = 'move';
  });
  imageArea.addEventListener('dragleave', ev => {
    if (!imageArea.contains(ev.relatedTarget)) clearDropPreview();
  });
  imageArea.addEventListener('drop', ev => {
    if (!imageDrag) return;
    ev.preventDefault();
    dropImage(imageDestination(ev.target, ev.clientX, ev.clientY));
  });
  imageArea.addEventListener('dragend', clearImageDrag);
  imageArea.addEventListener('pointerdown', ev => {
    if (ev.pointerType === 'mouse' || ev.target.closest('button')) return;
    const card = ev.target.closest('[data-image-index]');
    if (!card) return;
    const option = draft.options[selected];
    touchDrag = {
      pointer: ev.pointerId,
      x: ev.clientX,
      y: ev.clientY,
      card,
      option,
      image: option.images[Number(card.dataset.imageIndex)]
    };
    imageArea.setPointerCapture(ev.pointerId);
  });
  imageArea.addEventListener('pointermove', ev => {
    if (!touchDrag || ev.pointerId !== touchDrag.pointer) return;
    if (!imageDrag && Math.hypot(ev.clientX - touchDrag.x, ev.clientY - touchDrag.y) < 8) return;
    ev.preventDefault();
    if (!imageDrag) {
      imageDrag = {
        option: touchDrag.option,
        image: touchDrag.image
      };
      touchDrag.card.classList.add('image-dragging');
      const ghost = touchDrag.card.cloneNode(true);
      ghost.classList.add('image-touch-ghost');
      ghost.removeAttribute('id');
      ghost.setAttribute('aria-hidden', 'true');
      document.body.append(ghost);
      touchDrag.ghost = ghost;
    }
    touchDrag.ghost.style.left = ev.clientX + 12 + 'px';
    touchDrag.ghost.style.top = ev.clientY + 12 + 'px';
    const dest = imageDestination(document.elementFromPoint(ev.clientX, ev.clientY), ev.clientX, ev.clientY);
    showDropPreview(dest);
    if (ev.clientY < 60) window.scrollBy(0, -18);else if (ev.clientY > window.innerHeight - 60) window.scrollBy(0, 18);
  });
  imageArea.addEventListener('pointerup', ev => {
    if (!touchDrag || ev.pointerId !== touchDrag.pointer) return;
    const dest = imageDestination(document.elementFromPoint(ev.clientX, ev.clientY), ev.clientX, ev.clientY);
    if (imageArea.hasPointerCapture(ev.pointerId)) imageArea.releasePointerCapture(ev.pointerId);
    if (imageDrag) dropImage(dest);else clearImageDrag();
  });
  imageArea.addEventListener('pointercancel', () => {
    if (touchDrag) clearImageDrag();
  });
  imageArea.addEventListener('keydown', ev => {
    const card = ev.target.closest('[data-image-index]');
    if (!card || ev.target.closest('button') || !['ArrowLeft', 'ArrowRight', 'r', 'R', 'd', 'D'].includes(ev.key)) return;
    ev.preventDefault();
    const o = draft.options[selected],
      from = Number(card.dataset.imageIndex),
      image = o.images[from];
    let type = image.type,
      before = null;
    if (ev.key.toLowerCase() === 'r') type = 'REPRESENTATION';else if (ev.key.toLowerCase() === 'd') type = 'DETAIL';else {
      const group = o.images.map((i, k) => ({
          i,
          k
        })).filter(v => v.i.type === type),
        position = group.findIndex(v => v.k === from);
      if (ev.key === 'ArrowLeft') {
        if (position === 0) return;
        before = group[position - 1].k;
      } else {
        if (position === group.length - 1) return;
        before = group[position + 2]?.k ?? null;
      }
    }
    if (M.moveImage(o.images, from, type, before)) {
      changed();
      renderMedia();
      document.querySelector(`[data-image-index="${o.images.indexOf(image)}"]`)?.focus();
    } else imageError(imageLimitMessage(o.images));
  });
  let previewTimer;
  $('editor-description-fields').addEventListener('input', ev => {
    if (ev.target.dataset.field?.includes('.contents.')) {
      clearTimeout(previewTimer);
      previewTimer = setTimeout(previews, 300);
    }
  });

  async function addR2Images(kind = 'images', url = null) {
    if (saveBusy || imageReading) return;
    const owner = draft,
      option = draft.options[selected];
    imageReading = true;
    updateBusy();
    try {
      const images = url !== null ? [await window.R2ImagePicker.resolve(url)] : await window.R2ImagePicker.open({
        kind: kind === 'contents' ? 'description' : 'images'
      });
      if (!images || draft !== owner || !draft.options.includes(option)) return;
      if (kind === 'images' && images.length > 10 - option.images.filter(i => i.type !== 'USED_PRODUCT').length) throw Error('대표 1개 · 추가 9개까지 선택해 주세요.');
      for (const image of images) if (kind === 'contents') option.contents.push({
        id: AppUI.uuid(),
        type: 'IMAGE',
        detailType: 'IMAGE',
        content: image.url
      });else option.images.push({
        id: AppUI.uuid(),
        url: image.url,
        type: M.nextImageType(option.images),
        order: option.images.length
      });
      changed();
      renderMedia();
    } catch (err) {
      imageError(err.message);
    } finally {
      imageReading = false;
      updateBusy();
    }
  }
  FormActions.bindExplicitSubmit($('coupang-editor-form'), $('editor-validate'), ev => {
    ev.preventDefault();
    if (loading || ruleLoading || saveBusy || !draft) return;
    const errors = M.validate(draft, rules, mode),
      box = $('editor-validation');
    document.querySelectorAll('[aria-invalid]').forEach(el => el.removeAttribute('aria-invalid'));
    document.querySelectorAll('[data-error-for]').forEach(el => el.hidden = true);
    box.hidden = false;
    box.className = errors.length ? 'editor-validation form-error' : 'editor-validation editor-validation-success';
    box.textContent = errors.length ? `${errors.length}개 항목을 확인해 주세요. ${errors[0].message}` : '입력 검증 완료';
    if (!errors.length) {

      box.focus();
      return;
    }
    const first = errors[0],
      match = /^options\.(\d+)/.exec(first.path);
    if (match) selectOption(Number(match[1]));
    for (const error of errors) {
      const input = document.querySelector(`[data-field="${CSS.escape(error.path)}"]`);
      if (input) input.setAttribute('aria-invalid', 'true');
      const hint = document.querySelector(`[data-error-for="${CSS.escape(error.path)}"]`);
      if (hint) {
        hint.textContent = error.message;
        hint.hidden = false;
      }
    }
    const input = document.querySelector(`[data-field="${CSS.escape(first.path)}"]`);
    const section = input?.closest('.coupang-form-section')?.id || sectionFor(first.path);
    if (input) for (let parent = input.parentElement; parent; parent = parent.parentElement) if (parent.tagName === 'DETAILS') parent.open = true;
    $(section).scrollIntoView({
      behavior: 'smooth',
      block: 'start'
    });
    if (input && !input.disabled) input.focus({
      preventScroll: true
    });else box.focus({
      preventScroll: true
    });
  });
  function sectionFor(path) {
    if (path.startsWith('delivery.')) return returnKeys.includes(path.slice(9)) ? 'editor-returns' : 'editor-delivery';
    if (['delivery.deliveryCharge', 'delivery.deliveryChargeOnReturn', 'delivery.returnCharge'].includes(path)) returnTotal();
    if (path.startsWith('documents.')) return 'editor-documents';
    if (path.startsWith('settings.') || path.includes('.certifications.')) return 'editor-settings';
    if (path.includes('.images')) return 'editor-media';
    if (path.includes('.contents')) return 'editor-description';
    if (path.includes('.notices') || path.endsWith('.noticeCategory')) return 'editor-notices';
    if (path.endsWith('.registration.searchTags')) return 'editor-search-tags';
    if (path.includes('.attributes.')) return get(path.replace(/\.value$/, '.exposed')) === 'EXPOSED' ? 'editor-option-table-section' : 'editor-search-filters';
    if (path.includes('.registration.')) return saleKeys.some(k => path.endsWith('.' + k)) ? 'editor-option-table-section' : 'editor-settings';
    if (path.startsWith('options')) return 'editor-option-table-section';
    return 'editor-basic';
  }
  document.querySelectorAll('[data-editor-exit]').forEach(el => el.addEventListener('click', ev => {
    ev.preventDefault();
    if (dirty && !window.confirm('입력한 내용을 버리고 목록으로 돌아갈까요?')) return;
    try{if(entry)sessionStorage.removeItem(cacheKey(entry));}catch{}
    dirty = false;
    location.assign('/coupang/products');
  }));
  window.addEventListener('beforeunload', ev => {
    if (dirty) {
      ev.preventDefault();
      ev.returnValue = '';
    }
  });
  window.addEventListener('pagehide', () => {
    clearImageDrag();
    addressSequence++;
    addressRead?.cancel();
    outboundRead?.cancel();
    loadSequence++;
    ruleSequence++;
    editRead?.cancel();
    categoryRead?.cancel();

    clearTimeout(previewTimer);
  });
  $('coupang-editor-form').addEventListener('error', ev => {
    if (ev.target.tagName === 'IMG') {
      ev.target.hidden = true;
      const fallback = document.createElement('span');
      fallback.textContent = '이미지 없음';
      ev.target.parentElement.append(fallback);
    }
  }, true);
  let recoveryDownloaded=false;
  $('editor-recovery-download').addEventListener('click',()=>{
    const input=recoveryInput||window.CoupangWorkingCopyModel.flatten(draft);const url=URL.createObjectURL(new Blob([JSON.stringify(input,null,2)],{type:'application/json'}));
    const link=document.createElement('a');link.href=url;link.download='coupang-input-recovery.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);recoveryDownloaded=true;
  });
  $('editor-reprepare').addEventListener('click',()=>{
    if(!entry.recoveryBase||!window.confirm('현재 입력을 보관하고 최신 값으로 다시 준비할까요?'))return;
    try{sessionStorage.setItem(cacheKey(entry)+'.recovery.'+Date.now(),JSON.stringify(recoveryInput||window.CoupangWorkingCopyModel.flatten(draft)));}
    catch{if(!recoveryDownloaded){$('editor-load-error').textContent='입력을 내려받아 보관한 뒤 다시 준비해 주세요.';return;}}
    entry.document=entry.recoveryBase;entry.changes=[];entry.recoveryMessage=null;entry.recoveryBase=null;initialDocument=structuredClone(entry.document);rememberedChanges=[];draft=window.CoupangWorkingCopyModel.inflate(entry);recoveryInput=null;recoveryDownloaded=false;
    try{sessionStorage.removeItem(cacheKey(entry));}catch{}dirty=false;$('editor-recovery').hidden=true;$('editor-load-error').hidden=true;render();submissionUI.changed();
  });
  async function saveLocal(renewed=false){
    if(!draft||loading||saveBusy)return null;
    if(entry.recoveryMessage){recovery(entry.recoveryMessage,window.CoupangWorkingCopyModel.flatten(draft));return null;}
    localSaveBusy=true;updateBusy();$('editor-save-error').hidden=true;$('editor-validation').textContent='임시 저장 중…';
    const previousCacheKey=cacheKey(entry);
    try{
      const document=window.CoupangWorkingCopyModel.flatten(draft),changes=window.CoupangWorkingCopyModel.changes(initialDocument,document,rememberedChanges);
      const payload={sessionId:entry.sessionId,expectedRevision:entry.revision,document,changes};
      const saved=entry.workingCopyId?await apiRequest('/api/coupang/working-copies/'+entry.workingCopyId,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}):await apiPost('/api/coupang/working-copies',payload);
      entry=saved;rememberedChanges=saved.changes||changes;initialDocument=structuredClone(saved.document);draft=window.CoupangWorkingCopyModel.inflate(saved);mode=saved.mode==='UPDATE'?'edit':'new';dirty=false;
      history.replaceState(null,'','/coupang/working-copies/'+saved.workingCopyId+'/edit');
      try{sessionStorage.removeItem(previousCacheKey);sessionStorage.removeItem(cacheKey(entry));}catch{}
      render();$('editor-validation').hidden=false;$('editor-validation').textContent='임시 저장 완료';
      const result={id:saved.workingCopyId,revision:saved.revision,options:draft.options};submissionUI.workingCopy(result);return result;
    }catch(error){
      if(['COUPANG_WORKING_COPY_EXPIRED','COUPANG_WORKING_COPY_NOT_FOUND'].includes(error.code)&&!renewed){
        try{
          const input=window.CoupangWorkingCopyModel.flatten(draft),delta=window.CoupangWorkingCopyModel.changes(initialDocument,input,[]);
          const fresh=await apiGet(entry.workingCopyId?'/api/coupang/working-copies/'+entry.workingCopyId:'/api/coupang/working-copies/entry'+(entry.productId?'?productId='+entry.productId:''));
          if(fresh.revision!==entry.revision||fresh.accountKey!==entry.accountKey||fresh.mode!==entry.mode||fresh.productId!==entry.productId)throw Error('계정·revision·상품이 변경되었습니다. 현재 입력은 유지됩니다.');
          if(fresh.recoveryMessage)throw Error(fresh.recoveryMessage);
          const changes=[...(fresh.changes||[]),...delta];
          const merged=fresh.mode==='CREATE'?input:window.CoupangWorkingCopyModel.apply(fresh.document,changes);
          entry=fresh;initialDocument=structuredClone(fresh.document);rememberedChanges=window.CoupangWorkingCopyModel.changes(fresh.document,merged,changes);draft=window.CoupangWorkingCopyModel.inflate({...fresh,document:merged});localSaveBusy=false;updateBusy();return await saveLocal(true);
        }catch(renewError){error=renewError;}
      }
      $('editor-save-error').textContent=error.message;$('editor-save-error').hidden=false;return null;
    }finally{localSaveBusy=false;updateBusy();}
  }
  FormActions.bindExplicitSubmit($('coupang-editor-form'), $('editor-category-load'), category);
  FormActions.bindExplicitSubmit($('coupang-editor-form'), $('editor-save-working-copy'), () => saveLocal());
  submissionUI=window.CoupangSubmissionUI.create({
    canAct:()=>!!draft&&!loading&&!ruleLoading&&!saveBusy&&!entry?.blocked&&!entry?.recoveryMessage,
    busy:value=>{submissionBusy=value;updateBusy();},save:saveLocal,
    prepare:()=>({sessionId:entry.sessionId,changes:rememberedChanges}),
    settled:async result=>{
      if(!['SUCCEEDED','PARTIAL'].includes(result.status)&&!result.revised)return;
      if(result.status==='SUCCEEDED'&&!result.revised&&entry.appliedRevision!=null&&result.revision<=entry.appliedRevision)return;
      const delta=window.CoupangWorkingCopyModel.changes(initialDocument,window.CoupangWorkingCopyModel.flatten(draft),[]);
      const fresh=await apiGet('/api/coupang/working-copies/'+entry.workingCopyId+(result.target?.mode==='CREATE'?'?observed=true':''));
      if(fresh.revision!==entry.revision||fresh.accountKey!==entry.accountKey)throw Error('계정이나 작업본 revision이 변경되었습니다. 작성 중인 입력은 유지됩니다.');
      const input=window.CoupangWorkingCopyModel.apply(fresh.document,delta);
      entry=fresh;initialDocument=structuredClone(fresh.document);rememberedChanges=fresh.changes||[];draft=window.CoupangWorkingCopyModel.inflate({...fresh,document:input});mode=fresh.mode==='UPDATE'?'edit':'new';dirty=delta.length>0;
      if(dirty)cacheInput();else try{sessionStorage.removeItem(cacheKey(entry));}catch{}
      render();submissionUI.workingCopy({id:fresh.workingCopyId,revision:fresh.revision,options:draft.options});
    },
    beforePrepare:async()=>{if(mode==='new'&&!M.currentRules(rules,draft.basic.displayCategoryCode)){ruleError='카테고리 규격 확인이 만료되었거나 없습니다. 입력을 유지한 채 규격을 다시 조회해 주세요.';$('editor-validation').hidden=false;$('editor-validation').textContent=ruleError;renderSearchFilters();renderNotices();return false;}const errors=M.validate(draft,rules,mode);if(errors.length){$('editor-validation').hidden=false;$('editor-validation').textContent=errors[0].message;return false;}return true;}
  });
  initialize();
})();
