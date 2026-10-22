const {test}=require('node:test'),assert=require('node:assert/strict');
const {createQueue,savedRows,mergeRows}=require('../../../molebutter-app/src/main/resources/static/js/coupang-bulk-edit.js');
test('a new selection keeps unrelated receipts and unsaved input without adopting their identity',()=>{
 const a={product:'A',vendor:'1',value:'120000',receipt:{executionId:'eA',status:'UNKNOWN',key:'same-key'}},
       unsent={product:'C',vendor:'3',value:'139000',included:true,status:'READY'},
       b={product:'B',vendor:'2',value:'119000',status:'READY'};
 const merged=savedRows(JSON.stringify({version:1,rows:mergeRows([a,unsent],[b])}));
 assert.deepEqual(merged,[a,unsent,b]);
 const done={...b,status:'SUCCEEDED',receipt:null};
 assert.deepEqual(mergeRows(merged,[done]),[a,unsent,done]);
 const recovered={...a,status:'SUCCEEDED',receipt:null};
 assert.deepEqual(mergeRows(mergeRows(merged,[done]),[recovered]),[unsent,done,recovered]);
 assert.deepEqual(a.receipt,{executionId:'eA',status:'UNKNOWN',key:'same-key'});
});
test('recovery cache rejects corrupt or duplicate identities before replacing the stored evidence',()=>{
 assert.deepEqual(savedRows(null),[]);
 for(const value of ['{','null','{"version":2,"rows":[]}','{"version":1,"rows":[null]}',JSON.stringify({version:1,rows:[{product:'A',vendor:'1'},{product:'A',vendor:'1'}]})])assert.throws(()=>savedRows(value));
 assert.deepEqual(mergeRows([{product:'A',vendor:'1',receipt:{key:'1'}}],[{product:'A',vendor:'2',value:'10'}]).map(r=>r.vendor),['1','2']);
});
test('recovery-only queues cannot prepare or execute old unsent input',async()=>{
 const rows=[{product:'old',vendor:'1',included:true,value:'120000'}],writes=[];
 const queue=createQueue({kind:'PRICE',rows,recoveryOnly:true,get:async()=>{throw Error('unexpected read');},post:async(...args)=>writes.push(args),save(){},uuid:()=> 'key'});await queue.run();assert.equal(writes.length,0);
});
function setup(kind='PRICE',saved){
 const rows=saved??['1','2'].map(product=>({product,vendor:product+'00',internal:product,code:'SKU'+product})),contexts={},writes=[],stored=[],execs={};let failExecute=false,storageFail=false,receiptStorageFail=false,unknown=false,previewTamper=false,reconciled=false,applied=0;
 for(const r of rows)contexts[r.product]={entry:{accountKey:'a',revision:0,appliedRevision:0,sessionId:'session'+r.product},optionId:'option'+r.product,currentValue:kind==='PRICE'?100000:50,pendingValue:null,registry:{blocked:false,state:{version:1},conflicts:[]},source:{fingerprint:'f'+r.product,purchaseCost:90000,stockOptionCount:1,stockOptions:[{quantity:70}],stocks:[{quantity:70}]},settings:{revision:1},calculation:{suggestedPrice:120000}};
 const get=async url=>{if(url.includes('quick-edit'))return structuredClone(contexts[url.split('/')[4]]);return execs[url.split('/').at(-1)];};
 const post=async(url,body)=>{writes.push({url,body});if(url.endsWith('/prepare')){const id=url.split('/')[4],c=contexts[id];c.entry.revision++;return {entry:structuredClone(c.entry),registry:structuredClone(c.registry),preview:{id:'p'+id,executable:true,target:{issues:[],changes:[{path:kind==='PRICE'?'salePrice':'amountInStock',before:String(c.currentValue),after:String(body.value)}],steps:[{optionId:previewTamper?'wrong':c.optionId,type:kind}]}}};}
  if(url.endsWith('/execute')){if(failExecute){failExecute=false;throw Error('lost response');}const id=url.split('/').at(-2).slice(1);const x={id:'e'+id,status:unknown?'UNKNOWN':'SUCCEEDED'};execs[x.id]=x;return x;}
  if(url.endsWith('/reconcile'))return {id:url.split('/').at(-2),status:reconciled?'SUCCEEDED':'UNKNOWN'};
  if(url.endsWith('/revise'))return null;throw Error(url);};
 const queue=createQueue({kind,rows,get,post,save:data=>{if(storageFail||receiptStorageFail&&data.some(r=>r.receipt))throw Error('storage denied');stored.push(structuredClone(data));},uuid:()=> 'stable-key',applied:async()=>applied++,sleep:async()=>{}});
 return {queue,rows,contexts,writes,stored,get applied(){return applied;},async load(){for(const r of rows)await queue.load(r);},set lost(v){failExecute=v;},set storageFail(v){storageFail=v;},set receiptStorageFail(v){receiptStorageFail=v;},set unknown(v){unknown=v;},set tamper(v){previewTamper=v;},set reconciled(v){reconciled=v;}};
}
test('bulk uses per-option proposals and preserves current equality without saving',async()=>{const s=setup();await s.load();assert.deepEqual(s.rows.map(r=>r.value),['120000','120000']);s.rows[1].value='100000';await s.queue.run();assert.equal(s.writes.length,2);assert.equal(s.writes[0].body.kind,'PRICE');assert.equal(s.writes[0].body.value,120000);assert.equal(s.rows[0].status,'SUCCEEDED');assert.equal(s.rows[1].status,'UNCHANGED');assert.equal(s.applied,2);});
test('bulk strips comma formatting, avoids equal-price writes, and rejects decimals and currency text',async()=>{
 const s=setup();await s.load();s.rows[0].value=' 139,000 ';s.rows[1].value='100,000';await s.queue.run();assert.equal(s.writes.length,2);assert.equal(s.writes[0].body.value,139000);assert.equal(s.rows[1].status,'UNCHANGED');
 for(const value of ['120000.0','120,000원','1e5','-120000']){const invalid=setup();await invalid.load();invalid.rows[0].value=value;await invalid.queue.run();assert.equal(invalid.writes.length,0);}
});
test('stock zero and owned proposals keep manual multi-option quantities blank',async()=>{const s=setup('STOCK');Object.assign(s.contexts['1'].source,{availability:'UNAVAILABLE',ownedQuantity:0,ownedOptions:[],couponOptionCount:1});Object.assign(s.contexts['2'].source,{availability:'UNAVAILABLE',ownedQuantity:5,ownedOptions:[{quantity:3},{quantity:2}],couponOptionCount:1});await s.load();assert.equal(s.rows[0].value,'0');assert.equal(s.rows[1].value,'');assert(!s.rows[1].included);await s.queue.run();assert.equal(s.writes[0].body.value,0);assert.equal(s.writes.length,2);});
test('changed settings/revision/current values stop before prepare and preserve desired values for explicit rereview',async()=>{const s=setup();await s.load();s.contexts['1'].settings.revision++;await s.queue.run();assert.equal(s.writes.length,0);assert.equal(s.rows[0].status,'REVIEW');assert.equal(s.rows[0].value,'120000');await s.queue.run();assert.equal(s.writes.length,4);});
test('scope mismatch in server preview never executes; double clicks do not duplicate writes',async()=>{const s=setup();await s.load();s.tamper=true;await s.queue.run();assert.equal(s.writes.length,1);assert.equal(s.rows[0].status,'REVIEW');const n=setup();await n.load();await Promise.all([n.queue.run(),n.queue.run()]);assert.equal(n.writes.length,4);});
test('lost execute response persists receipt and reentry reuses the same preview/idempotency key',async()=>{const s=setup();await s.load();s.lost=true;await s.queue.run();assert.equal(s.rows[0].status,'UNKNOWN');assert.equal(s.writes.length,2);const recovered=setup('PRICE',s.stored.at(-1));await recovered.load();await recovered.queue.run();assert(!recovered.writes[0].url.endsWith('/prepare'));assert.deepEqual(recovered.writes[0],s.writes[1]);assert.equal(recovered.writes.filter(w=>w.url.endsWith('/prepare')).length,1);});
test('UNKNOWN blocks remaining options; reconcile succeeds without creating a new first submission',async()=>{const s=setup();await s.load();s.unknown=true;await s.queue.run();assert.equal(s.writes.length,2);assert.equal(s.rows[1].status,'READY');s.reconciled=true;s.unknown=false;await s.queue.run();assert(s.writes[2].url.endsWith('/reconcile'));assert.equal(s.writes.filter(w=>w.url.includes('/1/options/')&&w.url.endsWith('/prepare')).length,1);assert.equal(s.rows[1].status,'SUCCEEDED');});
test('receipt storage failure prevents external execution and queue stop preserves unsent items',async()=>{const s=setup();await s.load();s.storageFail=true;await s.queue.run();assert.equal(s.writes.length,0);assert.equal(s.rows[0].status,'ERROR');const n=setup();await n.load();n.queue.stop();assert.equal(n.writes.length,0);});

