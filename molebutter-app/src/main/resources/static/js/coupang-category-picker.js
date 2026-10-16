"use strict";
(() => {
  const e=AppUI.escape;
  let dialog, active=null, generation=0, query='', state=null, selection=null, changed=false;
  function close(){generation++;state?.read?.cancel();active=null;if(dialog?.open)dialog.close();}
  function ensure(){
    if(dialog)return;
    dialog=document.createElement('dialog');dialog.className='coupang-category-dialog coupang-category-dialog';
    dialog.setAttribute('aria-labelledby','coupang-category-title');
    dialog.innerHTML='<div class="coupang-subheading"><h2 id="coupang-category-title">쿠팡 카테고리 선택</h2><button type="button" class="btn" data-coupang-category-close>닫기</button></div><form><label class="field"><span>카테고리명 또는 코드</span><input type="search" maxlength="200" data-coupang-category-query required></label><button type="submit" class="btn primary">검색</button></form><div data-coupang-category-results-container></div><p class="form-error" data-coupang-category-error role="alert" hidden></p><div class="coupang-category-actions"><button type="button" class="btn" data-coupang-category-cancel>취소</button><button type="button" class="btn primary" data-coupang-category-apply>선택한 카테고리 적용</button></div>';
    document.body.append(dialog);
    dialog.querySelector('[data-coupang-category-close]').onclick=close;dialog.querySelector('[data-coupang-category-cancel]').onclick=close;dialog.addEventListener('cancel',close);
    dialog.querySelector('form').onsubmit=event=>{event.preventDefault();search();};
    FormActions.bindEnterSubmit(dialog.querySelector('form'),dialog.querySelector('button[type=submit]'));
    dialog.querySelector('[data-coupang-category-query]').oninput=updateApply;
    dialog.querySelector('[data-coupang-category-results-container]').onclick=event=>{
      const button=event.target.closest('[data-category-choice]');if(!button||!active||state.read||state.locked)return;
      if(button.dataset.categoryIndex!==undefined){const value=state.items[Number(button.dataset.categoryIndex)];if(value){selection=value;changed=true;render();}}
      else if(button.dataset.categoryClear!==undefined){selection=null;changed=true;render();}
      else if(button.dataset.categoryRetry!==undefined)load();
    };
    dialog.querySelector('[data-coupang-category-apply]').onclick=()=>{
      if(!active||state.read||!query||dialog.querySelector('[data-coupang-category-query]').value.trim()!==query)return;
      try{active.onApply({name:query,selection:changed?structuredClone(selection):undefined});close();}
      catch(error){const host=dialog.querySelector('[data-coupang-category-error]');host.textContent=error.message;host.hidden=false;}
    };
  }
  function updateApply(){dialog.querySelector('[data-coupang-category-apply]').disabled=!!state.read||!query||dialog.querySelector('[data-coupang-category-query]').value.trim()!==query;}
  function render(){
    const s=state,message=s.locked?'기존 쿠팡 상품의 카테고리는 변경할 수 없습니다.':s.read?'검색 중…':s.error||s.message;
    dialog.querySelector('[data-coupang-category-results-container]').innerHTML=`<section class="coupang-category-results-panel"><div class="coupang-category-results-heading"><h3>쿠팡 카테고리</h3>${selection?`<button type="button" class="btn small" data-category-choice data-category-clear ${s.locked||s.read?'disabled':''}>선택 해제</button>`:''}</div><p class="coupang-category-selected">${selection?e(selection.name)+' · '+e(selection.id):'선택 없음'}</p><p role="status" class="${s.error?'form-error':'product-meta'}">${e(message)}</p>${!s.locked?s.items.map((row,index)=>`<button type="button" class="coupang-category-result" data-category-choice data-category-index="${index}" aria-pressed="${selection?.id===row.id}" ${s.read?'disabled':''}><strong>${e(row.name)}</strong><span>${e(row.id)}</span></button>`).join(''):''}${s.error?`<button type="button" class="btn small" data-category-choice data-category-retry>다시 조회</button>`:''}</section>`;updateApply();
  }
  async function load(){
    const s=state;if(!active||s.locked||!query)return;
    s.read?.cancel();const token=generation,readQuery=query;
    const read=s.cached?{cancel(){},promise:Promise.resolve(s.cached)}:window.CoupangRead.start('/api/coupang/categories');
    s.read=read;s.error='';s.message='';render();
    try{
      const data=await read.promise;if(!active||token!==generation||s.read!==read)return;
      if(!Array.isArray(data))throw Error('카테고리 검색 결과를 확인해 주세요.');s.cached=data;
      const terms=readQuery.toLocaleLowerCase().split(/\s+/).filter(Boolean),values=data.map(row=>({id:row.id,name:row.wholeCategoryName||row.name})).filter(row=>terms.every(term=>(row.name+' '+row.id).toLocaleLowerCase().includes(term))).slice(0,200);
      if(values.some(row=>typeof row.id!=='string'||!row.id.trim()||typeof row.name!=='string'||!row.name.trim()))throw Error('카테고리 검색 결과를 확인해 주세요.');
      s.items=values;s.message=values.length?`${values.length}개 검색됨`:'검색 결과가 없습니다.';
    }catch(error){if(active&&token===generation&&s.read===read)s.error=error.message||'카테고리 검색에 실패했습니다.';}
    finally{if(active&&token===generation&&s.read===read){s.read=null;render();}}
  }
  function search(){const name=dialog.querySelector('[data-coupang-category-query]').value.trim();if(!name||name.length>200)return;generation++;state.read?.cancel();query=name;state.read=null;state.items=[];state.error='';dialog.querySelector('[data-coupang-category-error]').hidden=true;load();render();}
  window.CoupangCategoryPicker={open(options){close();ensure();active=options;selection=structuredClone(options.selection??null);changed=false;query='';state={locked:!!options.locked,read:null,items:[],error:'',message:''};dialog.querySelector('[data-coupang-category-query]').value=options.name||'';dialog.querySelector('[data-coupang-category-error]').hidden=true;render();dialog.showModal();dialog.querySelector('[data-coupang-category-query]').focus();if(options.name?.trim())search();},close};
  window.addEventListener('pagehide',close);
})();
