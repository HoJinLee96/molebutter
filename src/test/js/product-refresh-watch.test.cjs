const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),{readFileSync}=require('node:fs');
const settle=async()=>{for(let i=0;i<4;i++)await new Promise(r=>setImmediate(r));};
function setup(fetchStatus,onComplete=async()=>{},canPoll=()=>true){
 let id=0;const timers=new Map(),states=[],errors=[];
 const ctx=vm.createContext({setTimeout(fn){timers.set(++id,fn);return id;},clearTimeout(i){timers.delete(i);}});
 vm.runInContext(readFileSync('src/main/resources/static/js/product-refresh-watch.js','utf8'),ctx);
 const create=vm.runInContext('ProductRefreshWatch.create',ctx);
 return {watch:create({fetchStatus,onComplete,onState:s=>states.push(s),onError:e=>errors.push(e),canPoll}),states,errors,timers,async tick(){const item=timers.entries().next().value;if(item){timers.delete(item[0]);item[1]();}await settle();}};
}
test('serial polling has no overlapping requests; terminal status triggers one detail fetch',async()=>{
 let resolve,calls=0,done=0;const pending=new Promise(r=>resolve=r);
 const f=setup(()=>{calls++;return pending;},async()=>done++);f.watch.start('42');await f.tick();await f.tick();assert.equal(calls,1);
 resolve({status:'SUCCESS'});await settle();assert.equal(done,1);assert.equal(f.timers.size,0);
});
test('transient status/detail errors retry; authentication errors stop',async()=>{
 let n=0,done=0;
 const f=setup(async()=>{if(++n===1)throw Error('일시 통신 실패');return {status:'SUCCESS'};},async()=>{if(++done===1)throw Error('상세 조회 실패');});
 f.watch.start('42');await f.tick();await f.tick();await f.tick();assert.equal(f.errors.length,2);assert.equal(done,2);assert.equal(f.timers.size,0);
 const denied=setup(async()=>{throw Object.assign(Error('권한 없음'),{status:403});});denied.watch.start('42');await denied.tick();assert.equal(denied.timers.size,0);
});
test('closed or replaced watcher ignores stale response; hidden screen does not poll',async()=>{
 let resolve,visible=false,calls=0,done=0;const pending=new Promise(r=>resolve=r);
 const f=setup(()=>{calls++;return pending;},async()=>done++,()=>visible);
 f.watch.start('42');await f.tick();assert.equal(calls,0);visible=true;await f.tick();assert.equal(calls,1);
 f.watch.stop();resolve({status:'SUCCESS'});await settle();assert.equal(done,0);assert.equal(f.states.length,0);assert.equal(f.timers.size,0);
});

test('product failure or partial does not terminate an active blocked job',async()=>{
 for(const status of ['FAILED','PARTIAL']){let complete=0;let active=true;const f=setup(async()=>({status,active,runStatus:'BLOCKED'}),async()=>complete++);f.watch.start('42');await f.tick();assert.equal(complete,0);assert.equal(f.timers.size,1);active=false;await f.tick();assert.equal(complete,1);assert.equal(f.timers.size,0);}
});
