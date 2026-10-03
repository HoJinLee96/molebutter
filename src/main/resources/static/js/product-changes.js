/* Shared rendering of server-calculated deltas. Never derives stock or review state in the browser. */
window.ProductChanges=(() => {
    const {escape:e,stamp}=AttendanceUI;
    const number=n=>Number(n).toLocaleString('ko-KR');
    const unit=k=>['PRICE','DELIVERY','GROUP_PRICE'].includes(k)?'원':'개';
    const names={PRICE:'상품가',GROUP_PRICE:'매장 최저 검색가',DELIVERY:'배송비',STOCK:'재고',SOLD_OUT:'품절 전환',RESTOCK:'재입고',AVAILABILITY:'구매 상태 변경',OPTION_NEW:'신규 옵션',OPTION_MISSING:'옵션 구성 변경',NEW:'신규 판매글',MISSING:'이번 검색에서 미발견',REAPPEARED:'이번 검색에서 재발견',IDENTITY:'매장·채널 변경'};
    const state=s=>({AVAILABLE:'구매 가능',SOLD_OUT:'품절',UNAVAILABLE:'구매 불가',STOCK_UNKNOWN:'미확인'})[s]||s||'미확인';
    const button=(d,text,cls='neutral')=>`<button type="button" class="value-delta ${cls}" data-change-info="${e(JSON.stringify(d))}" aria-label="${e((names[d.kind]||'변동')+' '+text+' · 이전값과 시각 보기')}">${text}</button>`;
    function chip(d){
        if(!d)return '';
        if(d.difference!=null&&d.difference!==0){
            const price=['PRICE','DELIVERY','GROUP_PRICE'].includes(d.kind),up=d.difference>0;
            const pct=['PRICE','GROUP_PRICE'].includes(d.kind)&&d.before>0?` <span>${up?'+':'−'}${Math.abs(d.difference/d.before*100).toFixed(1)}%</span>`:'';
            const stock=['STOCK','SOLD_OUT','RESTOCK'].includes(d.kind);
            return button(d,`${up?'▲':'▼'} ${number(Math.abs(d.difference))}${unit(d.kind)}${pct}`,price?(up?'up':'down'):stock?(up?'down':'up'):'neutral');
        }
        if(['SOLD_OUT','RESTOCK','AVAILABILITY','OPTION_NEW','OPTION_MISSING'].includes(d.kind))return button(d,e(names[d.kind]),d.kind==='RESTOCK'?'restock':d.kind==='SOLD_OUT'?'soldout':'neutral');
        return '';
    }
    const listing=(summary,id)=>summary?.listings?.find(l=>l.supplierId===id);
    const metric=(summary,id,kind)=>chip(listing(summary,id)?.deltas?.find(d=>d.kind===kind));
    function option(summary,id,o){
        const ds=(listing(summary,id)?.deltas||[]).filter(d=>d.optionId===o.id&&d.scope===(o.stockScope||'OPTION'));
        return ds.filter(d=>!(d.kind==='STOCK'&&ds.some(x=>x.kind==='SOLD_OUT'))).map(chip).join('');
    }
    function selectedStock(summary,l,product){
        if(!summary||!l)return '';
        if((l.result?.options||[]).length===1)return option(summary,l.id,l.result.options[0]);
        const ds=(listing(summary,l.id)?.deltas||[]).filter(d=>d.optionId);
        const count=new Set(ds.map(d=>d.scope+':'+d.optionId)).size;
        return count?(product?`<button type="button" class="value-delta neutral" data-detail="${e(product)}">옵션 재고 변동 ${count}건</button>`:`<a class="value-delta neutral" href="#listing-${e(l.id)}">옵션 재고 변동 ${count}건</a>`):'';
    }
    function group(summary,id){
        const g=summary?.groups?.find(g=>g.groupId===id);if(!g)return '';
        return (g.difference?`<span class="group-price-change">최저 검색가 ${chip({kind:'GROUP_PRICE',...g,baselineAt:summary.reviewedAt})}</span>`:'')+(g.cheapestChanged?'<span class="product-meta">최저가 판매글 변경</span>':'')+(g.changedListings?`<span class="group-change-count">변동 ${g.changedListings}건</span>`:'');
    }
    const alternative=(s,id)=>s?.alternatives?.find(a=>a.supplierId===id);
    function summary(s){
        if(!s?.reviewedAt)return '<p class="field-hint">가격·재고의 정상 확인값이 생기면 변동 비교를 시작합니다.</p>';
        const rows=s.listings||[],sel=listing(s,s.selectedId),other=rows.filter(l=>l.changed&&l.supplierId!==s.selectedId);
        const price=other.filter(l=>l.deltas.some(d=>d.kind==='PRICE')).length,stock=other.filter(l=>l.deltas.some(d=>d.kind==='STOCK'||['SOLD_OUT','RESTOCK','AVAILABILITY'].includes(d.kind))).length;
        const fresh=rows.filter(l=>l.fresh).length,missing=rows.filter(l=>l.missing).length;
        const text=[price?`가격 변동 ${price}건`:'',stock?`재고·구매 상태 변동 ${stock}건`:'',fresh?`신규 판매글 ${fresh}건`:'',missing?`검색 미발견 ${missing}건`:''].filter(Boolean).join(' · ');
        const a=s.alternatives?.[0];
        const optionCount=new Set((sel?.deltas||[]).filter(d=>d.optionId).map(d=>d.scope+':'+d.optionId)).size;
        const badges=(sel?.deltas||[]).filter(d=>!(optionCount>1&&d.optionId)&&!(d.kind==='STOCK'&&sel.deltas.some(x=>x.kind==='SOLD_OUT'&&x.optionId===d.optionId&&x.scope===d.scope))).map(chip).join('')+(optionCount>1?`<a class="value-delta neutral" href="#listing-${e(s.selectedId)}">옵션 재고 변동 ${optionCount}건</a>`:'');
        return `<section class="change-summary ${s.anyChanged?'has-changes':''}" aria-label="마지막 확인 대비 변동"><div class="change-summary-heading"><strong>마지막 확인 대비 변동</strong><span class="product-meta">${s.reviewKind==='SYSTEM_INITIAL'?'시스템 초기 기준':e(s.reviewer||'검토 완료')} · ${e(stamp(s.reviewedAt))}</span><button type="button" id="change-review" class="btn small" ${!s.reviewable?'disabled title="진행 중인 조회가 완료된 후 확인할 수 있습니다."':''}>확인 완료</button></div><dl>
        <div><dt>선정 매입처</dt><dd>${!s.selectedId?'매입처 미선정':sel?.missing?'이번 검색에서 미발견':badges||'확인된 값의 변동 없음'}${s.selectedChanged?'<span class="change-action">가격·재고 변동 검토</span>':''}</dd></div>
        ${a?`<div><dt>더 저렴한 매입처</dt><dd>${e(a.name)} <strong>${number(a.price)}원</strong><span class="change-cheaper">선정가보다 ${number(a.saving)}원 저렴</span><a href="#listing-${e(a.supplierId)}">판매글 보기</a></dd></div>`:''}
        <div><dt>그 외 판매글</dt><dd>${e(text|| (other.length?`옵션 등 변동 ${other.length}건`:'확인된 값의 변동 없음'))}</dd></div></dl><p class="field-hint">확인 완료는 팀 공통 검토 기록이며 판매 채널 반영 여부를 뜻하지 않습니다.</p></section>
        ${missing?`<details class="change-missing"><summary>이번 검색에서 미발견 ${missing}개</summary><p class="field-hint">검색 범위 밖에 있을 수 있으며 판매 종료나 품절을 뜻하지 않습니다.</p><ul>${rows.filter(l=>l.missing).map(l=>`<li>${e(l.name)} ${ProductSourceSearch.webUrl(l.url)?`<a href="${e(ProductSourceSearch.webUrl(l.url))}" target="_blank" rel="noopener noreferrer">판매글 ↗</a>`:''}</li>`).join('')}</ul></details>`:''}`;
    }
    function history(items,productId){return items.map(h=>{
        const review=['REVIEWED','SYSTEM_INITIAL'].includes(h.source);
        return `<article class="value-history-item"><div><strong>${e(stamp(h.at))}</strong><span class="product-meta">${review?(h.source==='REVIEWED'?'확인 완료 · '+e(h.actor||''):'시스템 초기 기준'):h.source==='STOCK_LOOKUP'?'개별 재고 조회':'자동 최신화'}</span></div>${h.name?`<p>${e(h.name)} ${ProductSourceSearch.webUrl(h.url)?`<a href="${e(ProductSourceSearch.webUrl(h.url))}" target="_blank" rel="noopener noreferrer">판매글 ↗</a>`:''}</p>`:''}${productId&&h.originProductId&&h.originProductId!==productId?`<p class="product-meta">통합 전 상품 ${e(h.originProductId)}의 기록</p>`:''}${review?'':`<ul>${h.changes.map(d=>`<li><span>${e(names[d.kind]||d.kind)}${d.optionLabel?' · '+e(d.optionLabel):''}</span> ${d.before!=null||d.after!=null?`<span>${d.before==null?'미확인':number(d.before)+unit(d.kind)} → <strong>${d.after==null?'미확인':number(d.after)+unit(d.kind)}</strong></span>`:''}${d.beforeState||d.afterState?` <span>${e(state(d.beforeState))} → ${e(state(d.afterState))}</span>`:''}${d.note?`<span class="product-meta">${e(d.note)}</span>`:''}</li>`).join('')}</ul>`}</article>`;
    }).join('')||'<p class="field-hint">해당 조건의 변동 이력이 없습니다.</p>';}
    function inspect(d){
        let dialog=document.getElementById('change-value-dialog');
        if(!dialog){dialog=document.createElement('dialog');dialog.id='change-value-dialog';dialog.className='attendance-dialog change-value-dialog';dialog.setAttribute('aria-labelledby','change-value-title');document.body.append(dialog);dialog.addEventListener('click',ev=>{if(ev.target===dialog||ev.target.closest('[data-dismiss-change]'))dialog.close();});}
        dialog.innerHTML=`<div class="product-dialog-head"><h2 id="change-value-title">${e(names[d.kind]||'변동 상세')}${d.optionLabel?' · '+e(d.optionLabel):''}</h2><button type="button" class="btn small" data-dismiss-change>닫기</button></div><dl class="change-value-facts"><dt>비교 기준</dt><dd>${d.before==null?'확인값 없음':number(d.before)+unit(d.kind)} <span class="product-meta">${d.baselineAt?e(stamp(d.baselineAt)):''}</span></dd><dt>현재 확인값</dt><dd>${d.after==null?'확인값 없음':number(d.after)+unit(d.kind)} <span class="product-meta">${d.checkedAt?e(stamp(d.checkedAt)):''}</span></dd>${d.beforeState||d.afterState?`<dt>구매 상태</dt><dd>${e(state(d.beforeState))} → ${e(state(d.afterState))}</dd>`:''}</dl>${d.note?`<p class="field-hint">${e(d.note)}</p>`:''}`;
        dialog.showModal();
    }
    document.addEventListener('click',ev=>{const b=ev.target.closest('[data-change-info]');if(b)inspect(JSON.parse(b.dataset.changeInfo));});
    return {listing,metric,option,selectedStock,group,alternative,summary,history};
})();
