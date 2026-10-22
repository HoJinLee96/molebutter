"use strict";
window.CoupangQuickEdit = {create({onApplied=async()=>{}}={}){
  const {$,escape:e}=AppUI,dialog=$('coupang-quick-edit');
  let context=null,target=null,seq=0,busy=false,writing=false,preview=null,execution=null,timer=null,key=null,storageKey=null,executionRead=0,cachedBasis=null,valueEdited=false,costEdited=false,selectedStockId=null,historyOpen=false,historyRead=0,receipt=null;
  const money=v=>v==null?'미확인':Number(v).toLocaleString('ko-KR',{maximumFractionDigits:2})+'원';
  const endpoint=()=>`/api/coupang/products/${encodeURIComponent(target.product)}/options/${encodeURIComponent(target.vendor)}/quick-edit`;
  const current=n=>n===seq&&dialog.open;
  function error(err){setError('quick-edit-error',err?.message||String(err));}
  function controls(){const running=!!execution||!!receipt;for(const node of $('quick-edit-form').querySelectorAll('input,select,button'))node.disabled=busy||!context||context.registry.blocked||running;$('quick-edit-close').disabled=writing;$('quick-edit-reload').disabled=busy;$('quick-edit-history-toggle').disabled=writing;const result=$('quick-edit-result');if(result)result.disabled=busy;}
  const receiptKey=()=>storageKey+'.execution';
  function rememberReceipt(){sessionStorage.setItem(receiptKey(),JSON.stringify(receipt));}
  function clearReceipt(){receipt=null;try{sessionStorage.removeItem(receiptKey());}catch{}}
  const displayValue=v=>target.kind==='PRICE'?money(v):(v==null?'미확인':Number(v).toLocaleString('ko-KR')+'개');
  function delta(){const v=$('quick-edit-value').value;$('quick-edit-delta').textContent=context?displayValue(context.currentValue)+' → '+(v===''?'—':displayValue(Number(v))):'';}
  function cache(){if(!storageKey||execution?.status==='SUCCEEDED')return;try{if(context)cachedBasis={revision:context.entry.revision,pending:context.pendingValue,applied:context.entry.appliedRevision};sessionStorage.setItem(storageKey,JSON.stringify({...cachedBasis,version:2,valueEdited,costEdited,selectedStockId,value:$('quick-edit-value').value,cost:$('quick-edit-cost').value}));}catch{}}
  function invalidate(){preview=null;key=null;$('quick-edit-preview').innerHTML='';cache();calculate();delta();controls();}
  function calculationHtml(c){return `<details><summary>상세 계산 (VAT 10%)</summary><p>계산 가격 ${e(money(c.calculatedPrice))} · 제안 가격 ${e(money(c.suggestedPrice))}</p><p>희망 마진율 ${Number(context.settings.marginPercent)}% · 차이 ${(Number(c.marginPercent)-Number(context.settings.marginPercent)).toFixed(2)}%p${Number(c.marginPercent)<Number(context.settings.marginPercent)?' · 희망 마진율 미만':''}</p><dl class="quick-calculation">${[['사입 공급가액','purchaseSupply'],['사입 매입세액','purchaseVat'],['배송 공급가액','shippingSupply'],['배송 매입세액','shippingVat'],['판매 공급가액','saleSupply'],['매출부가세','saleVat'],['판매수수료','commission'],['수수료 부가세','commissionVat'],['예상 납부 부가세','payableVat']].map(([name,k])=>`<dt>${name}</dt><dd>${e(money(c[k]))}</dd>`).join('')}</dl></details>`;}
  const stockOptions=()=>context?.source.stockOptions??context?.source.stocks??[];
  const ownedMode=()=>target?.kind==='STOCK'&&['UNAVAILABLE','SELECTED_MISSING'].includes(context?.source.availability)&&context.source.ownedQuantity!=null;
  function stockDefault(){if(Object.hasOwn(context?.source||{},'stockProposal'))return context.source.stockProposal?.selectionRequired?'':context.source.stockProposal?.quantity??'';if(!ownedMode())return stockCandidate()?.quantity??'';const s=context.source;return s.ownedQuantity===0?0:s.ownedOptions.length===1&&s.couponOptionCount===1?s.ownedQuantity:'';}
  function stockCandidate(){if(!context)return null;const source=context.source;return selectedStockId!=null?stockOptions().find(s=>s.id===selectedStockId):((source.stockOptionCount??source.stocks.length)===1?stockOptions()[0]:null);}
  function stockNote(){const stock=ownedMode()?{previous:context.source.previousAvailability,checkedAt:context.source.availabilityCheckedAt}:stockCandidate(),node=$('quick-edit-stock-previous');node.textContent=ownedMode()?'이전 매입 상태':'이전 조회값';node.hidden=target.kind!=='STOCK'||valueEdited||context?.pendingValue!=null||!stock?.previous;node.title=stock?.checkedAt?AppUI.stamp(stock.checkedAt):'확인 시각 없음';}
  function showStatus(text){$('quick-edit-status').textContent=text;$('quick-edit-status').hidden=!text;}
  function renderCalculation(c){$('quick-edit-margin').innerHTML=`<p class="quick-edit-margin">마진 <strong>${e(money(Math.round(c.profit)))}</strong> · 마진율 <strong>${Number(c.marginPercent).toFixed(2)}%</strong></p>`;const suggested=CoupangPricing.calculate(context.settings,Number($('quick-edit-cost').value));$('quick-edit-calculation').innerHTML=(Number(suggested.profit)<-0.00000001?'<p class="form-error">제안 가격에서도 손실이 발생합니다. 계산 설정을 확인하고 판매가격을 직접 조정해 주세요.</p>':'')+calculationHtml(c);}
  function calculate(){if(!context||target.kind!=='PRICE')return;try{const cost=$('quick-edit-cost').value,value=$('quick-edit-value').value;if(cost===''){$('quick-edit-margin').textContent='';$('quick-edit-calculation').textContent='사입가를 입력하면 마진을 계산합니다. 판매가격은 직접 입력할 수 있습니다.';return;}const c=CoupangPricing.calculate(context.settings,Number(cost),value===''?null:Number(value));renderCalculation(c);}catch(err){$('quick-edit-margin').textContent='';$('quick-edit-calculation').textContent=err.message;}}
  async function reload(preserve=false){
    const n=++seq;busy=true;context=null;controls();showStatus('현재 쿠팡값과 선정 매입처를 확인하는 중…');
    try{
      const data=await apiGet(endpoint()+'?'+new URLSearchParams({kind:target.kind,internalProductId:target.internal}));if(!current(n))return;
      context=data;cachedBasis={revision:data.entry.revision,pending:data.pendingValue,applied:data.entry.appliedRevision};
      storageKey=`molebutter.coupang.quick.${data.entry.accountKey}.${target.product}.${target.vendor}.${target.kind}`;
      const inventory=data.currentInventory||{},price=inventory.salePrice??(target.kind==='PRICE'?data.currentValue:null),stock=inventory.amountInStock??(target.kind==='STOCK'?data.currentValue:null);
      const emphasize=(text,kind)=>target.kind===kind?`<strong>${text}</strong>`:text;
      $('quick-edit-identity').innerHTML=`<strong>${e(data.source.productCode)}</strong> · 옵션 ${AppUI.coupangOptionLink(data.entry.observation?.basic?.productId||data.registry.state.product?.productId,target.vendor)}`;
      $('quick-edit-source').innerHTML=`<p>${emphasize('현재 가격 '+e(money(price)),'PRICE')} · ${emphasize('현재 재고 '+e(stock==null?'미확인':stock+'개'),'STOCK')}</p>`;
      $('quick-edit-margin').hidden=target.kind!=='PRICE';$('quick-edit-cost-field').hidden=target.kind!=='PRICE';$('quick-edit-price-calculation').hidden=target.kind!=='PRICE';
      $('quick-edit-stock').hidden=ownedMode()||target.kind!=='STOCK'||(data.source.stockOptionCount??data.source.stocks.length)<=1;
      const owned=$('quick-edit-owned');owned.hidden=!ownedMode();owned.innerHTML=ownedMode()?`${data.source.availabilityReason==='SELECTED_LISTING_MISSING'?`<span>선정 판매글 미발견 · ${e(data.source.availabilityCheckedAt?AppUI.stamp(data.source.availabilityCheckedAt):'확인 시각 미기록')}${data.source.previousAvailability?' · 이전 검색 근거':''}</span>`:''}<strong>보유 재고 ${e(data.source.ownedQuantity)}개</strong>${data.source.ownedOptions.map(o=>`<span>${e([o.color,o.size].filter(Boolean).join(' / ')||'기본 옵션')} ${e(o.quantity)}개</span>`).join('')}${stockDefault()===''?'<span>옵션별 보유 수량을 확인하고 직접 입력해 주세요.</span>':''}`:'';
      $('quick-edit-settings').textContent=`배송비 ${money(data.settings.shippingCost)} · 수수료 ${Number(data.settings.commissionPercent)}% · 희망 마진 ${Number(data.settings.marginPercent)}%`;
      const input=$('quick-edit-value');input.type=target.kind==='PRICE'?'text':'number';input.inputMode='numeric';input.step=target.kind==='PRICE'?'10':'1';input.min=target.kind==='PRICE'?'10':'0';input.max=target.kind==='PRICE'?'9007199254740991':'99999';$('quick-edit-value-label').textContent=target.kind==='PRICE'?'변경 판매가격 (원)':'변경 판매 가능 수량 (개)';
      $('quick-edit-stock-option').innerHTML='<option value="">옵션 선택</option>'+stockOptions().map((s,i)=>`<option value="${i}">${e(s.label||s.id||'옵션')} · ${s.quantity==null?'재고 미확인':e(s.quantity)+'개'}${s.state&&!(s.quantity==null&&s.state==='STOCK_UNKNOWN')?' · '+e(({AVAILABLE:'구매 가능',SOLD_OUT:'품절',UNAVAILABLE:'구매 불가',STOCK_UNKNOWN:'재고 미확인'})[s.state]||s.state):''}${s.previous?' · 이전 조회값':''}</option>`).join('');
      let saved;if(!preserve)try{saved=JSON.parse(sessionStorage.getItem(storageKey));}catch{}
      if(saved&&saved.revision===data.entry.revision&&saved.pending===data.pendingValue&&saved.applied===data.entry.appliedRevision){
        valueEdited=saved.version===2?!!saved.valueEdited:saved.value!=null&&saved.value!=='';
        costEdited=saved.version===2?!!saved.costEdited:saved.cost!=null&&saved.cost!=='';
        selectedStockId=saved.selectedStockId??null;
        if(valueEdited)input.value=saved.value??'';if(costEdited)$('quick-edit-cost').value=saved.cost??'';
      }
      if(!costEdited)$('quick-edit-cost').value=data.source.purchaseCost??'';
      if(!valueEdited)input.value=data.pendingValue??(target.kind==='PRICE'?(data.calculation?.suggestedPrice??data.currentValue):stockDefault());
      const selection=stockOptions().findIndex(s=>s.id===selectedStockId);$('quick-edit-stock-option').value=selection<0?'':String(selection);
      delta();showStatus(data.registry.blocked?'전송 결과 확인이 필요한 상품입니다. 결과를 확인해 주세요.':'');stockNote();calculate();
      if(!preserve){try{receipt=JSON.parse(sessionStorage.getItem(receiptKey()));}catch{}if(receipt){valueEdited=true;input.value=String(receipt.value);delta();calculate();}}
      if(receipt&&!execution){if(receipt.executionId){try{const recovered=await apiGet('/api/coupang/submissions/'+encodeURIComponent(receipt.executionId));if(!current(n))return;execution=recovered;await settled();}catch(err){if(current(n)){error(err);renderUncertain();}}}else renderUncertain();}
      if(execution)poll();
    }catch(err){if(current(n)){error(err);showStatus('현재 값을 불러오지 못했습니다.');}}
    finally{if(current(n)){busy=false;controls();if(historyOpen)loadHistory(0);}}
  }
  const historyLabels={INVENTORY:'쿠팡 확인',WORKING_COPY_SAVE:'수정값 저장',EXECUTION_REQUEST:'전송 요청',EXECUTION_ACCEPTED:'요청 접수 · 반영 확인 중',EXECUTION_CONFIRMED:'조회 반영 확인 완료',EXECUTION_UNKNOWN:'전송 결과 미확인',EXECUTION_FAILED:'전송 실패'};
  async function loadHistory(page){
    const n=seq,order=++historyRead,section=$('quick-edit-history');section.innerHTML='<p role="status">이력 조회 중…</p>';
    try{
      const data=await apiGet(endpoint()+'/history?'+new URLSearchParams({kind:target.kind,page,size:20}));
      if(!current(n)||order!==historyRead||!historyOpen)return;
      const value=v=>v==null?'미확인':target.kind==='PRICE'?money(v):Number(v).toLocaleString('ko-KR')+'개';
      section.innerHTML=(data.items.length?data.items.map(row=>`<div class="quick-edit-history-row"><span>${e(AppUI.stamp(row.createdAt))} · ${e(historyLabels[row.kind]||row.kind)}</span><span>${e(value(row.before))} → <strong>${e(value(row.after))}</strong></span></div>`).join(''):'<p>수정 이력이 없습니다.</p>')+`<div class="quick-edit-history-pages"><button type="button" class="btn small" data-history-page="${data.page-1}" ${data.page<=0?'disabled':''}>이전</button><span>${data.totalPages?data.page+1:0} / ${data.totalPages}</span><button type="button" class="btn small" data-history-page="${data.page+1}" ${data.page+1>=data.totalPages?'disabled':''}>다음</button></div>`;
      section.querySelectorAll('[data-history-page]').forEach(button=>button.addEventListener('click',()=>loadHistory(Number(button.dataset.historyPage))));
    }catch(err){if(current(n)&&order===historyRead&&historyOpen){section.innerHTML=`<p class="form-error">${e(err?.message||'이력을 불러오지 못했습니다.')}</p><button type="button" class="btn small" id="quick-edit-history-retry">다시 조회</button>`;$('quick-edit-history-retry').addEventListener('click',()=>loadHistory(page));}}
  }
  function renderProblems(data){const conflicts=data.registry.conflicts.filter(c=>c.change.optionId===context.optionId&&c.change.path===`options.registration.${target.kind==='PRICE'?'salePrice':'maximumBuyCount'}`&&['CONFLICT','UNVERIFIED','BASELINE_MISSING'].includes(c.state));
    $('quick-edit-preview').innerHTML=data.preview.target.issues.map(i=>`<p class="form-error">${e(i.message)}</p>`).join('')+conflicts.map(c=>`<section><p>기준 ${e(JSON.stringify(c.baseline))} · 현재 쿠팡 ${e(JSON.stringify(c.current))} · 수정 ${e(JSON.stringify(c.desired))}</p><button type="button" class="btn small" data-quick-conflict="${e(c.key)}" data-choice="COUPANG">쿠팡값 사용</button><button type="button" class="btn small" data-quick-conflict="${e(c.key)}" data-choice="LOCAL">내 수정값 반영</button></section>`).join('');
    for(const button of $('quick-edit-preview').querySelectorAll('[data-quick-conflict]'))button.addEventListener('click',()=>run(async()=>{await apiPost('/api/coupang/products/'+target.product+'/conflicts/resolve',{observedVersion:data.registry.state.version,revision:data.registry.revision,key:button.dataset.quickConflict,choice:button.dataset.choice});if(button.dataset.choice==='COUPANG'){const chosen=conflicts.find(c=>c.key===button.dataset.quickConflict)?.current;if(chosen==null)throw Error('현재값을 다시 확인해 주세요.');$('quick-edit-value').value=String(chosen);valueEdited=true;}preview=null;$('quick-edit-preview').innerHTML='';await reload(true);}));
    return conflicts.length>0||data.preview.target.issues.length>0;
  }
  function renderUncertain(){showStatus('전송 결과를 확인하지 못했습니다. 결과 확인을 눌러 확인해 주세요.');$('quick-edit-execution').innerHTML='<button id="quick-edit-result" type="button" class="btn">결과 확인</button>';$('quick-edit-result').addEventListener('click',()=>run(recover));controls();}
  async function recover(){if(!receipt)return;const n=seq,attempt=receipt,attemptKey=receiptKey();try{const data=attempt.executionId?await apiGet('/api/coupang/submissions/'+encodeURIComponent(attempt.executionId)):await apiPost('/api/coupang/submissions/'+encodeURIComponent(attempt.previewId)+'/execute',{idempotencyKey:attempt.key});attempt.executionId=data.id;sessionStorage.setItem(attemptKey,JSON.stringify(attempt));if(!current(n))return;execution=data;await settled();}catch(err){if(!current(n))return;if(err.status>=400&&err.status<500&&![408,429].includes(err.status)&&!receipt.executionId){clearReceipt();$('quick-edit-execution').innerHTML='';await reload(true);throw err;}renderUncertain();throw err;}}
  async function run(fn){if(busy)return;const n=seq;busy=true;writing=true;controls();setError('quick-edit-error',null);try{await fn();}catch(err){if(current(n)){error(err);await reload(true);}}finally{busy=false;writing=false;if(dialog.open)controls();}}
  FormActions.bindExplicitSubmit($('quick-edit-form'),$('quick-edit-prepare'),()=>run(async()=>{
    if(!context||context.registry.blocked||receipt||execution)return;
    const n=seq,before=context.currentValue,observedVersion=context.registry.state.version,text=target.kind==='PRICE'?AppUI.normalizeCoupangPrice($('quick-edit-value').value):$('quick-edit-value').value,value=Number(text);
    if(!/^\d+$/.test(text))throw Error('0 이상의 정수 가격·수량을 입력해 주세요.');
    if(value===before){showStatus('현재 쿠팡값과 같아 변경할 내용이 없습니다.');return;}
    valueEdited=true;cache();
    const data=await apiPost(endpoint()+'/prepare',{kind:target.kind,value,sessionId:context.entry.sessionId,revision:context.entry.revision,observedVersion:context.registry.state.version,productId:target.internal,fingerprint:context.source.fingerprint,pricingRevision:context.settings.revision,purchaseCost:target.kind==='PRICE'&&$('quick-edit-cost').value!==''?Number($('quick-edit-cost').value):null});
    if(!current(n))return;
    context.entry=data.entry;context.registry=data.registry;context.pendingValue=value;cache();preview=data.preview;
    if(data.calculation)renderCalculation(data.calculation);
    if(renderProblems(data))return;
    const changes=preview.target.changes,change=changes[0],field=target.kind==='PRICE'?'salePrice':'amountInStock';
    const steps=preview.target.steps;
    if(data.registry.state.version!==observedVersion||!preview.executable||changes.length!==1||change.path!==field||Number(change.before)!==before||Number(change.after)!==value||!Array.isArray(steps)||steps.length!==1||steps[0].optionId!==context.optionId||steps[0].type!==target.kind){
      showStatus('현재값 또는 준비 결과가 변경되었습니다. 다시 확인한 뒤 저장해 주세요.');await reload(true);showStatus('현재값 또는 준비 결과가 변경되었습니다. 다시 확인한 뒤 저장해 주세요.');return;
    }
    key=AppUI.uuid();receipt={previewId:preview.id,key,value,executionId:null};
    try{rememberReceipt();}catch{receipt=null;throw Error('전송 복구 정보를 보관하지 못했습니다. 브라우저 저장소를 확인해 주세요.');}
    await recover();
  }));
  function renderExecution(){const x=execution;if(!x)return;preview=null;$('quick-edit-preview').innerHTML='';$('quick-edit-execution').innerHTML=`<h3>전송 결과</h3><p>${e(({QUEUED:'전송 대기',RUNNING:'전송 중',SUCCEEDED:'조회 반영 확인 완료',ACCEPTED:'요청 접수 · 반영 확인 중',UNKNOWN:'전송 결과 미확인 · 중복 전송 차단',PARTIAL:'일부 완료',FAILED:'전송 실패'})[x.status]||x.status)}</p>${x.target.steps.map(s=>`<p>${e(s.label)} · ${e(s.status)} ${e(s.message||'')}</p>`).join('')}<button id="quick-edit-result" type="button" class="btn">결과 확인</button>${['FAILED','PARTIAL'].includes(x.status)?'<button id="quick-edit-revise" type="button" class="btn">실패 입력 수정</button>':''}`;$('quick-edit-result').addEventListener('click',()=>run(async()=>{const n=seq,order=++executionRead;const result=await (['UNKNOWN','ACCEPTED','PARTIAL'].includes(x.status)?apiPost('/api/coupang/submissions/'+x.id+'/reconcile',{}):apiGet('/api/coupang/submissions/'+x.id));if(!current(n)||order!==executionRead)return;execution=result;await settled();}));$('quick-edit-revise')?.addEventListener('click',()=>run(async()=>{await apiPost('/api/coupang/submissions/'+x.id+'/revise',{});execution=null;clearReceipt();$('quick-edit-execution').innerHTML='';await reload(true);}));controls();}
  async function settled(){renderExecution();showStatus('');if(receipt){receipt.executionId=execution.id;rememberReceipt();}if(execution.status==='SUCCEEDED'){clearReceipt();try{sessionStorage.removeItem(storageKey);}catch{}await onApplied();}poll();}
  function poll(){clearTimeout(timer);if(!execution||!['QUEUED','RUNNING'].includes(execution.status)||!dialog.open)return;const n=seq,id=execution.id;timer=setTimeout(async()=>{const order=++executionRead;try{const data=await apiGet('/api/coupang/submissions/'+id);if(!current(n)||order!==executionRead)return;execution=data;await settled();}catch(err){if(current(n))error(err);}},1500);}
  $('quick-edit-stock-option').addEventListener('change',()=>{const value=$('quick-edit-stock-option').value;selectedStockId=value===''?null:stockOptions()[Number(value)].id;valueEdited=value==='';$('quick-edit-value').value=value===''?'':(stockOptions()[Number(value)].quantity??'');stockNote();invalidate();});
  $('quick-edit-value').addEventListener('input',()=>{valueEdited=true;stockNote();invalidate();});
  $('quick-edit-cost').addEventListener('input',()=>{costEdited=true;const cost=$('quick-edit-cost').value;if(cost!==''&&context)try{$('quick-edit-value').value=CoupangPricing.calculate(context.settings,Number(cost)).suggestedPrice;valueEdited=true;}catch{}invalidate();});
  $('quick-edit-history-toggle').addEventListener('click',()=>{historyOpen=!historyOpen;historyRead++;$('quick-edit-history').hidden=!historyOpen;$('quick-edit-history-toggle').setAttribute('aria-expanded',String(historyOpen));if(historyOpen)loadHistory(0);});
  $('quick-edit-reload').addEventListener('click',()=>{if(!busy){preview=null;$('quick-edit-preview').innerHTML='';setError('quick-edit-error',null);reload(true);}});
  $('quick-edit-close').addEventListener('click',()=>{if(!writing)dialog.close();});dialog.addEventListener('cancel',ev=>{if(writing)ev.preventDefault();});dialog.addEventListener('close',()=>{seq++;historyRead++;clearTimeout(timer);cache();busy=false;});
  return {async open(product,vendor,internal,kind){if(dialog.open)return;target={product,vendor,internal,kind};context=null;execution=null;preview=null;storageKey=null;cachedBasis=null;key=null;valueEdited=false;costEdited=false;receipt=null;selectedStockId=null;historyOpen=false;historyRead++;$('quick-edit-history').hidden=true;$('quick-edit-history').innerHTML='';$('quick-edit-history-toggle').setAttribute('aria-expanded','false');$('quick-edit-identity').innerHTML='';$('quick-edit-source').innerHTML='';$('quick-edit-calculation').innerHTML='';$('quick-edit-margin').innerHTML='';$('quick-edit-delta').textContent='';$('quick-edit-margin').hidden=true;$('quick-edit-stock').hidden=true;$('quick-edit-form').reset();$('quick-edit-preview').innerHTML='';$('quick-edit-execution').innerHTML='';setError('quick-edit-error',null);$('quick-edit-title').textContent=kind==='PRICE'?'가격수정':'재고수정';dialog.showModal();await reload();}};
}};
