"use strict";
(() => {
  const clone = value => structuredClone(value), same = (a,b) => JSON.stringify(a) === JSON.stringify(b);
  const price=typeof module!=='undefined'?require('./app-ui.js').normalizeCoupangPrice:AppUI.normalizeCoupangPrice;
  const basicKeys=['sellerProductName','displayProductName','generalProductName','brand','productGroup','displayCategoryCode'];
  function inflate(entry){
    const d=clone(entry.document), source=entry.observation;
    d.limits=source?.limits||{categoryReadOnly:false,optionStructureReadOnly:false,purchaseAttributesReadOnly:false};
    if(source)d.basic={...source.basic,...d.basic};
    d.options=d.options.map((o,n)=>{const observed=source?.options?.[n];return {...o,...(observed?{sellerProductItemId:observed.sellerProductItemId,vendorItemId:observed.vendorItemId,current:observed.current,currentError:observed.currentError,attributePermissions:observed.attributePermissions,separateCurrentChanges:observed.separateCurrentChanges}:{}),originalAttributes:clone(observed?.attributes||o.attributes),currentChanges:observed?.separateCurrentChanges?{...(observed.current?.salePrice!=null&&String(observed.current.salePrice)!==String(o.registration.salePrice)?{salePrice:o.registration.salePrice}:{}),...(observed.current?.amountInStock!=null&&String(observed.current.amountInStock)!==String(o.registration.maximumBuyCount)?{amountInStock:o.registration.maximumBuyCount}:{})}:{},noticeCategory:o.noticeCategory||o.notices?.[0]?.category||''};});
    return d;
  }
  function flatten(d){
    const uuid=typeof module!=='undefined'?require('./app-ui.js').uuid:AppUI.uuid;
    return {basic:Object.fromEntries(basicKeys.filter(k=>d.basic[k]!=null).map(k=>[k,String(d.basic[k])])),options:d.options.map(o=>{
      const noticeCategory=o.noticeCategory||o.notices?.[0]?.category||null,notices=(o.notices||[]).filter(n=>n.category===noticeCategory);
      const registration={...o.registration};for(const k of ['originalPrice','salePrice'])if(registration[k]!=null)registration[k]=price(registration[k]);if(o.currentChanges?.salePrice!=null)registration.salePrice=price(o.currentChanges.salePrice);if(o.currentChanges?.amountInStock!=null)registration.maximumBuyCount=String(o.currentChanges.amountInStock);
      return {id:o.id,itemName:o.itemName||'',registration,attributes:(o.attributes||[]).filter(a=>!a._automatic||String(a.value||'').length>0).map(a=>({name:a.name,value:a.value,exposed:a.exposed})),images:(o.images||[]).map((i,n)=>({id:i.id||(i.id=uuid()),assetId:i.assetId||null,url:i.url||null,type:i.type||'DETAIL',order:i.order??n})),contents:(o.contents||[]).map(c=>({id:c.id||(c.id=uuid()),type:c.type,detailType:c.detailType||'TEXT',content:c.content||'',assetId:c.assetId||null,...(c.purpose?{purpose:c.purpose}:{})})),notices:clone(notices),certifications:clone(o.certifications||[]),noticeCategory};
    }),delivery:clone(d.delivery),settings:clone(d.settings),documents:(d.documents||[]).map(v=>({templateName:v.templateName,path:v.path??null,vendorPath:v.vendorPath??null}))};
  }
  const rowSpecs={attributes:{keys:['name','exposed'],fields:['value']},notices:{keys:['category','name'],fields:['content']},certifications:{keys:['type'],fields:['code']},documents:{keys:['templateName'],fields:['path','vendorPath']}};
  const keyOf=c=>JSON.stringify([c.path,c.optionId||null,c.rowKey||null]);
  const rowKey=(row,spec)=>spec.keys.map(k=>String(row[k]??''));
  const rowId=(row,spec)=>JSON.stringify(rowKey(row,spec));
  const legacyList=c=>!c.rowKey&&['options.attributes','options.notices','options.certifications','documents'].includes(c.path);
  function changes(before,after,remembered=[]){
    const out=new Map(remembered.filter(c=>!legacyList(c)).map(c=>[keyOf(c),clone(c)]));
    const add=(path,optionId,value,rowKey=null,operation='SET')=>{const c={path,optionId,value,...(rowKey?{rowKey,operation}:{})};out.set(keyOf(c),c);};
    const rows=(path,optionId,old,next,spec)=>{
      const previous=new Map(old.map(r=>[rowId(r,spec),r])), wanted=new Map(next.map(r=>[rowId(r,spec),r]));
      if(previous.size!==old.length||wanted.size!==next.length)throw Error('목록 행의 식별자가 중복되었습니다. 입력을 확인해 주세요.');
      const removeChildren=key=>{for(const [id,c] of out)if(c.optionId===optionId&&c.path.startsWith(path+'.')&&same(c.rowKey,key))out.delete(id);};
      for(const [id,row] of wanted){const key=rowKey(row,spec),prior=previous.get(id),parent=out.get(keyOf({path,optionId,rowKey:key}));
        if(!prior||parent?.operation==='SET'){removeChildren(key);add(path,optionId,row,key);}
        else for(const field of spec.fields)if(!same(prior[field],row[field]))add(path+'.'+field,optionId,row[field]??'',key);
      }
      for(const [id,row] of previous)if(!wanted.has(id)){const key=rowKey(row,spec);removeChildren(key);add(path,optionId,null,key,'REMOVE');}
    };
    for(const group of ['basic','settings','delivery'])for(const key of Object.keys(after[group]))if(!same(before[group]?.[key],after[group][key]))add(group+'.'+key,null,after[group][key]);
    for(const o of after.options){const old=before.options.find(v=>v.id===o.id);if(!old)continue;
      if(o.itemName!==old.itemName)add('options.itemName',o.id,o.itemName);
      for(const key of Object.keys(o.registration)){const amount=['originalPrice','salePrice'].includes(key),next=amount?price(o.registration[key]):o.registration[key],prior=amount&&old.registration[key]!=null?price(old.registration[key]):old.registration[key];if(!same(prior,next))add('options.registration.'+key,o.id,next);}
      for(const key of ['images','contents'])if(!same(old[key],o[key]))add('options.'+key,o.id,o[key]);
      for(const key of ['attributes','notices','certifications'])rows('options.'+key,o.id,old[key]||[],o[key]||[],rowSpecs[key]);
    }
    rows('documents',null,before.documents||[],after.documents||[],rowSpecs.documents);
    return [...out.values()];
  }
  function apply(document,changes){
    const out=clone(document);
    for(const c of changes){let row=out,path=c.path;
      if(path.startsWith('options.')){row=out.options.find(o=>o.id===c.optionId);path=path.slice(8);if(!row)throw Error('옵션 구성이 변경되어 입력을 자동 복구할 수 없습니다.');}
      if(legacyList(c))throw Error('이전 목록 수정의 범위를 확인할 수 없습니다. 입력을 보관하고 다시 준비해 주세요.');
      if(c.rowKey){const [kind,field]=path.split('.'),spec=rowSpecs[kind];if(!spec)throw Error('수정 행을 확인해 주세요.');const values=row[kind];const index=values.findIndex(v=>same(rowKey(v,spec),c.rowKey));
        if(c.operation==='REMOVE'){if(index>=0)values.splice(index,1);continue;}
        if(!field){if(index>=0)values[index]=clone(c.value);else values.push(clone(c.value));}
        else{let target=values[index];if(!target){target=Object.fromEntries(spec.keys.map((k,n)=>[k,c.rowKey[n]]));if(kind==='certifications')target.attachments=[];values.push(target);}target[field]=clone(c.value);}
      }else{const parts=path.startsWith('registration.')?['registration',path.slice(13)]:path.startsWith('settings.')?['settings',path.slice(9)]:path.split('.');const key=parts.pop();for(const part of parts)row=row[part];row[key]=clone(c.value);}
    }
    for(const option of out.options)option.noticeCategory=option.notices?.[0]?.category||null;
    return out;
  }
  const api={inflate,flatten,changes,apply,legacyList};if(typeof module!=='undefined')module.exports=api;else window.CoupangWorkingCopyModel=api;
})();
