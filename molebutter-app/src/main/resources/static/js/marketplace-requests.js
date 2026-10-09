/* Each browser read has a server cancellation token; no client-side retry loop. */
(() => {
    'use strict';
    const active=new Set();
    function start(url,options={}){
        const id=AppUI.uuid(),controller=new AbortController();let settled=false;
        const read={
            promise:(options.method&&options.method!=='GET'?apiRequest(url,{...options,signal:controller.signal,headers:{...options.headers,'X-Coupang-Request-Id':id}}):apiGet(url,{...options,signal:controller.signal,headers:{...options.headers,'X-Coupang-Request-Id':id}})).finally(()=>{settled=true;active.delete(read);}),
            cancel(){
                if(settled||controller.signal.aborted)return;
                controller.abort();active.delete(read);
                // Internal cancellation only; uses the same CSRF protection as other POSTs.
                securedFetch('/api/marketplaces/coupang/requests/'+id+'/cancel',{method:'POST',keepalive:true}).catch(()=>{});
            }
        };
        active.add(read);return read;
    }
    window.addEventListener('pagehide',()=>{for(const read of [...active])read.cancel();});
    window.CoupangRead={start};
})();
