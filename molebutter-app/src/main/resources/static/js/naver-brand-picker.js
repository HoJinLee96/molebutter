(() => {
    'use strict';
    const {escape:e}=AppUI;
    let dialog=null,active=null,read=null,sequence=0,items=[];
    function close(){sequence++;read?.cancel();read=null;active=null;items=[];if(dialog?.open)dialog.close();}
    function ensure(){
        if(dialog)return;
        dialog=document.createElement('dialog');dialog.className='coupang-brand-dialog';
        dialog.setAttribute('aria-labelledby','naver-brand-picker-title');
        dialog.innerHTML='<div class="coupang-brand-heading"><h2 id="naver-brand-picker-title">스마트스토어 브랜드 검색</h2><button type="button" class="btn" data-naver-brand-close>닫기</button></div><form data-naver-brand-form><label class="field"><span>브랜드명</span><input type="search" maxlength="200" data-naver-brand-query required></label><button class="btn primary" type="submit">검색</button></form><p role="status" data-naver-brand-status></p><div data-naver-brand-results></div>';
        document.body.append(dialog);
        dialog.querySelector('[data-naver-brand-close]').onclick=close;
        dialog.addEventListener('cancel',close);
        dialog.querySelector('form').onsubmit=event=>{event.preventDefault();search();};
        FormActions.bindEnterSubmit(dialog.querySelector('form'),dialog.querySelector('button[type=submit]'));
        dialog.querySelector('[data-naver-brand-results]').onclick=event=>{const button=event.target.closest('[data-naver-brand-index]');if(!button||read||!active)return;const brand=items[Number(button.dataset.naverBrandIndex)];if(brand){const select=active.onSelect;close();select(brand);}};
    }
    async function search(){
        if(read||!active)return;
        const name=dialog.querySelector('[data-naver-brand-query]').value.trim();if(!name)return;
        const token=++sequence,submit=dialog.querySelector('button[type=submit]'),status=dialog.querySelector('[data-naver-brand-status]'),results=dialog.querySelector('[data-naver-brand-results]');
        items=[];results.innerHTML='';status.textContent='검색 중…';submit.disabled=true;
        const controller=new AbortController();read={cancel:()=>controller.abort(),promise:apiGet('/api/marketplaces/naver/metadata/brands?name='+encodeURIComponent(name),{signal:controller.signal})};
        try{
            const data=await read.promise;if(token!==sequence)return;
            if(!Array.isArray(data)||data.some(b=>typeof b.id!=='string'||!/^\d+$/.test(b.id)||typeof b.name!=='string'||!b.name.trim()))throw Error('브랜드 검색 결과를 확인해 주세요.');
            items=data;results.innerHTML=data.map((b,n)=>`<button type="button" class="coupang-brand-result" data-naver-brand-index="${n}"><strong>${e(b.name)}</strong><span>${e(b.id)}</span><span>선택</span></button>`).join('');status.textContent=data.length?'':'검색 결과가 없습니다.';
        }catch(error){if(token===sequence)status.textContent=error.message;}
        finally{if(token===sequence){read=null;submit.disabled=false;}}
    }
    window.NaverBrandPicker={open(options){close();ensure();active=options;dialog.querySelector('[data-naver-brand-query]').value=options.name||'';dialog.querySelector('[data-naver-brand-results]').innerHTML='';dialog.querySelector('[data-naver-brand-status]').textContent='';dialog.querySelector('button[type=submit]').disabled=false;dialog.showModal();dialog.querySelector('[data-naver-brand-query]').focus();},close};
    window.addEventListener('pagehide',close);
})();
