/* R2 lookup is read-only. Applying returns verified URLs without changing any product code. */
(() => {
    'use strict';
    const e=AppUI.escape,base='/api/coupang/r2-images';
    let dialog,active=null,generation=0,controller=null,items=[],selected=new Map(),cursor=null,code='',folder='',folders=[],working=false;
    const q=selector=>dialog.querySelector(selector);
    async function resolve(url,signal){return apiGet(base+'/resolve?'+new URLSearchParams({url}),{signal});}
    function close(value=null){if(!active)return;generation++;controller?.abort();const finish=active;active=null;working=false;if(dialog.open)dialog.close();finish(value);}
    function ensure(){
        if(dialog)return;dialog=document.createElement('dialog');dialog.className='r2-image-dialog';dialog.setAttribute('aria-labelledby','r2-image-title');
        dialog.innerHTML='<div class="r2-image-heading"><h2 id="r2-image-title">R2 이미지 선택</h2><button type="button" class="btn" data-close>닫기</button></div><div class="r2-image-query"><label class="field"><span>조회 상품코드</span><input data-code maxlength="40" autocomplete="off"></label><button type="button" class="btn primary" data-query>조회</button></div><p class="product-meta">조회 코드는 상품코드·판매자상품코드를 변경하지 않습니다.</p><div class="r2-image-folders" role="group" aria-label="이미지 폴더"></div><p data-status role="status"></p><p data-error class="form-error" role="alert" hidden></p><div class="r2-image-list"></div><button type="button" class="btn" data-more hidden>더 보기</button><div class="r2-image-actions"><span data-count></span><button type="button" class="btn" data-cancel>취소</button><button type="button" class="btn primary" data-apply>선택한 이미지 추가</button></div>';
        document.body.append(dialog);q('[data-close]').onclick=()=>{if(!working)close();};q('[data-cancel]').onclick=()=>{if(!working)close();};dialog.addEventListener('cancel',event=>{event.preventDefault();if(!working)close();});
        q('[data-query]').onclick=()=>load(false);q('[data-more]').onclick=()=>load(true);FormActions.bindEnter(q('[data-code]'),q('[data-query]'));
        q('[data-code]').oninput=()=>{generation++;controller?.abort();controller=null;working=false;render();};
        q('.r2-image-folders').onclick=event=>{const button=event.target.closest('[data-folder]');if(button&&!working){folder=button.dataset.folder;render();}};
        q('.r2-image-list').onchange=event=>{const input=event.target.closest('[data-key]');if(!input||working)return;const item=items.find(i=>i.key===input.dataset.key);if(item){input.checked?selected.set(item.key,item):selected.delete(item.key);render();}};
        q('[data-apply]').onclick=async()=>{
            if(working||!selected.size||q('[data-code]').value.trim().toUpperCase()!==code)return;
            working=true;const token=++generation;controller=new AbortController();error('');render();
            try{const verified=[];for(const item of selected.values())verified.push(await resolve(item.url,controller.signal));if(active&&token===generation)close(verified);}
            catch(err){if(active&&token===generation){working=false;error(err.message);render();}}
        };
    }
    function error(message){q('[data-error]').textContent=message||'';q('[data-error]').hidden=!message;}
    function render(){
        const parts=folder?folder.split('/'):[];
        const parent=parts.slice(0,-1).join('/');
        const prefixPath=folder?folder+'/':'';
        const children=[...new Set(folders.filter(path=>path.startsWith(prefixPath)&&path!==folder).map(path=>prefixPath+path.slice(prefixPath.length).split('/')[0]))].sort();
        const button=(path,label)=>`<button type="button" class="btn small" data-folder="${e(path)}" ${working?'disabled':''}>${e(label)}</button>`;
        q('.r2-image-folders').innerHTML=code?`${folder?`<nav class="r2-image-breadcrumbs" aria-label="현재 폴더">${button(parent,'상위 폴더')}<span>📁 ${e(folder)}</span></nav>`:''}<div class="r2-image-subfolders">${children.map(path=>button(path,'📁 '+path.slice(prefixPath.length))).join('')}</div>`:'';
        const prefix=folder?folder+'/':'';
        const visible=items.filter(i=>{const relative=i.key.slice(('products/'+code+'/').length);return relative.startsWith(prefix)&&!relative.slice(prefix.length).includes('/');});
        q('.r2-image-list').innerHTML=visible.map(i=>`<label class="r2-image-card"><input type="checkbox" data-key="${e(i.key)}" ${selected.has(i.key)?'checked':''} ${working?'disabled':''}><img src="${e(i.url)}" alt="${e(i.key.split('/').pop())}" loading="lazy" referrerpolicy="no-referrer"><span>${e(i.key.split('/').pop())}</span><small>${Math.ceil(i.bytes/1024)} KB</small></label>`).join('');
        q('[data-status]').textContent=working?'이미지 확인 중…':code?(visible.length||children.length?`폴더 ${children.length}개 · 이미지 ${visible.length}개${cursor?' · 더 보기로 나머지 경로 확인':''}`:(cursor?'더 보기로 나머지 경로를 확인해 주세요.':'조회된 폴더나 이미지가 없습니다.')):'상품코드를 입력하고 조회해 주세요.';
        q('[data-more]').hidden=!cursor;q('[data-more]').disabled=working||q('[data-code]').value.trim().toUpperCase()!==code;
        q('[data-code]').disabled=working;q('[data-query]').disabled=working;q('[data-close]').disabled=working;q('[data-cancel]').disabled=working;
        q('[data-apply]').disabled=working||!selected.size||q('[data-code]').value.trim().toUpperCase()!==code;q('[data-count]').textContent=`${selected.size}개 선택`;
    }
    async function load(more){
        if(working)return;const term=q('[data-code]').value.trim().toUpperCase();if(!/^[A-Z0-9_-]{4,40}$/.test(term))return error('상품코드는 영문·숫자·밑줄·하이픈 4~40자로 입력해 주세요.');
        if(more&&term!==code)return;controller?.abort();controller=new AbortController();const token=++generation;working=true;error('');render();
        try{const params=new URLSearchParams({productCode:term});if(more&&cursor)params.set('cursor',cursor);const page=await apiGet(base+'?'+params,{signal:controller.signal});if(!active||token!==generation)return;
            if(!more){if(code!==term){selected.clear();folder='';}items=[];folders=[];}code=page.productCode;const found=new Map(items.map(i=>[i.key,i]));for(const item of page.images)found.set(item.key,item);items=[...found.values()];folders=[...new Set([...folders,...(page.folders||[]),...items.flatMap(i=>{const path=i.key.slice(('products/'+code+'/').length).split('/');return path.slice(0,-1).map((_,n)=>path.slice(0,n+1).join('/'));})])];cursor=page.nextCursor;
        }catch(err){if(active&&token===generation)error(err.message||'R2 이미지 조회에 실패했습니다. 다시 조회해 주세요.');}
        finally{if(active&&token===generation){working=false;render();}}
    }
    window.R2ImagePicker={resolve,open(options={}){close();ensure();items=[];selected=new Map();cursor=null;code='';folder='';folders=[];working=false;q('[data-code]').value=options.productCode||'';error('');const promise=new Promise(finish=>active=finish);render();dialog.showModal();q('[data-code]').focus();if(options.productCode)load(false);return promise;},close};
    window.addEventListener('pagehide',()=>close());
})();
