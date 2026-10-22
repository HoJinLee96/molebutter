"use strict";
// A shared page-scoped controller; page owners keep their domain selection keys.
(() => {
  function reconcile(selection,items,key){
    const current=new Map(items.map(item=>[String(key(item)),item]));
    for(const id of selection.keys()){
      if(!current.has(String(id)))selection.delete(id);
      else if(typeof selection.set==='function')selection.set(id,current.get(String(id)));
    }
    return selection;
  }
  // Only current rows that are visibly checked may enter a new action. Never expand an empty snapshot to "all".
  function snapshot(selection,items,key,checkedKeys){
    reconcile(selection,items,key);const checked=new Set(checkedKeys.map(String));
    for(const id of selection.keys())if(!checked.has(String(id)))selection.delete(id);
    return items.filter(item=>selection.has(String(key(item)))&&checked.has(String(key(item))));
  }
  function create({controls,checkboxes,onToggle,isBusy=()=>false}) {
    function sync(){
      const rows=checkboxes().filter(node=>!node.disabled),checked=rows.filter(node=>node.checked).length;
      for(const control of controls){control.checked=rows.length>0&&checked===rows.length;control.indeterminate=checked>0&&checked<rows.length;control.disabled=isBusy()||!rows.length;}
    }
    for(const control of controls)control.addEventListener('change',()=>{if(control.disabled||isBusy())return;onToggle(control.checked,checkboxes().filter(node=>!node.disabled));sync();});
    return {sync};
  }
  if(typeof window!=='undefined')window.TableSelection={create,reconcile,snapshot};
  if(typeof module!=='undefined')module.exports={create,reconcile,snapshot};
})();
