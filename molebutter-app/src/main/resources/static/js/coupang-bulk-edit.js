"use strict";
(() => {
  const price=typeof module!=='undefined'?require('./app-ui.js').normalizeCoupangPrice:AppUI.normalizeCoupangPrice;
  const path=r=>`/api/coupang/products/${encodeURIComponent(r.product)}/options/${encodeURIComponent(r.vendor)}/quick-edit`;
  const snapshot=(c,kind)=>JSON.stringify([c.entry.accountKey,c.entry.revision,c.entry.appliedRevision,c.optionId,c.currentValue,c.registry.state.version,c.source.fingerprint,kind==='PRICE'?c.settings.revision:null]);
  const stableSnapshot=(c,kind)=>JSON.stringify([c.entry.accountKey,c.optionId,c.currentValue,c.source.fingerprint,kind==='PRICE'?c.settings.revision:null]);
  const rowIdentity=r=>JSON.stringify([r.product,r.vendor]);
  function savedRows(value){
    if(value==null)return [];
    const saved=JSON.parse(value);
    if(saved?.version!==1||!Array.isArray(saved.rows)||saved.rows.some(r=>!r||typeof r.product!=='string'||typeof r.vendor!=='string')||new Set(saved.rows.map(rowIdentity)).size!==saved.rows.length)throw Error('이전 작업 형식을 확인할 수 없습니다.');
    return saved.rows;
  }
  function mergeRows(previous,current){
    const selected=new Set(current.map(rowIdentity));
    return [...previous.filter(r=>!selected.has(rowIdentity(r))),...current];
  }
  function createQueue({kind,rows,get,post,readContexts,preparePath,save,uuid,changed=()=>{},applied=async()=>{},recoveryOnly=false,sleep=ms=>new Promise(r=>setTimeout(r,ms))}) {
    let running=false,stopping=false;const ownRevisions=new Map();
    const persist=()=>save(rows.map(({context,...r})=>r));
    const notify=()=>{persist();changed();};
    const open=r=>get(path(r)+'?'+new URLSearchParams({kind,internalProductId:r.internal}));
    function adopt(r,c){r.context=c;r.before=c.currentValue;r.basis=snapshot(c,kind);r.stableBasis=stableSnapshot(c,kind);r.account=c.entry.accountKey;if(c.registry.state.product?.productId)r.exposure=c.registry.state.product.productId;}
    const read=async list=>readContexts?readContexts(list):new Map(await Promise.all(list.map(async r=>[r,await open(r)])));
    function loaded(r,c){if(r.receipt&&r.account!==c.entry.accountKey)throw Error('계정이 달라 이전 전송을 복구할 수 없습니다.');adopt(r,c);if(r.value==null){const s=c.source;if(c.pendingValue!=null)r.value=String(c.pendingValue);else if(kind==='PRICE')r.value=String(c.calculation?.suggestedPrice??c.currentValue??'');else if(Object.hasOwn(s,'stockProposal'))r.value=s.stockProposal?.selectionRequired?'':s.stockProposal?.quantity!=null?String(s.stockProposal.quantity):'';else if(s.availability==='UNAVAILABLE'&&s.ownedQuantity!=null)r.value=s.ownedQuantity===0?'0':s.ownedOptions.length===1&&s.couponOptionCount===1?String(s.ownedQuantity):'';else r.value=(s.stockOptionCount??s.stocks.length)===1?String((s.stockOptions??s.stocks)[0]?.quantity??''):'';}
      r.status=r.receipt?r.receipt.status||'UNKNOWN':c.registry.blocked?'BLOCKED':r.status==='SUCCEEDED'?'SUCCEEDED':'READY';r.message=c.registry.blocked?'기존 전송 결과 확인 필요':r.value===''?'변경값 입력 필요':'';if(c.registry.blocked||r.status==='SUCCEEDED')r.included=false;else if(r.included==null)r.included=r.status==='READY'&&r.value!=='';}
    async function loadGroup(list,active=()=>true){try{const contexts=await read(list);if(!active())return;for(const r of list)try{loaded(r,contexts.get(r));}catch(err){r.status='ERROR';r.message=err.message;r.included=false;}}
      catch(err){if(!active())return;for(const r of list){r.status='ERROR';r.message=err.message;r.included=false;}}notify();}
    const load=(r,active)=>loadGroup([r],active);
    async function settle(r,x){if(!x?.id||!['QUEUED','RUNNING','SUCCEEDED','ACCEPTED','UNKNOWN','PARTIAL','FAILED'].includes(x.status))throw Error('전송 결과 응답을 확인하지 못했습니다.');r.receipt.executionId=x.id;r.receipt.status=x.status;r.status=x.status;notify();
      while(['QUEUED','RUNNING'].includes(x.status)&&!stopping){await sleep(1200);x=await get('/api/coupang/submissions/'+encodeURIComponent(x.id));r.receipt.status=x.status;r.status=x.status;notify();}
      if(x.status==='SUCCEEDED'){if(r.receipt.revision!=null)ownRevisions.set(r.product,r.receipt.revision);r.receipt=null;r.status='SUCCEEDED';r.included=false;r.message='조회 반영 확인 완료';notify();try{await applied({product:r.product,vendor:r.vendor,status:r.status});}catch(err){r.message='조회 반영 확인 완료 · 목록 갱신 실패: '+err.message;notify();}return true;}
      r.message=x.target?.steps?.find(step=>step.status==='FAILED'&&step.message)?.message||({UNKNOWN:'전송 결과 미확인 · 추가 전송 중단',ACCEPTED:'요청 접수 · 반영 확인 중',FAILED:'전송 실패 · 입력 수정 필요',PARTIAL:'일부 완료 · 결과 확인 필요'})[x.status]||'처리 중 · 결과 확인 필요';notify();return false;}
    async function recover(r){const p=r.receipt;if(r.account!==r.context.entry.accountKey)throw Error('계정이 변경되었습니다.');const x=p.executionId?await (['UNKNOWN','ACCEPTED','PARTIAL'].includes(p.status)?post('/api/coupang/submissions/'+encodeURIComponent(p.executionId)+'/reconcile',{}):get('/api/coupang/submissions/'+encodeURIComponent(p.executionId))):await post('/api/coupang/submissions/'+encodeURIComponent(p.previewId)+'/execute',{idempotencyKey:p.key});return settle(r,x);}
    async function run(){if(running)return;running=true;stopping=false;const refreshed=new Map();changed();try{
      for(const r of rows){if(stopping)break;if(!r.receipt&&(!r.included||recoveryOnly))continue;
        try{
          if(r.receipt){if(!await recover(r))break;refreshed.delete(r.product);continue;}
          const text=kind==='PRICE'?price(r.value):String(r.value??'');const value=Number(text);if(!/^\d+$/.test(text)||!Number.isSafeInteger(value)||value<0||(kind==='STOCK'?value>99999:value===0||value%10!==0))throw Error(kind==='PRICE'?'0보다 큰 10원 단위 가격을 입력해 주세요.':'0~99999 정수 수량을 입력해 주세요.');
          if(!refreshed.has(r.product))refreshed.set(r.product,await read(rows.filter(item=>item.product===r.product&&item.included&&!item.receipt)));
          const c=refreshed.get(r.product).get(r);if(!c||c.currentValue==null)throw Error('현재 판매값이 미확인입니다. 해당 상품을 갱신해 주세요.');
          const ownRevision=ownRevisions.get(r.product),ownChange=readContexts&&ownRevision!=null&&c.entry.revision===ownRevision&&c.entry.appliedRevision===ownRevision&&r.stableBasis===stableSnapshot(c,kind);
          if((r.basis!==snapshot(c,kind)&&!ownChange)||c.registry.blocked){adopt(r,c);r.status='REVIEW';r.message='현재값·작업본·근거가 변경되었습니다. 입력을 확인하고 저장을 다시 눌러 주세요.';notify();break;}
          adopt(r,c);if(value===c.currentValue){r.status='UNCHANGED';r.message='현재값과 같음';r.included=false;notify();await applied({product:r.product,vendor:r.vendor,status:r.status});continue;}
          r.status='PREPARING';r.message='수정값 저장 중';notify();
          const d=await post(preparePath?preparePath(r):path(r)+'/prepare',{kind,value,sessionId:c.entry.sessionId,revision:c.entry.revision,observedVersion:c.registry.state.version,productId:r.internal,fingerprint:c.source.fingerprint,pricingRevision:c.settings.revision,purchaseCost:kind==='PRICE'?c.source.purchaseCost:null});
          const p=d.preview,t=p.target,change=t.changes[0],step=t.steps?.[0];
          if(!p.id||!p.executable||d.registry.state.version!==c.registry.state.version||t.issues.length||d.registry.conflicts.some(v=>v.change?.optionId===c.optionId&&['CONFLICT','UNVERIFIED','BASELINE_MISSING'].includes(v.state))||t.changes.length!==1||change.before==null||change.after==null||change.path!==(kind==='PRICE'?'salePrice':'amountInStock')||Number(change.before)!==c.currentValue||Number(change.after)!==value||t.steps?.length!==1||step.optionId!==c.optionId||step.type!==kind){r.status='REVIEW';r.message='준비 결과가 변경되었거나 충돌이 있습니다. 해당 옵션 수정에서 확인해 주세요.';notify();break;}
          r.receipt={previewId:p.id,key:uuid(),value,executionId:null,status:'UNKNOWN',revision:d.entry?.revision};try{notify();}catch(err){r.receipt=null;throw Error('전송 복구 정보를 저장하지 못해 전송을 중단했습니다.');}
          if(!await recover(r))break;refreshed.delete(r.product);
        }catch(err){if(r.receipt&&!r.receipt.executionId&&err.status>=400&&err.status<500&&![408,429].includes(err.status))r.receipt=null;r.message=err.message;r.status=r.receipt?'UNKNOWN':'ERROR';try{notify();}catch{}break;}
      }
    }finally{running=false;changed();}}
    async function revise(r){if(running||!r.receipt?.executionId)return;running=true;changed();try{await post('/api/coupang/submissions/'+encodeURIComponent(r.receipt.executionId)+'/revise',{});r.receipt=null;await load(r);}finally{running=false;changed();}}
    return {load,loadGroup,run,revise,stop(){stopping=true;},get running(){return running;},persist};
  }
  function create({onApplied=async()=>{}}={}){
    const {$,escape:e}=AppUI,dialog=$('coupang-bulk-edit');let rows=[],kind='PRICE',queue=null,loading=false,loadingPaused=false,order=0,selection=null,storageKey=null,recoveryOnly=false,pendingRecovery=null;
    const money=v=>v==null?'미확인':Number(v).toLocaleString('ko-KR')+(kind==='PRICE'?'원':'개');
    function render(){const busy=loading||queue?.running;$('bulk-edit-close').disabled=!!queue?.running;$('bulk-edit-stop').hidden=!busy;$('bulk-edit-stop').textContent=queue?.running?'현재 항목 후 중단':'조회 중단';$('bulk-edit-save').disabled=busy||!rows.some(r=>r.receipt||!recoveryOnly&&r.included&&r.context&&!r.context.registry.blocked);$('bulk-edit-save').textContent=recoveryOnly?'이전 결과 확인':rows.some(r=>r.receipt)?'결과 확인·계속':'저장';
      $('bulk-edit-recover').disabled=!!busy;
      $('bulk-edit-rows').innerHTML=rows.map((r,i)=>`<tr><td><input type="checkbox" data-bulk-include="${i}" ${r.included?'checked':''} ${recoveryOnly||busy||!r.context||r.context.registry.blocked||r.receipt||r.status==='SUCCEEDED'?'disabled':''} aria-label="${e(r.code)} 선택"></td><td><strong>${e(r.code)}</strong><small>옵션 ${AppUI.coupangOptionLink(r.exposure,r.vendor)}</small></td><td>${e(money(r.before))}</td><td><input type="${kind==='PRICE'?'text':'number'}" inputmode="numeric" data-bulk-value="${i}" aria-label="${e(r.code)} 변경 ${kind==='PRICE'?'가격':'수량'}" value="${e(r.value??'')}" min="${kind==='PRICE'?10:0}" max="${kind==='PRICE'?9007199254740991:99999}" step="${kind==='PRICE'?10:1}" ${recoveryOnly||busy||!r.context||!r.included||r.receipt||r.status==='SUCCEEDED'?'disabled':''}></td><td>${e(({LOADING:'조회 중',READY:'수정 대기',SUCCEEDED:'완료',UNCHANGED:'변경 없음',UNKNOWN:'결과 미확인',ACCEPTED:'반영 확인 중',QUEUED:'전송 대기',RUNNING:'전송 중',PREPARING:'저장 중',REVIEW:'재확인 필요',ERROR:'오류',BLOCKED:'전송 확인 필요',FAILED:'실패',PARTIAL:'일부 완료'})[r.status]||r.status)}<small>${e(r.message||'')}</small>${r.context?.source.availabilityReason==='SELECTED_LISTING_MISSING'?`<small title="${e(r.context.source.availabilityCheckedAt||'확인 시각 미기록')}">선정 판매글 미발견</small>`:''}${r.context?.source.previousAvailability?'<small>이전 매입 상태</small>':''}${!recoveryOnly&&r.receipt&&['FAILED','PARTIAL'].includes(r.status)?`<button type="button" class="btn small" data-bulk-revise="${i}" ${busy?'disabled':''}>실패 입력 수정</button>`:''}</td></tr>`).join('');selection?.sync();for(const b of $('bulk-edit-rows').querySelectorAll('[data-bulk-revise]'))FormActions.bindExplicitSubmit($('bulk-edit-form'),b,async()=>{if(queue.running)return;try{await queue.revise(rows[Number(b.dataset.bulkRevise)]);}catch(err){$('bulk-edit-error').textContent=err.message;}render();});}
    $('bulk-edit-rows').addEventListener('input',ev=>{const i=ev.target.dataset.bulkValue;if(i==null)return;rows[i].value=ev.target.value;rows[i].included=true;queue.persist();$('bulk-edit-save').disabled=false;});
    $('bulk-edit-rows').addEventListener('change',ev=>{const i=ev.target.dataset.bulkInclude;if(i==null)return;rows[i].included=ev.target.checked;queue.persist();render();});

    selection=window.TableSelection.create({controls:[$('bulk-edit-select-all'),$('bulk-edit-select-all-mobile')],checkboxes:()=>[...$('bulk-edit-rows').querySelectorAll('[data-bulk-include]')],isBusy:()=>loading||!!queue?.running,onToggle:(checked,nodes)=>{for(const node of nodes)rows[Number(node.dataset.bulkInclude)].included=checked;queue.persist();render();}});
    FormActions.bindExplicitSubmit($('bulk-edit-form'),$('bulk-edit-save'),async()=>{if(!queue||loading)return;$('bulk-edit-error').textContent='';await queue.run();render();});
    FormActions.bindExplicitSubmit($('bulk-edit-form'),$('bulk-edit-recover'),async()=>{if(!pendingRecovery||loading||queue?.running)return;const previous=pendingRecovery;pendingRecovery=null;await controller.open([],previous.kind,previous.rows);});
    $('bulk-edit-stop').addEventListener('click',()=>{if(loading){loadingPaused=true;loading=false;}queue?.stop();render();});$('bulk-edit-close').addEventListener('click',()=>{if(!queue?.running)dialog.close();});dialog.addEventListener('cancel',ev=>{if(queue?.running)ev.preventDefault();});dialog.addEventListener('close',()=>{order++;loading=false;});
    const controller={async open(items,nextKind,recoveryRows=null){if(dialog.open&&!recoveryRows)return;kind=nextKind;recoveryOnly=!!recoveryRows;pendingRecovery=null;$('bulk-edit-recover').hidden=true;$('bulk-edit-recovery-note').hidden=true;$('bulk-edit-recovery-note').textContent='';storageKey='coupang.bulk-edit.'+kind;const n=++order;rows=items.map(r=>({product:r.product.sellerProductId,vendor:r.option.vendorItemId,exposure:r.product.productId,internal:r.recommendation?.internalProductId,before:nextKind==='PRICE'?r.option.current?.salePrice:r.option.current?.amountInStock,code:r.option.settings?.find(f=>f.name==='externalVendorSku')?.value?.trim()||'',status:'LOADING'}));
      if(recoveryRows)rows=recoveryRows;else try{const saved=savedRows(sessionStorage.getItem(storageKey));if(saved.length){
        const requested=new Set(rows.map(rowIdentity)),unresolved=saved.filter(r=>r.receipt&&!requested.has(rowIdentity(r)));
        if(unresolved.length){
          pendingRecovery={kind,rows:unresolved};$('bulk-edit-recovery-note').textContent='현재 선택에 없는 이전 전송은 별도로 보관했습니다. 현재 선택한 상품만 변경합니다. 이전 대상: '+unresolved.map(r=>`${r.code} · 옵션 ${r.vendor}`).join(', ');$('bulk-edit-recovery-note').hidden=false;$('bulk-edit-recover').hidden=false;
        }
        // Restore only explicitly selected identities; never replace a new selection with an old queue.
        rows=rows.map(row=>{const prior=saved.find(r=>rowIdentity(r)===rowIdentity(row));return prior&&(prior.receipt||prior.status!=='SUCCEEDED'&&prior.status!=='UNCHANGED')?prior:row;});
      }}catch{rows=[];queue=null;loading=false;$('bulk-edit-title').textContent='이전 일괄 작업 확인 필요';$('bulk-edit-error').textContent='저장된 이전 작업을 안전하게 읽지 못했습니다. 복구 자료를 보존하고 새 전송을 중단했습니다.';dialog.showModal();render();return;}
      const key=storageKey;queue=createQueue({kind,rows,recoveryOnly,get:apiGet,post:apiPost,preparePath:r=>`/api/coupang/products/${encodeURIComponent(r.product)}/bulk-edit/options/${encodeURIComponent(r.vendor)}/prepare`,readContexts:async list=>{
        const grouped=new Map(),result=new Map();for(const r of list){if(!grouped.has(r.product))grouped.set(r.product,[]);grouped.get(r.product).push(r);}
        for(const [product,group]of grouped){const reply=await apiPost(`/api/coupang/products/${encodeURIComponent(product)}/bulk-edit/context`,{kind,sessionId:group[0].context?.entry.sessionId??null,options:group.map(r=>({vendorItemId:r.vendor,internalProductId:r.internal}))});
          const contexts=reply?.options?.map(c=>({...c,entry:reply.entry,registry:reply.registry,settings:reply.settings}));
          if(!Array.isArray(contexts)||contexts.length!==group.length)throw Error('저장 문맥 응답을 확인하지 못했습니다.');group.forEach((r,i)=>{if(contexts[i].currentInventory?.vendorItemId&&String(contexts[i].currentInventory.vendorItemId)!==String(r.vendor))throw Error('옵션 문맥이 변경되었습니다.');result.set(r,contexts[i]);});}
        return result;
      },uuid:AppUI.uuid,save:data=>{if(n===order)sessionStorage.setItem(key,JSON.stringify({version:1,rows:mergeRows(savedRows(sessionStorage.getItem(key)),data)}));},changed:()=>{if(n===order&&dialog.open)render();},applied:onApplied});loading=true;loadingPaused=false;$('bulk-edit-title').textContent=recoveryOnly?'이전 전송 결과 확인':kind==='PRICE'?'선택 상품 가격 변경':'선택 상품 재고 변경';$('bulk-edit-error').textContent=recoveryOnly?'아래 이전 전송의 결과만 확인합니다. 새 가격·재고 전송은 준비하지 않습니다.':'';if(!dialog.open)dialog.showModal();render();const codes=new Map(),grouped=new Map();
      for(const r of rows){if(n!==order||!dialog.open||loadingPaused)break;try{if(!r.internal){const code=r.code.trim().toUpperCase();if(!codes.has(code))codes.set(code,await apiGet('/api/products/by-code?'+new URLSearchParams({code,page:'0',size:'20'})));const found=codes.get(code);if(found.totalElements!==1||found.items[0].duplicateCount)throw Error('정확히 일치하는 내부 상품이 하나여야 합니다.');r.internal=found.items[0].id;}if(n!==order||!dialog.open||loadingPaused)break;if(!grouped.has(r.product))grouped.set(r.product,[]);grouped.get(r.product).push(r);}catch(err){r.status='ERROR';r.message=err.message;r.included=false;if(n===order&&dialog.open)render();}}
      const pending=[...grouped.values()],activeQueue=queue;
      await Promise.all(Array.from({length:Math.min(4,pending.length)},async()=>{
        while(pending.length&&n===order&&dialog.open&&!loadingPaused){const group=pending.shift();await activeQueue.loadGroup(group,()=>n===order&&dialog.open&&!loadingPaused);}
      }));
      if(n===order){loading=false;render();}
    }};return controller;
  }
  if(typeof window!=='undefined')window.CoupangBulkEdit={create};
  if(typeof module!=='undefined')module.exports={createQueue,savedRows,mergeRows};
})();
