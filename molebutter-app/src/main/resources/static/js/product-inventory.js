(() => {
    let scheduled=false,sequence=0;
    async function refresh() {
        scheduled=false;const seq=++sequence,nodes=[...document.querySelectorAll('[data-inventory-summary]')];
        const ids=[...new Set(nodes.map(el=>el.dataset.inventorySummary))];if(!ids.length)return;
        try {
            const rows=[];
            for(let start=0;start<ids.length;start+=100)rows.push(...await apiGet('/api/inventory/summaries?'+new URLSearchParams({productIds:ids.slice(start,start+100).join(',')})));
            if(seq!==sequence)return;const totals=new Map(rows.map(row=>[row.productId,row]));
            nodes.filter(el=>el.isConnected).forEach(el=>{const row=totals.get(el.dataset.inventorySummary);el.textContent=`보유 ${row?.onHand||0}개 · 미입고 ${row?.pending||0}개`;});
        }catch(err){if(seq===sequence)nodes.filter(el=>el.isConnected).forEach(el=>el.textContent='보유 재고 조회 실패');}
    }
    function schedule(){if(!scheduled){scheduled=true;queueMicrotask(refresh);}}
    const observer=new MutationObserver(schedule);
    for(const id of ['product-rows','detail-summary']){const root=document.getElementById(id);if(root)observer.observe(root,{childList:true});}
    schedule();document.addEventListener('visibilitychange',()=>{if(!document.hidden)schedule();});
})();
