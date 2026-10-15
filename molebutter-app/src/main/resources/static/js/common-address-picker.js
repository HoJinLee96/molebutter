/* Seller address IDs are scoped to a marketplace. No writes occur before Apply. */
(() => {
    'use strict';
    const e=AppUI.escape,providers=[['COUPANG','쿠팡'],['NAVER','스마트스토어'],['GMARKET','G마켓'],['AUCTION','옥션'],['LOTTEON','롯데ON'],['ELEVENST','11번가']],kinds=[['outbound','출고지'],['returns','반품/교환지']];
    let dialog,active=null,generation=0,states=new Map(),choices={},changes={},naverReads=new Map();
    const supported=m=>['COUPANG','NAVER','ELEVENST','LOTTEON'].includes(m),key=(m,k)=>m+'.'+k;
    function close(){generation++;for(const s of states.values())s.read?.cancel();for(const read of naverReads.values())read.cancel();naverReads.clear();active=null;if(dialog?.open)dialog.close();}
    function ensure(){
        if(dialog)return;
        dialog=document.createElement('dialog');dialog.className='common-brand-dialog common-address-dialog';dialog.setAttribute('aria-labelledby','common-address-title');
        dialog.innerHTML='<div class="common-subheading"><h2 id="common-address-title">마켓별 출고지·반품/교환지 선택</h2><button type="button" class="btn" data-address-close>닫기</button></div><p class="product-meta">각 마켓에 등록된 주소록을 조회합니다. 선택한 주소는 해당 마켓에만 적용하며 배송비는 바꾸지 않습니다. 각 탭에서 따로 수정한 값은 유지됩니다.</p><button type="button" class="btn" data-address-reload>전체 조회</button><div data-address-providers></div><p class="form-error" role="alert" data-address-error hidden></p><div class="common-brand-actions"><button type="button" class="btn" data-address-cancel>취소</button><button type="button" class="btn primary" data-address-apply>선택한 주소 적용</button></div>';
        document.body.append(dialog);dialog.querySelector('[data-address-close]').onclick=close;dialog.querySelector('[data-address-cancel]').onclick=close;dialog.addEventListener('cancel',close);
        dialog.querySelector('[data-address-reload]').onclick=()=>{for(const s of states.values())if(!s.locked&&!s.read)load(s,1);};
        dialog.querySelector('[data-address-providers]').onclick=event=>{
            const button=event.target.closest('[data-address-state]');if(!button||!active)return;const s=states.get(button.dataset.addressState);if(!s||s.locked||s.read)return;
            if(button.dataset.addressIndex!==undefined){const address=s.items[Number(button.dataset.addressIndex)];if(!address)return;(choices[s.market]??={})[s.kind]=address;(changes[s.market]??={})[s.kind]=address;render();}
            else if(button.dataset.addressPage)load(s,Number(button.dataset.addressPage));
        };
        dialog.querySelector('[data-address-apply]').onclick=()=>{
            if(!active||busy()||!Object.keys(changes).length)return;
            try{active.onApply({selections:structuredClone(changes)});close();}catch(error){const host=dialog.querySelector('[data-address-error]');host.textContent=error.message;host.hidden=false;}
        };
    }
    const busy=()=>[...states.values()].some(s=>s.read);
    function render(){
        const list=providers.filter(([m])=>!active.markets||active.markets.includes(m));
        dialog.querySelector('[data-address-providers]').innerHTML=list.map(([market,label])=>`<section class="common-address-provider"><h3>${e(label)}</h3>${!supported(market)?'<p class="product-meta">주소록 조회 연결 준비 중</p>':`<div class="common-address-kinds">${kinds.filter(([k])=>!active.kind||k===active.kind).map(([kind,title])=>{
            const s=states.get(key(market,kind)),selected=choices[market]?.[kind],message=s.locked?'기존 등록상품의 주소는 해당 마켓 탭에서 변경해 주세요.':s.read?'조회 중…':s.error||s.message||'';
            return `<div><h4>${title}</h4><p class="common-brand-selected">${selected?`${e(selected.name)} · ${e(selected.code)}<br>${e(selected.address)} ${e(selected.detail)}`:'선택 없음'}</p><p class="${s.error?'form-error':'product-meta'}" role="status">${e(message)}</p><div class="common-address-results">${s.items.map((a,n)=>`<button type="button" class="common-brand-result" data-address-state="${key(market,kind)}" data-address-index="${n}" aria-pressed="${selected?.code===a.code&&selected?.address===a.address}" ${s.read||s.locked?'disabled':''}><strong>${e(a.name)} · ${e(a.code)}</strong><span>${e(a.zipCode)} ${e(a.address)} ${e(a.detail)}</span><span>${e(a.phone)}</span></button>`).join('')}</div>${!s.locked?`<div class="common-row"><button type="button" class="btn small" data-address-state="${key(market,kind)}" data-address-page="${s.page}" ${s.read?'disabled':''}>다시 조회</button><button type="button" class="btn small" data-address-state="${key(market,kind)}" data-address-page="${s.page-1}" ${s.read||s.page<=1?'disabled':''}>이전</button><span>${s.page}쪽</span><button type="button" class="btn small" data-address-state="${key(market,kind)}" data-address-page="${s.page+1}" ${s.read||!s.hasNext?'disabled':''}>다음</button></div>`:''}</div>`;
        }).join('')}</div>`}</section>`).join('');
        dialog.querySelector('[data-address-apply]').disabled=busy()||!Object.keys(changes).length;dialog.querySelector('[data-address-reload]').disabled=busy();
    }
    function rows(s,data){
        const items=[];let hasNext;
        if(s.market==='COUPANG'){
            if(!Array.isArray(data?.items)||typeof data.hasNext!=='boolean')throw Error('주소록 응답을 확인해 주세요.');
            for(const place of data.items){if(place.usable!==true)continue;if(!Array.isArray(place.addresses))throw Error('주소록 응답을 확인해 주세요.');for(const a of place.addresses)items.push({code:place.code,name:place.name,zipCode:a.zipCode,address:a.address,detail:a.detail,phone:a.phone});}hasNext=data.hasNext;
        }else if(s.market==='LOTTEON'){if(!Array.isArray(data?.items))throw Error('주소록 응답을 확인해 주세요.');for(const a of data.items){const d=a.data||{};items.push({code:a.id,name:d.dvpNm||a.name,zipCode:d.stnmZipNo||d.zipNo,address:d.stnmZipAddr||d.zipAddr,detail:d.stnmDtlAddr||d.dtlAddr,phone:d.telNo||d.mphnNo});}hasNext=false;}else if(s.market==='ELEVENST'){if(!Array.isArray(data))throw Error('주소록 응답을 확인해 주세요.');for(const a of data)items.push({...a,zipCode:a.zip});hasNext=false;
        }else{
            if(!Array.isArray(data?.addressBooks)||!Number.isInteger(data.totalPage))throw Error('주소록 응답을 확인해 주세요.');
            const types=s.kind==='outbound'?['RELEASE','LOGISTICS_CENTER_RELEASE']:['REFUND_OR_EXCHANGE','LOGISTICS_CENTER_REFUND_OR_EXCHANGE'];
            for(const a of data.addressBooks)if(types.includes(a.addressType))items.push({code:String(a.addressBookNo),name:a.name,zipCode:a.zipCode,address:a.address||a.baseAddress,detail:a.detailAddress,phone:a.phoneNumber1});hasNext=s.page<data.totalPage;
        }
        const normalized=items.map(a=>Object.fromEntries(Object.entries(a).map(([k,v])=>[k,v==null?'':String(v)])));
        if(normalized.some(a=>!(s.market==='LOTTEON'?/^[A-Za-z0-9_-]{1,30}$/:/^[1-9]\d{0,29}$/).test(a.code)||!a.address))throw Error('주소록 응답을 확인해 주세요.');
        return {items:normalized,hasNext};
    }
    async function load(s,page){
        if(!active||s.locked||!supported(s.market)||page<1)return;
        s.read?.cancel();const token=generation;s.error='';s.message='';
        let read;if(s.market==='COUPANG')read=window.CoupangRead.start('/api/marketplaces/coupang/shipping-places/'+(s.kind==='returns'?'return':'outbound')+'?page='+page);
        else if(s.market==='LOTTEON'){const controller=new AbortController();read={cancel:()=>controller.abort(),promise:apiGet('/api/marketplaces/lotteon/metadata/'+(s.kind==='returns'?'return':'outbound')+'/page?filter='+encodeURIComponent(active.lotteonChild||''),{signal:controller.signal})};}
        else if(s.market==='ELEVENST'){const controller=new AbortController();read={cancel:()=>controller.abort(),promise:apiGet('/api/marketplaces/elevenst/addressbooks?kind='+(s.kind==='returns'?'return':'outbound'),{signal:controller.signal})};}
        else {
            // Outbound and return addresses are two views of the same page response.
            let shared=naverReads.get(page);
            if(!shared){const controller=new AbortController();shared={cancel:()=>controller.abort(),promise:apiGet('/api/marketplaces/naver/metadata/addresses?page='+page,{signal:controller.signal})};naverReads.set(page,shared);
                shared.promise.finally(()=>{if(naverReads.get(page)===shared)naverReads.delete(page);}).catch(()=>{});}
            read={cancel(){},promise:shared.promise};
        }
        s.read=read;render();
        try{const data=await read.promise;if(!active||token!==generation||s.read!==read)return;const parsed=rows({...s,page},data);Object.assign(s,parsed,{page});s.message=s.items.length?s.items.length+'개 조회됨':s.hasNext?'이 페이지에 해당 유형의 주소가 없습니다. 다음 페이지를 확인해 주세요.':'등록된 주소가 없습니다.';}
        catch(error){if(active&&token===generation&&s.read===read){s.error=error.message||'주소록 조회에 실패했습니다.';if(s.market==='NAVER'&&/GW.AUTHN|ROLE_NOT_FOUND/.test(s.error))s.error+=' 주소록은 판매자정보 API 그룹의 권한을 확인해 주세요.';}}
        finally{if(active&&token===generation&&s.read===read){s.read=null;render();}}
    }
    window.CommonAddressPicker={open(options){close();ensure();active=options;choices=structuredClone(options.selections||{});changes={};states=new Map(providers.filter(([m])=>supported(m)).flatMap(([market])=>kinds.map(([kind])=>[key(market,kind),{market,kind,locked:!!options.locked?.[market],items:[],page:1,hasNext:false,read:null,error:'',message:''}])));dialog.querySelector('[data-address-error]').hidden=true;render();dialog.showModal();for(const s of states.values())if((!options.markets||options.markets.includes(s.market))&&(!options.kind||options.kind===s.kind))load(s,1);},close};
    window.addEventListener('pagehide',close);
})();
