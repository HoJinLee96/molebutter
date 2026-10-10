(() => {
    'use strict';
    const {$,escape:e}=AppUI;
    function create(options){
        let observation=null,initial=null,preview=null,key=null,busy=false,executionId=null,timer=null,disposed=false,submitted=null,completedId=null,blocked=false,refreshing=false,ambiguous=false,lastExecution=null,generation=0;
        const dialog=$('editor-save-dialog'),status={QUEUED:'대기',RUNNING:'전송 중',SUCCEEDED:'성공',ACCEPTED:'승인·반영 대기',PARTIAL:'일부 완료',FAILED:'실패',UNKNOWN:'결과 확인 필요'};
        const error=message=>{$('editor-save-error').textContent=message||'';$('editor-save-error').hidden=!message;$('editor-save-dialog-error').textContent=message||'';$('editor-save-dialog-error').hidden=!message;};
        function update(){options.busy(busy||blocked||refreshing||ambiguous);$('editor-save-confirm').disabled=busy||!preview?.executable;$('editor-save-close').disabled=busy||ambiguous;for(const button of $('editor-save-results').querySelectorAll('[data-save-action]'))button.disabled=busy||refreshing;}
        function remember(id){if(executionId!==id)generation++;executionId=id;try{sessionStorage.setItem('coupang-save-'+options.productId,id);}catch{}}
        const revisable=value=>!value.revised&&['FAILED','PARTIAL'].includes(value.status)&&(value.targets||[]).some(t=>(t.steps||[]).some(s=>s.status==='FAILED'))&&!(value.targets||[]).some(t=>(t.steps||[]).some(s=>['RUNNING','UNKNOWN','ACCEPTED'].includes(s.status)));
        function render(value){
            lastExecution=value;
            const steps=(value.targets||[]).flatMap(t=>t.steps||[]),done=steps.filter(s=>s.status==='SUCCEEDED').length;
            $('editor-save-results').hidden=false;
            $('editor-save-results').innerHTML=`<h2>저장 결과</h2><p role="status">${e(status[value.status]||value.status)} · ${done} / ${steps.length}단계 완료</p><ol>${steps.map(s=>`<li><strong>${e(s.label)}</strong> · ${e(s.code==='NOT_EXECUTED'||s.status==='QUEUED'&&['FAILED','PARTIAL','UNKNOWN','ACCEPTED'].includes(value.status)?'미실행':status[s.status]||s.status)} <span>${e(s.message||'')}</span></li>`).join('')}</ol>${value.revised?'<p>위에서 값을 수정한 뒤 다시 저장할 수 있습니다. 이전 실행 기록은 보관됩니다.</p>':''}<div class="toolbar">${revisable(value)?'<button class="btn primary" type="button" data-save-action="revise">입력 수정</button>':''}<button class="btn" type="button" data-save-action="refresh">새로고침</button>${window.MarketplaceSubmissionUI.retryable(value)?'<button class="btn" type="button" data-save-action="retry">실패·미실행 단계 재시도</button>':''}${window.MarketplaceSubmissionUI.reconcilable(value)?'<button class="btn" type="button" data-save-action="reconcile">결과 확인</button>':''}</div>`;
            clearTimeout(timer);if(['RUNNING','QUEUED'].includes(value.status)&&!disposed)timer=setTimeout(refresh,2000);
            blocked=!value.revised&&(['RUNNING','QUEUED'].includes(value.status)||window.MarketplaceSubmissionUI.retryable(value)||window.MarketplaceSubmissionUI.reconcilable(value));update();if(value.status==='SUCCEEDED'&&submitted&&completedId!==value.id&&!refreshing){refreshing=true;update();options.saved(submitted).then(fresh=>{observation=fresh.observation;initial=structuredClone(fresh.draft);completedId=value.id;}).catch(err=>{blocked=true;error(err.message);}).finally(()=>{refreshing=false;update();});}
        }
        async function refresh(){if(!executionId||disposed)return;const id=executionId,version=generation;try{const value=await apiGet('/api/marketplaces/submissions/'+encodeURIComponent(id));if(id===executionId&&version===generation&&!disposed)render(value);}catch(err){if(id===executionId&&version===generation)error(err.message);}}
        async function prepare(){
            if(busy||blocked||refreshing||ambiguous||!observation)return;
            error('');let changes;try{changes=window.CoupangEditorModel.changes(initial,options.value());}catch(err){error(err.message);return;}
            if(!changes.length){error('변경된 항목이 없습니다.');return;}
            if(Date.parse(observation.expiresAt)<=Date.now()){error('조회 세션이 만료되었습니다. 입력값을 보관한 뒤 상품을 다시 조회해 주세요.');return;}
            busy=true;update();
            try{
                submitted=structuredClone(options.value());preview=await apiPost('/api/marketplaces/coupang/products/'+options.productId+'/save-preparation',{token:observation.token,changes});key=AppUI.uuid();
                $('editor-save-preview').innerHTML=(preview.targets||[]).map(t=>`<h3>쿠팡 상품 수정</h3>${(t.issues||[]).length?'<ul class="form-error">'+t.issues.map(i=>'<li>'+e(i.message)+'</li>').join('')+'</ul>':''}<ol>${(t.steps||[]).map(s=>'<li>'+e(s.label+(t.optionNames?.[s.optionId]?' · '+t.optionNames[s.optionId]:''))+'</li>').join('')}</ol><div class="table-scroll"><table><thead><tr><th>항목</th><th>현재값</th><th>변경값</th></tr></thead><tbody>${(t.changes||[]).map(c=>`<tr><th>${e(window.MarketplaceSubmissionUI.changeLabel(c.path,{options:Object.entries(t.optionNames||{}).map(([id,name])=>({id,name}))},window.CoupangEditorModel.spec))}</th><td>${e(c.before??'—')}</td><td>${e(c.after??'—')}</td></tr>`).join('')}</tbody></table></div>`).join('');
                dialog.showModal();
            }catch(err){error(err.message);}finally{busy=false;update();}
        }
        $('editor-save-confirm').addEventListener('click',async()=>{
            if(busy||!preview?.executable)return;busy=true;update();error('');
            try{
                const value=await apiPost('/api/marketplaces/submissions/'+encodeURIComponent(preview.id)+'/execute',{idempotencyKey:key});ambiguous=false;remember(value.id);render(value);dialog.close();preview=null;
            }catch(err){ambiguous=!err.status||err.status>=500;error(err.message);if(ambiguous)$('editor-save-dialog-error').textContent+=' 실행 접수 결과를 확인하지 못했습니다. 같은 확인 버튼을 다시 누르면 기존 실행을 확인합니다.';}finally{busy=false;update();}
        });
        $('editor-save-close').addEventListener('click',()=>{if(!busy&&!ambiguous){dialog.close();preview=null;}});
        dialog.addEventListener('cancel',ev=>{if(busy||ambiguous)ev.preventDefault();else preview=null;});
        $('editor-save-results').addEventListener('click',async ev=>{
            const action=ev.target.closest('[data-save-action]')?.dataset.saveAction;if(!action||busy||refreshing)return;
            if(action==='revise'){
                if(!lastExecution||!revisable(lastExecution))return;
                busy=true;generation++;clearTimeout(timer);update();error('');
                try{
                    // Keep the trusted original observation: prepare re-reads Coupang,
                    // omits already-applied changes and still detects outside conflicts.
                    const value=await apiPost('/api/marketplaces/submissions/'+encodeURIComponent(executionId)+'/revise',{});
                    if(!value.revised)throw Error('입력 수정 전환 결과를 확인할 수 없습니다. 새로고침 후 다시 시도해 주세요.');
                    preview=null;render(value);
                    $('editor-option-list').scrollIntoView({behavior:'smooth',block:'start'});
                }catch(err){error(err.message);}finally{busy=false;update();}
                if(lastExecution?.revised)$('editor-option-list').querySelector('input:not(:disabled)')?.focus({preventScroll:true});
                return;
            }
            if(!['refresh','retry','reconcile'].includes(action))return;
            if(action==='refresh'){await refresh();return;}busy=true;update();try{render(await apiPost('/api/marketplaces/submissions/'+encodeURIComponent(executionId)+'/'+action,{}));}catch(err){error(err.message);}finally{busy=false;update();}
        });
        window.addEventListener('pagehide',()=>{disposed=true;clearTimeout(timer);});
        return {observe(value,draft){observation=value;initial=structuredClone(draft);try{executionId=sessionStorage.getItem('coupang-save-'+options.productId);}catch{}if(executionId)refresh();else if(value.draftId)apiGet('/api/marketplaces/submissions?draftId='+encodeURIComponent(value.draftId)+'&page=0&size=1').then(result=>{if(result.items?.[0]){remember(result.items[0].id);render(result.items[0]);}}).catch(err=>error(err.message));},prepare,changed(){if(preview&&!busy&&!ambiguous){preview=null;dialog.close();}},busy:()=>busy};
    }
    window.CoupangEditorSave={create};
})();
