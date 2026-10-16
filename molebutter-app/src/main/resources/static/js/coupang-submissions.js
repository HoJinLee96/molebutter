"use strict";

(() => {
  'use strict';

  const statuses = {
    QUEUED: '대기',
    RUNNING: '처리 중',
    SUCCEEDED: '성공',
    ACCEPTED: '승인·반영 확인 필요',
    PARTIAL: '일부 완료',
    FAILED: '실패',
    UNKNOWN: '결과 미확인'
  };
  const active = execution => ['QUEUED', 'RUNNING'].includes(execution?.status);
  const steps = execution => execution?.target?.steps || [];
  const freshPreviewCodes = new Set(['BASELINE_CHANGED', 'ACCOUNT_CHANGED', 'ACCESS_DENIED', 'MAPPING_CHANGED', 'ALREADY_CREATED', 'ALREADY_REGISTERED', 'LEGACY_INTENT', 'UNSUPPORTED_SNAPSHOT_VERSION']);
  const retryable = (execution, revision) => !execution.revised && !active(execution) && (revision == null || execution.revision === revision) && steps(execution).some(step => step.status === 'FAILED') && !steps(execution).some(step => ['UNKNOWN', 'ACCEPTED', 'RUNNING'].includes(step.status) || step.status === 'FAILED' && freshPreviewCodes.has(step.code));
  const revisable = execution => !execution.revised && ['FAILED', 'PARTIAL'].includes(execution.status) && steps(execution).some(step => step.status === 'FAILED') && !steps(execution).some(step => ['UNKNOWN', 'ACCEPTED', 'RUNNING'].includes(step.status));
  const reconcilable = execution => !active(execution) && steps(execution).some(step => ['UNKNOWN', 'ACCEPTED'].includes(step.status));
  const fieldNames = {
    vendorId: '업체코드',
    vendorUserId: 'Wing 사용자 ID',
    brandId: '브랜드 ID',
    requested: '판매 승인 요청',
    itemName: '옵션명',
    amountInStock: '현재 재고',
    images: '이미지',
    imageOrder: '표시 순서',
    imageType: '이미지 유형',
    vendorPath: '이미지 주소',
    cdnPath: '이미지 주소',
    contents: '상품 설명',
    contentsType: '설명 유형',
    contentDetails: '설명 내용',
    detailType: '내용 유형',
    content: '내용',
    attributes: '속성',
    attributeTypeName: '속성명',
    attributeValueName: '속성값',
    exposed: '속성 구분',
    notices: '상품고시',
    noticeCategoryName: '고시 분류',
    noticeCategoryDetailName: '고시 항목',
    certifications: '인증',
    certificationType: '인증 유형',
    certificationCode: '인증 번호',
    requiredDocuments: '구비 서류',
    templateName: '서류명',
    documentPath: '서류 경로',
    vendorDocumentPath: '서류 주소',
    bundleInfo: '묶음 상품',
    bundleType: '묶음 유형',
    autoPricingInfo: '자동 가격'
  };
  function changeLabel(path, draft, spec = {}) {
    const parts = String(path).split('.'),
      labels = [];
    for (let n = 0; n < parts.length; n++) {
      const part = parts[n];
      if (part === 'items' && /^\[\d+\]$/.test(parts[n + 1] || '')) {
        const index = Number(parts[++n].slice(1, -1)) - 1;
        labels.push('옵션 ' + (draft?.options?.[index]?.itemName || String(index + 1)));
        continue;
      }
      if (part === '옵션' && draft?.options?.some(option => option.id === parts[n + 1])) {
        const option = draft.options.find(option => option.id === parts[++n]);
        labels.push('옵션 ' + option.itemName);
        continue;
      }
      if (/^\[\d+\]$/.test(part)) {
        labels[labels.length - 1] += ' ' + part.slice(1, -1);
        continue;
      }
      const compound = parts.slice(n).join('.');
      const optionFields = {
        'options.price': '판매가',
        'options.quantity': '재고',
        'options.name': '등록 옵션명',
        'options.sku': '판매자상품코드'
      };
      if (optionFields[compound]) {
        labels.push(optionFields[compound]);
        break;
      }
      if (spec[compound]) {
        labels.push(spec[compound][0]);
        break;
      }
      labels.push(spec[part]?.[0] || fieldNames[part] || part);
    }
    if (parts[0] === '옵션' && parts.at(-1) === 'salePrice') labels[labels.length - 1] = '현재 판매가';
    return labels.join(' · ');
  }
  function create(options) {
    const {
      $,
      escape: e
    } = AppUI;
    const dialog = $('coupang-submission-dialog'),
      section = $('coupang-submissions');
    let currentWorkingCopy = null,
      preview = null,
      key = null,
      preparing = false,
      settling=false,
      settledExecutions=new Set(),
      submitting = false,
      timer = null,
      disposed = false,
      generation = 0,
      page = 0,
      totalPages = 0,
      loading = false,
      refreshAgain = false,
      executions = new Map();
    const mode = value => value === 'CREATE' ? '신규 등록' : value === 'UPDATE' ? '수정' : value;
    function error(message) {
      for (const id of ['coupang-submission-error', 'coupang-submission-dialog-error']) {
        $(id).textContent = message || '';
        $(id).hidden = !message;
      }
    }
    function badge(status) {
      return `<span class="badge ${status === 'FAILED' ? 'fail' : status === 'SUCCEEDED' ? 'ok' : 'pending'}">${e(statuses[status] || status || '미확인')}</span>`;
    }
    const blocked = () => [...executions.values()].some(value=>active(value)||steps(value).some(step=>['RUNNING','QUEUED','UNKNOWN','ACCEPTED'].includes(step.status)));
    function update() {
      $('coupang-save').disabled = preparing || submitting || settling || blocked() || !options.canAct();
      $('coupang-submission-confirm').disabled = submitting || preparing || settling || !preview?.executable;
      $('coupang-submission-dismiss').disabled = submitting;
      $('coupang-submission-refresh').disabled = loading || submitting;
      $('coupang-submission-previous').disabled = loading || page === 0;
      $('coupang-submission-next').disabled = loading || page + 1 >= totalPages;
      section.querySelectorAll('[data-submission-action]').forEach(button => {
        button.disabled = submitting || preparing || settling || loading;
      });
    }
    function close() {
      if (submitting) return;
      dialog.close();
      preview = null;
      key = null;
    }
    function renderPreview(value) {
      function renderPreviewTarget(target) {
        return target ? `<article class="coupang-submission-target"><h3>${e('쿠팡')} · ${e(mode(target.mode))}</h3>${''}${(target.issues || []).length ? `<ul class="form-error">${target.issues.map(issue => `<li>${e(issue.message)}</li>`).join('')}</ul>` : ''}<div class="coupang-planned-steps">${(target.steps || []).map(step => `<span>${e(step.label)}</span>`).join('')}</div>${(target.changes || []).length ? `<div class="table-scroll"><table class="coupang-submission-changes"><thead><tr><th>항목</th><th>현재 값</th><th>변경 값</th></tr></thead><tbody>${target.changes.map(change => `<tr><th>${e(changeLabel(change.path, currentWorkingCopy, window.CoupangEditorModel?.spec))}</th><td>${e(change.before ?? '—')}</td><td>${e(change.after ?? '—')}</td></tr>`).join('')}</tbody></table></div>` : '<p class="product-meta">변경된 항목 없음</p>'}</article>` : "";
      }
      $('coupang-submission-preview').innerHTML = `<p class="coupang-preview-summary">작업본 버전 ${e(String(value.revision))} · ${(value.target?.steps || []).some(s => s.type === 'APPROVAL') ? '쿠팡 저장 후 판매 승인 요청' : '상품 저장'}</p>` + renderPreviewTarget(value.target);
      update();
    }
    async function prepare() {
      if (preparing || submitting || settling || blocked() || !options.canAct()) return;
      preparing = true;
      error('');
      preview = null;
      key = null;
      update();
      try {
        if (options.beforePrepare && !(await options.beforePrepare())) return;
        const saved = await options.save();
        if (!saved) return;
        currentWorkingCopy = saved;
        options.busy(true);
        const value = await apiPost('/api/coupang/submissions/prepare', {
          workingCopyId: saved.id,
          revision: saved.revision,
          ...(options.prepare ? options.prepare() : {})
        });
        if (disposed) return;
        if (value?.workingCopyId !== saved.id || value.revision !== saved.revision) throw Error('전송 미리보기를 다시 확인해 주세요.');
        preview = value;
        key = AppUI.uuid();
        renderPreview(value);
        dialog.showModal();
        $('coupang-submission-dismiss').focus();
      } catch (err) {
        error(err.message);
      } finally {
        preparing = false;
        options.busy(false);
        update();
      }
    }
    async function execute() {
      if (submitting || preparing || settling || !preview?.executable) return;
      submitting = true;
      error('');
      update();
      const prepared = preview;
      try {
        const execution = await apiPost('/api/coupang/submissions/' + encodeURIComponent(prepared.id) + '/execute', {
          idempotencyKey: key
        });
        if (disposed) return;
        dialog.close();
        preview = null;
        key = null;
        page = 0;
        renderExecutions([execution], 1);
        await settle([execution]);
        await refresh();
        section.scrollIntoView({
          block: 'start'
        });
      } catch (err) {
        error(err.message);
        // Retrying this confirmation reuses the exact key. It must never create a second execution.
        await refresh();
      } finally {
        submitting = false;
        options.busy(preparing || settling);
        update();
      }
    }
    function renderExecutions(items, pages) {
      function renderExecutionTarget(target) {
        return target ? `<div class="coupang-submission-target"><h3>${e('쿠팡')} · ${e(mode(target.mode))} ${badge(target.status)}</h3>${target.externalProductId ? `<p class="product-meta">등록상품 ID ${e(target.externalProductId)}</p>${/^\d{1,30}$/.test(target.externalProductId) ? `<a class="btn small" href="/coupang/products/${encodeURIComponent(target.externalProductId)}/edit">쿠팡 상품 수정</a>` : ''}` : ''}<ul class="coupang-execution-steps">${(target.steps || []).map(step => `<li><span>${e(step.label)}</span>${badge(step.status)}<span class="coupang-step-message">${e(step.message || '')}</span></li>`).join('')}</ul></div>` : "";
      }
      executions = new Map(items.map(execution => [execution.id, execution]));
      totalPages = pages;
      section.hidden = !currentWorkingCopy?.id;
      $('coupang-submission-list').innerHTML = items.length ? items.map(execution => `<article class="coupang-execution" data-execution="${e(execution.id)}"><div class="coupang-execution-heading"><div><strong>실행 ${e(execution.id)}</strong> · 작업본 버전 ${e(String(execution.revision))} ${badge(execution.status)}<p class="product-meta">${e(execution.createdAt || '')}</p></div><div class="coupang-execution-actions">${(!execution.workingCopyId || execution.workingCopyId === currentWorkingCopy?.id) && retryable(execution, currentWorkingCopy?.revision) ? `<button type="button" class="btn small" data-submission-action="retry" data-execution-id="${e(execution.id)}">실패 작업 재시도</button>` : ''}${revisable(execution) ? `<button type="button" class="btn small" data-submission-action="revise" data-execution-id="${e(execution.id)}">입력 수정</button>` : ''}${reconcilable(execution) ? `<button type="button" class="btn small" data-submission-action="reconcile" data-execution-id="${e(execution.id)}">결과 확인</button>` : ''}</div></div>${execution.revised ? '<p class="product-meta">기존 처리 결과를 보존했습니다. 입력을 수정한 뒤 새 변경 확인을 진행해 주세요.</p>' : revisable(execution) ? '<p class="product-meta">입력 수정은 기존 처리 결과를 보존하고 아직 실행하지 않은 작업을 종료합니다. 작성 중인 입력은 유지됩니다.</p>' : ''}${steps(execution).some(step => step.status === 'FAILED') && !execution.revised && !revisable(execution) && !retryable(execution, currentWorkingCopy?.revision) && !reconcilable(execution) ? '<p class="product-meta">입력을 확인하고 현재 작업본으로 새 변경 확인을 진행해 주세요.</p>' : ''}${renderExecutionTarget(execution.target)}</article>`).join('') : '<p class="coupang-empty">전송 기록 없음</p>';
      $('coupang-submission-previous').disabled = loading || page === 0;
      $('coupang-submission-next').disabled = loading || page + 1 >= totalPages;
      $('coupang-submission-page').textContent = totalPages ? `${page + 1} / ${totalPages}` : '0 / 0';
      clearTimeout(timer);
      timer = null;
      if (items.some(active) && !disposed) timer = setTimeout(refresh, 2000);
      update();
    }
    async function settle(items){
      if(!options.settled)return;
      for(const item of items){const identity=item.id+':'+item.revision+':'+(item.revised?'revised':item.status+':'+steps(item).filter(s=>s.status==='SUCCEEDED').map(s=>s.id||s.label).join(','));
        if(item.workingCopyId!==currentWorkingCopy?.id||(!item.revised&&item.status!=='SUCCEEDED'&&!(item.status==='PARTIAL'&&steps(item).some(s=>s.status==='SUCCEEDED')))||settledExecutions.has(identity))continue;
        settling=true;options.busy(true);update();
        try{await options.settled(item);settledExecutions.add(identity);}
        finally{settling=false;options.busy(preparing||submitting);update();}
      }
    }
    async function refresh() {
      if (!currentWorkingCopy?.id || disposed) return;
      if (loading) {
        refreshAgain = true;
        return;
      }
      loading = true;
      update();
      const seq = generation,
        id = currentWorkingCopy.id;
      try {
        const result = await apiGet('/api/coupang/submissions?workingCopyId=' + encodeURIComponent(id) + '&page=' + page + '&size=10');
        if (disposed || seq !== generation || id !== currentWorkingCopy?.id) return;
        renderExecutions(result?.items || [], result?.totalPages || 0);
        await settle(result?.items||[]);
      } catch (err) {
        if (!disposed && seq === generation) error(err.message);
      } finally {
        loading = false;
        update();
        if (refreshAgain) {
          refreshAgain = false;
          refresh();
        }
      }
    }
    async function action(button) {
      if (disposed || submitting || preparing || settling || loading) return;
      const type = button.dataset.submissionAction,
        id = button.dataset.executionId,
        execution = executions.get(id);
      if (!execution || execution.workingCopyId && execution.workingCopyId !== currentWorkingCopy?.id || !{
        retry: () => retryable(execution, currentWorkingCopy?.revision),
        revise: () => revisable(execution),
        reconcile: () => reconcilable(execution)
      }[type]?.()) return;
      const seq = generation,
        workingCopyId = currentWorkingCopy?.id;
      submitting = true;
      error('');
      options.busy(true);
      update();
      try {
        const result = await apiPost('/api/coupang/submissions/' + encodeURIComponent(id) + '/' + type, {});
        if (disposed || seq !== generation || workingCopyId !== currentWorkingCopy?.id) return;
        if (type === 'revise') {
          dialog.close();
          preview = null;
          key = null;
          renderExecutions([...executions.values()].map(item => item.id === id ? result : item), totalPages);
        }
        await refresh();
        await settle([result]);
      } catch (err) {
        if (!disposed && seq === generation) error(err.message);
      } finally {
        submitting = false;
        options.busy(false);
        update();
      }
    }
    FormActions.bindExplicitSubmit($('coupang-editor-form'), $('coupang-save'), prepare);
    $('coupang-submission-confirm').addEventListener('click', execute);
    $('coupang-submission-dismiss').addEventListener('click', close);
    dialog.addEventListener('cancel', event => {
      if (submitting) event.preventDefault();else {
        preview = null;
        key = null;
      }
    });
    $('coupang-submission-refresh').addEventListener('click', () => {
      error('');
      refresh();
    });
    $('coupang-submission-previous').addEventListener('click', () => {
      if (!loading && page > 0) {
        page--;
        refresh();
      }
    });
    $('coupang-submission-next').addEventListener('click', () => {
      if (!loading) {
        page++;
        refresh();
      }
    });
    section.addEventListener('click', event => {
      const button = event.target.closest('[data-submission-action]');
      if (button && !button.disabled) action(button);
    });
    window.addEventListener('pagehide', () => {
      disposed = true;
      generation++;
      clearTimeout(timer);
    });
    return {
      workingCopy(value) {
        const changed = currentWorkingCopy?.id !== value?.id;
        const revisionChanged = currentWorkingCopy?.revision !== value?.revision;
        currentWorkingCopy = value;
        update();
        if (changed) {
          generation++;
          page = 0;
          executions.clear();settledExecutions.clear();
          $('coupang-submission-list').innerHTML = '';
          dialog.close();
          preview = null;
          key = null;
          refresh();
        } else if (revisionChanged) refresh();
      },
      changed() {
        if (preview && !submitting) close();
        update();
      },
      update
    };
  }
  const exports = {
    create,
    active,
    retryable,
    revisable,
    reconcilable,
    changeLabel,
    statuses
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = exports;else window.CoupangSubmissionUI = exports;
})();
