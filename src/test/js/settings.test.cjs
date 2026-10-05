const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const source=readFileSync('molebutter-app/src/main/resources/static/js/app-ui.js','utf8')+readFileSync('molebutter-app/src/main/resources/static/js/settings.js','utf8');
const template=readFileSync('molebutter-app/src/main/resources/templates/settings.html','utf8');
const settle=()=>new Promise(r=>setImmediate(r));
function fixture({admin=true,post=async()=>({}),fail=false}={}){
    const make=()=>({value:'',textContent:'',innerHTML:'',disabled:false,hidden:true,dataset:{},listeners:{},classList:{toggle(){}},setAttribute(){},removeAttribute(){},addEventListener(k,f){this.listeners[k]=f;},close(){this.open=false;},showModal(){this.open=true;}});
    const nodes=new Map([...template.matchAll(/\bid="([^"]+)"/g)].map(([,id])=>[id,make()]));
    const $=id=>nodes.get(id)??null;
    if(!admin)for(const id of ['payment-create-form','brand-create-form','channel-create-form','pricing-settings-form','schedule-settings-form'])nodes.delete(id);
    const tabs=['brands','suppliers','schedule'].map(tab=>({...make(),dataset:{tab}}));
    const panels=tabs.map(t=>({...make(),dataset:{settingsPanel:t.dataset.tab}}));
    const controls=[make(),make()];let failed=fail;
    const posts=[],events={},location={search:'?tab=schedule'};
    const ctx=vm.createContext({URLSearchParams,location,history:{pushState(_,__,url){location.search=url.slice(url.indexOf('?'));}},addEventListener(k,f){events[k]=f;},
        document:{body:{dataset:{settingsAdmin:String(admin)}},getElementById:$,querySelectorAll(selector){
            return selector==='[data-tab]'?tabs:selector==='[data-settings-panel]'?panels:selector==='.settings-section'?panels:selector.startsWith('.settings-section:not(')?controls:[];
        }},
        setError(id,value){$(id).textContent=value??'';$(id).hidden=!value;},
        apiPost:async(url,body)=>{posts.push({url,body});return post(url,body);},
        apiGet:async url=>{
            if(failed)throw Error('연결 실패');
            if(url.endsWith('/payment-methods'))return [{id:'1',name:'카드',revision:0}];
            if(url.endsWith('/brands'))return [{id:'9',name:'등록 브랜드',revision:0,usageCount:2}];
            if(url.endsWith('/sales-channels'))return [{code:'COUPANG',name:'쿠팡',revision:0,usageCount:1,builtIn:true,supported:true}];
            if(url.endsWith('/suppliers'))return [{name:'LF몰'}];
            if(url==='/api/product-pricing/settings')return {revision:0,shipping:4000,fee:.105,margin:.06};
            return {revision:0,scheduleEnabled:true,scheduleTime:'18:00'};
        }
    });vm.runInContext(source,ctx);
    return {$,posts,events,panels,controls,location,recover(){failed=false;},submit(id){return $(id).listeners.submit({preventDefault(){}});}};
}
test('settings opens a linked tab and renders read-only data without admin forms',async()=>{
    const f=fixture({admin:false});await settle();assert.equal(f.panels[2].hidden,false);assert.equal(f.panels[0].hidden,true);
    assert.equal(f.$('brand-create-form'),null);assert.doesNotMatch(f.$('settings-brand-rows').innerHTML,/data-action/);
    assert.match(f.$('settings-schedule-summary').textContent,/18:00/);assert.equal(f.posts.length,0);
    f.location.search='?tab=suppliers';f.events.popstate();assert.equal(f.panels[1].hidden,false);assert.equal(f.panels[2].hidden,true);
});
test('settings failures disable saving until a successful explicit retry',async()=>{
    const f=fixture({fail:true});await settle();assert.equal(f.$('settings-reload').hidden,false);assert.equal(f.controls[0].disabled,true);
    await f.submit('brand-create-form');assert.equal(f.posts.length,0);
    f.recover();await f.$('settings-reload').listeners.click();assert.equal(f.controls[0].disabled,false);assert.equal(f.$('settings-error').hidden,true);
});
test('pending settings save prevents double submissions and preserves input after failure',async()=>{
    let reject;const pending=new Promise((_,no)=>reject=no);const f=fixture({post:()=>pending});await settle();
    f.$('brand-name').value='추가 브랜드';const saving=f.submit('brand-create-form');await f.submit('brand-create-form');
    assert.equal(f.posts.length,1);assert.equal(f.controls[0].disabled,true);
    reject(Error('저장 실패'));await saving;assert.equal(f.$('brand-name').value,'추가 브랜드');assert.equal(f.controls[0].disabled,false);assert.equal(f.$('settings-error').textContent,'저장 실패');
});
test('schedule save submits current revision without pricing conditions',async()=>{
 const f=fixture();await settle();f.$('schedule-time').value='19:30';f.$('schedule-enabled').checked=true;
 await f.submit('schedule-settings-form');assert.equal(f.posts[0].url,'/api/product-refresh/settings');assert.equal(f.posts[0].body.revision,0);assert.equal(f.posts[0].body.scheduleTime,'19:30');assert.equal(f.posts[0].body.fee,undefined);
});
