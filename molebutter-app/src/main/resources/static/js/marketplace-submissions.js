(() => {
    'use strict';
    const statuses={QUEUED:'대기',RUNNING:'처리 중',SUCCEEDED:'성공',ACCEPTED:'승인·반영 확인 필요',PARTIAL:'일부 완료',FAILED:'실패',UNKNOWN:'결과 미확인'};
    const active=execution=>['QUEUED','RUNNING'].includes(execution?.status);
    const steps=execution=>(execution?.targets||[]).flatMap(target=>target.steps||[]);
    const freshPreviewCodes=new Set(['BASELINE_CHANGED','ACCOUNT_CHANGED','ACCESS_DENIED','MAPPING_CHANGED','ALREADY_CREATED','ALREADY_REGISTERED','LEGACY_INTENT','UNSUPPORTED_SNAPSHOT_VERSION']);
    const retryable=(execution,revision)=>!execution.revised&&!active(execution)&&(revision==null||execution.revision===revision)&&steps(execution).some(step=>step.status==='FAILED')&&!steps(execution).some(step=>['UNKNOWN','ACCEPTED','RUNNING'].includes(step.status)||step.status==='FAILED'&&freshPreviewCodes.has(step.code));
    const revisable=execution=>!execution.revised&&['FAILED','PARTIAL'].includes(execution.status)&&steps(execution).some(step=>step.status==='FAILED')&&!steps(execution).some(step=>['UNKNOWN','ACCEPTED','RUNNING'].includes(step.status));
    const reconcilable=execution=>!active(execution)&&steps(execution).some(step=>['UNKNOWN','ACCEPTED'].includes(step.status));
    const fieldNames={vendorId:'업체코드',vendorUserId:'Wing 사용자 ID',brandId:'브랜드 ID',requested:'판매 승인 요청',itemName:'옵션명',amountInStock:'현재 재고',images:'이미지',imageOrder:'표시 순서',imageType:'이미지 유형',vendorPath:'이미지 주소',cdnPath:'이미지 주소',contents:'상품 설명',contentsType:'설명 유형',contentDetails:'설명 내용',detailType:'내용 유형',content:'내용',attributes:'속성',attributeTypeName:'속성명',attributeValueName:'속성값',exposed:'속성 구분',notices:'상품고시',noticeCategoryName:'고시 분류',noticeCategoryDetailName:'고시 항목',certifications:'인증',certificationType:'인증 유형',certificationCode:'인증 번호',requiredDocuments:'구비 서류',templateName:'서류명',documentPath:'서류 경로',vendorDocumentPath:'서류 주소',bundleInfo:'묶음 상품',bundleType:'묶음 유형',autoPricingInfo:'자동 가격'};
    function changeLabel(path,draft,spec={}){
        const parts=String(path).split('.'),labels=[];
        for(let n=0;n<parts.length;n++){
            const part=parts[n];
            if(part==='items'&&/^\[\d+\]$/.test(parts[n+1]||'')){
                const index=Number(parts[++n].slice(1,-1))-1;
                labels.push('옵션 '+(draft?.options?.[index]?.name||String(index+1)));continue;
            }
            if(part==='옵션'&&draft?.options?.some(option=>option.id===parts[n+1])){
                const option=draft.options.find(option=>option.id===parts[++n]);labels.push('옵션 '+option.name);continue;
            }
            if(/^\[\d+\]$/.test(part)){labels[labels.length-1]+=' '+part.slice(1,-1);continue;}
            const compound=parts.slice(n).join('.');
            const optionFields={'options.price':'판매가','options.quantity':'재고','options.name':'등록 옵션명','options.sku':'판매자상품코드'};
            if(optionFields[compound]){labels.push(optionFields[compound]);break;}
            if(spec[compound]){labels.push(spec[compound][0]);break;}
            labels.push(spec[part]?.[0]||fieldNames[part]||part);
        }
        if(parts[0]==='옵션'&&parts.at(-1)==='salePrice')labels[labels.length-1]='현재 판매가';
        return labels.join(' · ');
    }
    function create(options){
        const {$,escape:e}=AppUI;
        const dialog=$('common-submission-dialog'),section=$('common-submissions');
        let currentDraft=null,preview=null,key=null,preparing=false,submitting=false,timer=null,disposed=false,generation=0,page=0,totalPages=0,loading=false,refreshAgain=false;
        const names={COUPANG:'쿠팡',NAVER:'네이버',GMARKET:'G마켓',AUCTION:'옥션',LOTTEON:'롯데ON'};
        const mode=value=>value==='CREATE'?'신규 등록':value==='UPDATE'?'수정':value;
        function error(message){for(const id of ['common-submission-error','common-submission-dialog-error']){$(id).textContent=message||'';$(id).hidden=!message;}}
        function badge(status){return `<span class="badge ${status==='FAILED'?'fail':status==='SUCCEEDED'?'ok':'pending'}">${e(statuses[status]||status||'미확인')}</span>`;}
        function update(){
            $('common-market-save').disabled=preparing||submitting||!options.canAct();
            $('common-requested').disabled=preparing||submitting||!options.canAct();
            $('common-submission-confirm').disabled=submitting||preparing||!preview?.executable;
            $('common-submission-dismiss').disabled=submitting;
            $('common-submission-refresh').disabled=loading||submitting;
            $('common-submission-previous').disabled=loading||page===0;
            $('common-submission-next').disabled=loading||page+1>=totalPages;
        }
        function close(){if(submitting)return;dialog.close();preview=null;key=null;}
        function renderPreview(value){
            $('common-submission-preview').innerHTML=`<p class="common-preview-summary">초안 버전 ${e(String(value.revision))} · ${value.requested?'저장 후 판매 승인 요청':'판매 승인 요청 없이 저장'}</p>`+(value.targets||[]).map(target=>`<article class="common-submission-target"><h3>${e(names[target.market]||target.market)} · ${e(mode(target.mode))}</h3>${(target.issues||[]).length?`<ul class="form-error">${target.issues.map(issue=>`<li>${e(issue.message)}</li>`).join('')}</ul>`:''}<div class="common-planned-steps">${(target.steps||[]).map(step=>`<span>${e(step.label)}</span>`).join('')}</div>${(target.changes||[]).length?`<div class="table-scroll"><table class="common-submission-changes"><thead><tr><th>항목</th><th>현재 값</th><th>변경 값</th></tr></thead><tbody>${target.changes.map(change=>`<tr><th>${e(changeLabel(change.path,currentDraft,window.CoupangEditorModel?.spec))}</th><td>${e(change.before??'—')}</td><td>${e(change.after??'—')}</td></tr>`).join('')}</tbody></table></div>`:'<p class="product-meta">변경된 항목 없음</p>'}</article>`).join('');
            update();
        }
        async function prepare(){
            if(preparing||submitting||!options.canAct())return;
            preparing=true;error('');preview=null;key=null;update();
            try{
                const saved=await options.save();
                if(!saved)return;
                currentDraft=saved;
                options.busy(true);
                const value=await apiPost('/api/marketplaces/submissions/prepare',{draftId:saved.id,revision:saved.revision,...(options.prepare?options.prepare():{}),requested:$('common-requested').checked});
                if(disposed)return;
                if(value?.draftId!==saved.id||value.revision!==saved.revision)throw Error('전송 미리보기를 다시 확인해 주세요.');
                preview=value;key=AppUI.uuid();renderPreview(value);dialog.showModal();$('common-submission-dismiss').focus();
            }catch(err){error(err.message);}
            finally{preparing=false;options.busy(false);update();}
        }
        async function execute(){
            if(submitting||preparing||!preview?.executable)return;
            submitting=true;error('');update();
            const prepared=preview;
            try{
                const execution=await apiPost('/api/marketplaces/submissions/'+encodeURIComponent(prepared.id)+'/execute',{idempotencyKey:key});
                if(disposed)return;
                dialog.close();preview=null;key=null;page=0;
                renderExecutions([execution],1);
                await refresh();section.scrollIntoView({block:'start'});
            }catch(err){
                error(err.message);
                // Retrying this confirmation reuses the exact key. It must never create a second execution.
                await refresh();
            }finally{submitting=false;update();}
        }
        function renderExecutions(items,pages){
            totalPages=pages;
            section.hidden=!currentDraft?.id;
            $('common-submission-list').innerHTML=items.length?items.map(execution=>`<article class="common-execution" data-execution="${e(execution.id)}"><div class="common-execution-heading"><div><strong>실행 ${e(execution.id)}</strong> · 초안 버전 ${e(String(execution.revision))} ${badge(execution.status)}<p class="product-meta">${e(execution.createdAt||'')}</p></div><div class="common-execution-actions">${retryable(execution,currentDraft?.revision)?`<button type="button" class="btn small" data-submission-action="retry" data-execution-id="${e(execution.id)}">실패 작업 재시도</button>`:''}${reconcilable(execution)?`<button type="button" class="btn small" data-submission-action="reconcile" data-execution-id="${e(execution.id)}">결과 확인</button>`:''}</div></div>${steps(execution).some(step=>step.status==='FAILED')&&!retryable(execution,currentDraft?.revision)&&!reconcilable(execution)?'<p class="product-meta">입력을 확인하고 현재 초안으로 새 변경 확인을 진행해 주세요.</p>':''}${(execution.targets||[]).map(target=>`<div class="common-submission-target"><h3>${e(names[target.market]||target.market)} · ${e(mode(target.mode))} ${badge(target.status)}</h3>${target.externalProductId?`<p class="product-meta">등록상품 ID ${e(target.externalProductId)}</p>`:''}<ul class="common-execution-steps">${(target.steps||[]).map(step=>`<li><span>${e(step.label)}</span>${badge(step.status)}<span class="common-step-message">${e(step.message||'')}</span></li>`).join('')}</ul></div>`).join('')}</article>`).join(''):'<p class="common-empty">전송 기록 없음</p>';
            $('common-submission-previous').disabled=loading||page===0;
            $('common-submission-next').disabled=loading||page+1>=totalPages;
            $('common-submission-page').textContent=totalPages?`${page+1} / ${totalPages}`:'0 / 0';
            clearTimeout(timer);timer=null;
            if(items.some(active)&&!disposed)timer=setTimeout(refresh,2000);
        }
        async function refresh(){
            if(!currentDraft?.id||disposed)return;
            if(loading){refreshAgain=true;return;}
            loading=true;update();const seq=generation,id=currentDraft.id;
            try{
                const result=await apiGet('/api/marketplaces/submissions?draftId='+encodeURIComponent(id)+'&page='+page+'&size=10');
                if(disposed||seq!==generation||id!==currentDraft?.id)return;
                renderExecutions(result?.items||[],result?.totalPages||0);
            }catch(err){if(!disposed&&seq===generation)error(err.message);}
            finally{loading=false;update();if(refreshAgain){refreshAgain=false;refresh();}}
        }
        async function action(button){
            if(submitting||preparing)return;
            const type=button.dataset.submissionAction,id=button.dataset.executionId;
            submitting=true;error('');update();button.disabled=true;
            try{await apiPost('/api/marketplaces/submissions/'+encodeURIComponent(id)+'/'+type,{});await refresh();}
            catch(err){error(err.message);}finally{submitting=false;update();}
        }
        $('common-market-save').addEventListener('click',prepare);
        $('common-submission-confirm').addEventListener('click',execute);
        $('common-submission-dismiss').addEventListener('click',close);
        dialog.addEventListener('cancel',event=>{if(submitting)event.preventDefault();else{preview=null;key=null;}});
        $('common-requested').addEventListener('change',()=>{preview=null;key=null;});
        $('common-submission-refresh').addEventListener('click',()=>{error('');refresh();});
        $('common-submission-previous').addEventListener('click',()=>{if(!loading&&page>0){page--;refresh();}});
        $('common-submission-next').addEventListener('click',()=>{if(!loading){page++;refresh();}});
        section.addEventListener('click',event=>{const button=event.target.closest('[data-submission-action]');if(button&&!button.disabled)action(button);});
        window.addEventListener('pagehide',()=>{disposed=true;generation++;clearTimeout(timer);});
        return {
            draft(value){const changed=currentDraft?.id!==value?.id;currentDraft=value;update();if(changed){generation++;page=0;refresh();}},
            changed(){if(preview&&!submitting)close();update();},
            update
        };
    }
    const exports={create,active,retryable,revisable,reconcilable,changeLabel,statuses};
    if(typeof module!=='undefined'&&module.exports)module.exports=exports;else window.MarketplaceSubmissionUI=exports;
})();
