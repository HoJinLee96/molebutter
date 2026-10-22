"use strict";
(() => {
  const {$,escape:e}=AppUI,dialog=$('coupang-working-copy-dialog');
  if(!dialog)return;
  let page=0,query='',data=null,busy=false,sequence=0;
  function buttons(){
    $('coupang-working-copies-query').querySelectorAll('input,button').forEach(el=>el.disabled=busy);
    $('coupang-working-copies-prev').disabled=busy||page===0;
    $('coupang-working-copies-next').disabled=busy||!data||page+1>=data.totalPages;
  }
  async function load(target=0){
    if(busy)return;const seq=++sequence;busy=true;buttons();$('coupang-working-copies-error').hidden=true;
    $('coupang-working-copies-summary').textContent='임시저장 조회 중…';
    try{
      const params=new URLSearchParams({mode:'CREATE',query,page:String(target),size:'20'});
      const response=await apiGet('/api/coupang/working-copies?'+params);if(seq!==sequence)return;
      if(!Array.isArray(response.items)||!Number.isInteger(response.totalPages)||response.items.some(row=>row.mode!=='CREATE'))throw Error('신규 등록 작업본 응답을 확인해 주세요.');
      data=response;page=target;
      $('coupang-working-copies-rows').innerHTML=data.items.map(row=>`<tr><td class="wrap">${e(row.name||'이름 없는 상품')}</td><td>${row.blocked?'전송 결과 확인 필요':'임시 저장'}</td><td>${e(row.updatedAt?.replace('T',' ').slice(0,19)||'—')}</td><td><a class="btn small" href="/coupang/working-copies/${encodeURIComponent(row.id)}/edit">불러오기</a></td></tr>`).join('')||'<tr><td colspan="4" class="empty">신규 등록 임시저장이 없습니다.</td></tr>';
      $('coupang-working-copies-summary').textContent=`${data.totalElements||0}개 임시저장`;
      $('coupang-working-copies-page').textContent=data.totalPages?`${page+1} / ${data.totalPages}`:'';
    }catch(error){if(seq===sequence){$('coupang-working-copies-error').textContent=error.message;$('coupang-working-copies-error').hidden=false;$('coupang-working-copies-summary').textContent='';}}
    finally{if(seq===sequence){busy=false;buttons();}}
  }
  $('editor-load-working-copy').addEventListener('click',()=>{dialog.showModal();load(page);});
  $('coupang-working-copy-close').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>{sequence++;busy=false;buttons();$('editor-load-working-copy').focus();});
  FormActions.bindEnterSubmit($('coupang-working-copies-query'),$('coupang-working-copies-query').querySelector('button[type=submit]'));
  $('coupang-working-copies-query').addEventListener('submit',event=>{event.preventDefault();if(busy)return;query=$('coupang-working-copies-search').value.trim();load(0);});
  $('coupang-working-copies-reset').addEventListener('click',()=>{if(busy)return;query='';$('coupang-working-copies-search').value='';load(0);});
  $('coupang-working-copies-prev').addEventListener('click',()=>load(page-1));
  $('coupang-working-copies-next').addEventListener('click',()=>load(page+1));
  window.addEventListener('pagehide',()=>sequence++);
})();
