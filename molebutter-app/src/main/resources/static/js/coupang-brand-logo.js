"use strict";
(() => {
  function current(option){const rows=(option.contents||[]).filter(c=>c.purpose==='BRAND_LOGO');if(rows.length>1)throw Error('브랜드 로고가 여러 개입니다. 설명 행을 확인해 주세요.');return rows[0]||null;}
  function apply(option,url,uuid){
    const old=current(option);if(old?.content===url||!old&&!url)return false;
    const rows=option.contents||(option.contents=[]);
    if(url&&rows.some(c=>c!==old&&c.detailType==='IMAGE'&&c.content===url))throw Error('같은 이미지가 다른 설명 행에 있습니다. 기존 행을 확인한 뒤 선택해 주세요.');
    if(old){if(url){old.content=url;old.assetId=null;}else rows.splice(rows.indexOf(old),1);return true;}
    if(url){let at=0;while(at<rows.length&&rows[at].detailType==='IMAGE'&&(()=>{try{return new URL(rows[at].content).pathname.includes('/common/');}catch{return false;}})())at++;
      rows.splice(at,0,{id:uuid(),type:'IMAGE',detailType:'IMAGE',content:url,assetId:null,purpose:'BRAND_LOGO'});return true;
    }return false;
  }
  const api={current,apply};if(typeof module!=='undefined')module.exports=api;if(typeof window!=='undefined')window.CoupangBrandLogo=api;
})();
