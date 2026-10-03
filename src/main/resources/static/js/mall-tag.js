/**
 * 쇼핑몰 꼬리표. 쇼핑몰 이름과 고유색의 단일 원본이다.
 * 색상은 at-a-glance 색상표를 따르되, 같은 검정이던 LF몰·헤지스·롯데홈쇼핑은 서로 구분되게 바꿨다(app.css .mall-tag--*).
 */
const MallTag = (() => {
    const names = { LFMALL: 'LF몰', HAZZYS: '헤지스', NAVER_SMART_STORE: '네이버 쇼핑윈도', LOTTE_ON: '롯데온',
        LOTTE_IMALL: '롯데홈쇼핑', HI_THEHYUNDAI: '더현대Hi', HMALL: '현대Hmall' };
    const tones = { LFMALL: 'lfmall', HAZZYS: 'hazzys', NAVER_SMART_STORE: 'naver', LOTTE_ON: 'lotte-on',
        LOTTE_IMALL: 'lotte-imall', HI_THEHYUNDAI: 'hi-thehyundai', HMALL: 'hmall' };
    const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const name = code => names[code] ?? code ?? '';
    /** 모르는 코드는 회색 꼬리표. label을 주면 같은 색에 다른 문구(예: 네이버 스마트스토어)를 쓴다. */
    const html = (code, label = name(code)) => `<span class="mall-tag mall-tag--${tones[code] ?? 'unknown'}">${escape(label)}</span>`;
    return { names, name, html };
})();
