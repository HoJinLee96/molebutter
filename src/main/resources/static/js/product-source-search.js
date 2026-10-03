/** 매입처 원문 링크는 HTTP(S) 주소만 표시한다. */
const ProductSourceSearch = (() => {
    function webUrl(value) {
        try {const u = new URL(value); return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password ? u.href : '';}
        catch {return '';}
    }
    return { webUrl };
})();
