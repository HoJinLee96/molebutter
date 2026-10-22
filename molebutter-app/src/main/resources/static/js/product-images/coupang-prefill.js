/** Preparation only. The click synchronously reserves a new tab; all source data remains in place. */
export function enableCoupangPrefill({button,status,link,snapshot,canStart,onBusy,post}) {
    let busy=false;
    button?.addEventListener('click',async()=>{
        if(busy||!canStart())return;
        busy=true;onBusy(true);button.disabled=true;button.textContent='등록 준비 중…';link.hidden=true;
        let tab;
        try {
            tab=window.open('about:blank','_blank');if(tab){tab.opener=null;if(tab.document){tab.document.title='쿠팡 등록 준비';if(tab.document.body)tab.document.body.textContent='상품 정보·추천값·R2 이미지를 확인하고 있습니다. 잠시 기다려 주세요.';}}
            status.textContent='상품 정보·추천값·R2 이미지를 확인하고 있습니다.';
            const result=await post('/api/coupang/working-copies/prefill',snapshot());
            // Accept only this app's generated token route, never a provider-controlled redirect.
            if(!/^\/coupang\/products\/new\?prefill=[a-f0-9-]{36}$/.test(result.editorUrl))throw Error('등록 화면 주소를 확인하지 못했습니다.');
            link.href=result.editorUrl;link.hidden=false;
            if(tab&&!tab.closed){tab.location.replace(result.editorUrl);status.textContent='새 탭에 등록 입력을 준비했습니다. 내용을 확인 후 직접 저장·전송해 주세요.';}
            else status.textContent='새 탭이 차단됐습니다. 아래 버튼으로 준비한 등록 화면을 여세요.';
        }catch(error){if(tab&&!tab.closed)tab.close();status.textContent=error.message||'등록 준비를 완료하지 못했습니다. 기존 입력은 유지됩니다.';}
        finally{busy=false;onBusy(false);button.disabled=false;button.textContent='상품 등록';}
    });
}
