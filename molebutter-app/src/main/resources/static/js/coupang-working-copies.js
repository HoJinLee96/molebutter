"use strict";

(() => {
  'use strict';

  const {
    $,
    escape: e
  } = AppUI;
  if (!$('coupang-working-panel')) return;
  const key = 'molebutter.coupang.working-copies.navigation.v47';
  const editUrl = p => '/coupang/working-copies/' + encodeURIComponent(p.id) + '/edit';
  const stateLabel = p => p.blocked ? '전송 결과 확인 필요' : '임시 저장';
  let page = 0,
    query = '',
    data = null,
    busy = false,
    sequence = 0,
    selected = 'COUPANG';
  function navigation() {
    try {
      sessionStorage.setItem(key, JSON.stringify({
        page,
        query,
        input: $('coupang-working-copies-search').value,
        scroll: window.scrollY,
        tab: selected
      }));
    } catch {}
  }
  function tab(value, autoload = true) {
    selected = value;
    for (const view of ['WORKING', 'COUPANG']) {
      const name = view.toLowerCase();
      $('coupang-' + name + '-panel').hidden = value !== view;
      $('coupang-' + name + '-tab').setAttribute('aria-selected', String(value === view));
    }
    if (autoload && value === 'WORKING' && !data && !busy) load(0);
  }
  function buttons() {
    $('coupang-working-copies-query').querySelectorAll('input,button').forEach(el => el.disabled = busy);
    $('coupang-working-copies-prev').disabled = busy || page === 0;
    $('coupang-working-copies-next').disabled = busy || !data || page + 1 >= data.totalPages;
  }
  async function load(target = 0) {
    if (busy) return;
    const seq = ++sequence;
    busy = true;
    buttons();
    $('coupang-working-copies-error').hidden = true;
    $('coupang-working-copies-summary').textContent = '작업본 조회 중…';
    try {
      const p = new URLSearchParams({
        page: String(target),
        size: '20'
      });
      if (query) p.set('query', query);
      const response = await apiGet('/api/coupang/working-copies?' + p);
      if (seq !== sequence) return;
      if (!Array.isArray(response.items) || !Number.isInteger(response.totalPages)) throw Error('작업본 목록 응답을 확인해 주세요.');
      data = response;
      page = target;
      $('coupang-working-copies-rows').innerHTML = data.items.map(p => {
        const url = editUrl(p);
        return `<tr><td>${e(p.externalProductId || '신규')}</td><td class="wrap">${url ? `<a class="coupang-product-link" href="${url}" data-working-copy-link>${e(p.name || '이름 없는 상품')}</a>` : `<span>${e(p.name || '이름 없는 상품')}</span>`}</td><td class="wrap">${p.mode === 'UPDATE' ? '상품 수정' : '신규 등록'}</td><td>${e(stateLabel(p))}</td><td>${e(p.updatedAt?.replace('T', ' ').slice(0, 19) || '—')}</td><td>${url ? `<a class="btn small primary" href="${url}" data-working-copy-link>수정</a>` : '<span class="product-meta">원상품 번호 확인 필요</span>'}</td></tr>`;
      }).join('') || '<tr><td colspan="6" class="empty">임시 저장 작업본이 없습니다.</td></tr>';
      $('coupang-working-copies-summary').textContent = `${data.totalElements || 0}개 작업본`;
      $('coupang-working-copies-page').textContent = data.totalPages ? `${page + 1} / ${data.totalPages}` : '';
    } catch (err) {
      if (seq === sequence) {
        $('coupang-working-copies-error').textContent = err.message;
        $('coupang-working-copies-error').hidden = false;
        $('coupang-working-copies-summary').textContent = '';
      }
    } finally {
      if (seq === sequence) {
        busy = false;
        buttons();
      }
    }
  }
  $('coupang-working-tab').addEventListener('click', () => tab('WORKING'));
  $('coupang-coupang-tab').addEventListener('click', () => tab('COUPANG'));
  FormActions.bindEnterSubmit($('coupang-working-copies-query'), $('coupang-working-copies-query').querySelector('button[type=submit]'));
  $('coupang-working-copies-query').addEventListener('submit', ev => {
    ev.preventDefault();
    if (busy) return;
    query = $('coupang-working-copies-search').value.trim();
    load(0);
  });
  $('coupang-working-copies-reset').addEventListener('click', () => {
    if (busy) return;
    query = '';
    $('coupang-working-copies-search').value = '';
    load(0);
  });
  $('coupang-working-copies-prev').addEventListener('click', () => load(page - 1));
  $('coupang-working-copies-next').addEventListener('click', () => load(page + 1));
  $('coupang-working-panel').addEventListener('click', ev => {
    if (ev.target.closest('[data-working-copy-link]')) navigation();
  });
  $('coupang-coupang-tab').addEventListener('click', navigation);
  window.addEventListener('pagehide', () => sequence++);
  let saved = null,
    coupang = false;
  try {
    saved = JSON.parse(sessionStorage.getItem(key));
    sessionStorage.removeItem(key);
    coupang = !!sessionStorage.getItem('molebutter.coupang.products.navigation.v46');
  } catch {}
  if (saved && Number.isInteger(saved.page) && saved.page >= 0 && typeof saved.query === 'string') {
    query = saved.query;
    $('coupang-working-copies-search').value = typeof saved.input === 'string' ? saved.input : query;
    selected = coupang ? 'COUPANG' : saved.tab === 'COUPANG' ? saved.tab : 'WORKING';
    tab(selected, false);
    if (selected === 'WORKING' && !busy) load(saved.page).then(() => window.scrollTo?.(0, Number(saved.scroll) || 0));
  } else tab('COUPANG');
})();