test('failure to persist a prepared receipt prevents execution and stale loads cannot select rows',async()=>{
 const s=setup();await s.load();s.receiptStorageFail=true;await s.queue.run();assert.equal(s.writes.length,1);assert(s.writes[0].url.endsWith('/prepare'));assert.equal(s.rows[0].receipt,null);
 const n=setup();await n.queue.load(n.rows[0],()=>false);assert.equal(n.rows[0].context,undefined);assert.equal(n.rows[0].included,undefined);
});

test('same product revision changes stop the next option until another explicit save',async()=>{
 const s=setup('PRICE',[{product:'1',vendor:'100',internal:'1'},{product:'1',vendor:'101',internal:'1'}]);await s.load();await s.queue.run();assert.equal(s.writes.length,2);assert.equal(s.rows[1].status,'REVIEW');assert.equal(s.rows[1].value,'120000');await s.queue.run();assert.equal(s.writes.length,4);assert.equal(s.rows[1].status,'SUCCEEDED');
});

function groupedSetup({conflict=false,unknown=false,externalRevision=false}={}){
 const rows=[{product:'1',vendor:'100',internal:'1'},{product:'1',vendor:'101',internal:'2'}],reads=[],writes=[],saved=[];let revision=0,appliedRevision=null,version=1,settings=1,actualWrites=0;
 const contexts=new Map(rows.map((r,i)=>[r.vendor,{optionId:'option'+i,currentValue:100000,source:{fingerprint:'f'+i,purchaseCost:90000,stocks:[],stockOptionCount:0},calculation:{suggestedPrice:120000}}]));
 const context=r=>({...structuredClone(contexts.get(r.vendor)),entry:{accountKey:'a',revision,appliedRevision,sessionId:'s'},registry:{blocked:false,state:{version},conflicts:[]},settings:{revision:settings}});
 const queue=createQueue({kind:'PRICE',rows,get:async()=>{throw Error('Unexpected external quick-edit GET');},readContexts:async list=>{reads.push(list.map(r=>r.vendor));return new Map(list.map(r=>[r,context(r)]));},preparePath:r=>'/bulk/'+r.vendor+'/prepare',post:async(url,input)=>{
   writes.push({url,input});if(url.endsWith('/prepare')){revision++;const r=rows.find(r=>url.includes(r.vendor)),c=context(r);return {entry:c.entry,registry:c.registry,preview:{id:r.vendor,executable:true,target:{issues:[],changes:[{path:'salePrice',before:String(c.currentValue),after:String(input.value)}],steps:[{optionId:c.optionId,type:'PRICE'}]}}};}
   if(url.endsWith('/execute')){const vendor=url.split('/').at(-2);if(conflict)return {id:'e'+vendor,status:'FAILED'};if(unknown)return {id:'e'+vendor,status:'UNKNOWN'};actualWrites++;appliedRevision=revision;version++;contexts.get(vendor).currentValue=120000;if(externalRevision)revision++;return {id:'e'+vendor,status:'SUCCEEDED'};}throw Error(url);
 },save:data=>saved.push(structuredClone(data)),uuid:()=> 'key',sleep:async()=>{}});
 return {queue,rows,reads,writes,saved,get actualWrites(){return actualWrites;},set settings(v){settings=v;}};
}
test('bulk DB contexts group same-product options; own completed revision permits the remaining option',async()=>{
 const s=groupedSetup();await s.queue.loadGroup(s.rows);assert.deepEqual(s.reads,[['100','101']]);assert.equal(s.writes.length,0);await s.queue.run();assert.equal(s.actualWrites,2);assert(s.rows.every(r=>r.status==='SUCCEEDED'));assert.deepEqual(s.reads,[['100','101'],['100','101'],['101']]);
});
test('bulk DB route preserves external revision/settings changes and pre-dispatch conflict stops later options',async()=>{
 const changed=groupedSetup({externalRevision:true});await changed.queue.loadGroup(changed.rows);await changed.queue.run();assert.equal(changed.actualWrites,1);assert.equal(changed.rows[1].status,'REVIEW');assert.equal(changed.rows[1].value,'120000');
 const settings=groupedSetup();await settings.queue.loadGroup(settings.rows);settings.settings=2;await settings.queue.run();assert.equal(settings.writes.length,0);assert.equal(settings.rows[0].status,'REVIEW');
 const blocked=groupedSetup({conflict:true});await blocked.queue.loadGroup(blocked.rows);await blocked.queue.run();assert.equal(blocked.actualWrites,0);assert.equal(blocked.writes.length,2);assert.equal(blocked.rows[0].status,'FAILED');assert.equal(blocked.rows[0].value,'120000');assert.equal(blocked.rows[1].status,'READY');
 const unknown=groupedSetup({unknown:true});await unknown.queue.loadGroup(unknown.rows);await unknown.queue.run();assert.equal(unknown.rows[0].status,'UNKNOWN');assert.equal(unknown.writes.length,2);
});
test('closed/stopped group reads cannot select options and user blank input survives DB reload',async()=>{
 const s=groupedSetup();await s.queue.loadGroup(s.rows,()=>false);assert(s.rows.every(r=>r.context===undefined));await s.queue.loadGroup(s.rows);s.rows[0].value='';await s.queue.loadGroup(s.rows);assert.equal(s.rows[0].value,'');
});

test('server stock proposal overrides old supplier stock and null proposal leaves unknown stock blank',async()=>{
 const s=setup('STOCK');Object.assign(s.contexts['1'].source,{availability:'SELECTED_MISSING',availabilityReason:'SELECTED_LISTING_MISSING',stockProposal:{source:'ZERO',quantity:0,selectionRequired:false},stocks:[{quantity:1}],stockOptions:[{quantity:1}]});
 Object.assign(s.contexts['2'].source,{availability:'UNKNOWN',stockProposal:null});await s.load();assert.equal(s.rows[0].value,'0');assert(s.rows[0].included);assert.equal(s.rows[1].value,'');assert(!s.rows[1].included);
 s.rows[0].value='3';await s.queue.load(s.rows[0]);assert.equal(s.rows[0].value,'3');assert.equal(s.writes.length,0);
});
