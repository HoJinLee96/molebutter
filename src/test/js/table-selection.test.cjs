const {test}=require('node:test'),assert=require('node:assert/strict');
const {create,reconcile,snapshot}=require('../../../molebutter-app/src/main/resources/static/js/table-selection.js');
test('page selection synchronizes all/partial/none and skips disabled rows without crossing tables',()=>{
 const control=()=>({listeners:{},addEventListener(n,f){this.listeners[n]=f;}}),desktop=control(),mobile=control(),rows=[{checked:true},{checked:false},{checked:false,disabled:true}],other=[{checked:false}];let busy=false;
 const a=create({controls:[desktop,mobile],checkboxes:()=>rows,isBusy:()=>busy,onToggle:(checked,items)=>items.forEach(n=>n.checked=checked)});a.sync();assert(desktop.indeterminate);assert(mobile.indeterminate);desktop.checked=true;desktop.listeners.change();assert(desktop.checked);assert(mobile.checked);assert(!desktop.indeterminate);assert(!rows[2].checked);assert(!other[0].checked);
 mobile.checked=false;mobile.listeners.change();assert(!desktop.checked);assert(!rows[0].checked);busy=true;a.sync();assert(desktop.disabled);desktop.checked=true;desktop.listeners.change();assert(!rows[0].checked);busy=false;rows.length=0;a.sync();assert(desktop.disabled);
});
test('reconciliation removes vanished rows and refreshes current revisions before an action snapshot',()=>{
 const selected=new Map([['1',{id:'1',revision:1}],['2',{id:'2',revision:1}]]),current=[{id:'2',revision:3},{id:'3',revision:0}];reconcile(selected,current,p=>p.id);assert.equal(selected.size,1);assert.equal(selected.get('2').revision,3);
 assert.deepEqual(snapshot(selected,current,p=>p.id,[]),[]);assert.equal(selected.size,0);
 const ids=new Set(['old','2','3']);assert.deepEqual(snapshot(ids,current,p=>p.id,['3','foreign']),[current[1]]);assert.deepEqual([...ids],['3']);
});
