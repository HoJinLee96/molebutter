const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const source=['app-ui.js','form-actions.js','settings.js'].map(name=>readFileSync('molebutter-app/src/main/resources/static/js/'+name,'utf8')).join('\n');
const template=readFileSync('molebutter-app/src/main/resources/templates/settings.html','utf8');
const settle=()=>new Promise(r=>setImmediate(r));
function fixture({admin=true,post=async()=>({}),fail=false}={}){
    const make=(tagName='DIV',type='text')=>({tagName,type,value:'',textContent:'',innerHTML:'',disabled:false,hidden:false,dataset:{},listeners:{},attributes:{},classList:{toggle(){}},setAttribute(k,v){this.attributes[k]=v;},removeAttribute(k){delete this.attributes[k];},getAttribute(k){return this.attributes[k]??null;},hasAttribute(k){return k in this.attributes;},matches(){return false;},addEventListener(k,f){this.listeners[k]=f;},close(){this.open=false;},showModal(){this.open=true;},reportValidity(){return this.valid!==false;},fire(type){const event={target:this,currentTarget:this,preventDefault(){this.defaultPrevented=true;},stopImmediatePropagation(){this.stopped=true;}};return Promise.resolve(this.listeners[type]?.(event)).then(()=>event);},click(){return this.fire('click');}});
    const nodes=new Map([...template.matchAll(/<([\w-]+)\b([^>]*\bid="([^"]+)"[^>]*)>/g)].map(([,tag,attrs,id])=>[id,make(tag.toUpperCase(),/\btype="([^"]+)"/.exec(attrs)?.[1]||'text')]));
    const $=id=>nodes.get(id)??null;
    if(!admin)for(const id of ['payment-create-form','brand-create-form','channel-create-form','pricing-settings-form','schedule-settings-form'])nodes.delete(id);
    const tabs=['brands','suppliers','schedule'].map(tab=>({...make(),dataset:{tab}}));
    const panels=tabs.map(t=>({...make(),dataset:{settingsPanel:t.dataset.tab}}));
    for(const [form,input,button] of [['brand-create-form','brand-name','brand-create-save'],['payment-create-form','payment-name','payment-create-save'],['settings-edit-form','settings-edit-name','settings-edit-save'],['settings-delete-form',null,'settings-delete-save'],['schedule-settings-form','schedule-time','schedule-save']]){if(input){$(input).form=$(form);$(input).parentElement=$(form);}$(button).form=$(form);$(button).parentElement=$(form);}
    const controls=[$('brand-create-save'),$('payment-create-save'),$('settings-edit-save'),$('settings-delete-save'),$('schedule-save')];let failed=fail;
    const posts=[],events={},location={search:'?tab=schedule'};
    const ctx=vm.createContext({URLSearchParams,location,history:{pushState(_,__,url){location.search=url.slice(url.indexOf('?'));}},addEventListener(k,f){events[k]=f;},
        document:{listeners:{},addEventListener(k,f){this.listeners[k]=f;},body:{dataset:{settingsAdmin:String(admin)}},getElementById:$,querySelectorAll(selector){
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
    });ctx.window=ctx;ctx.getComputedStyle=()=>({display:'block',visibility:'visible'});vm.runInContext(source,ctx);
    return {$,posts,events,panels,controls,location,recover(){failed=false;},click(id){return $(id).click();},submit(id){return $(id).fire('submit');},enter(id){const event={key:'Enter',target:$(id),preventDefault(){this.defaultPrevented=true;}};ctx.document.listeners.keydown(event);return event;}};
}
test('settings opens a linked tab and renders read-only data without admin forms',async()=>{
    const f=fixture({admin:false});await settle();assert.equal(f.panels[2].hidden,false);assert.equal(f.panels[0].hidden,true);
    assert.equal(f.$('brand-create-form'),null);assert.doesNotMatch(f.$('settings-brand-rows').innerHTML,/data-action/);
    assert.match(f.$('settings-schedule-summary').textContent,/18:00/);assert.equal(f.posts.length,0);
    f.location.search='?tab=suppliers';f.events.popstate();assert.equal(f.panels[1].hidden,false);assert.equal(f.panels[2].hidden,true);
});
test('settings failures disable saving until a successful explicit retry',async()=>{
    const f=fixture({fail:true});await settle();assert.equal(f.$('settings-reload').hidden,false);assert.equal(f.controls[0].disabled,true);
    await f.click('brand-create-save');assert.equal(f.posts.length,0);
    f.recover();await f.$('settings-reload').listeners.click();assert.equal(f.controls[0].disabled,false);assert.equal(f.$('settings-error').hidden,true);
});
test('pending settings save prevents double submissions and preserves input after failure',async()=>{
    let reject;const pending=new Promise((_,no)=>reject=no);const f=fixture({post:()=>pending});await settle();
    f.$('brand-name').value='추가 브랜드';const saving=f.click('brand-create-save');await f.click('brand-create-save');
    assert.equal(f.posts.length,1);assert.equal(f.controls[0].disabled,true);
    reject(Error('저장 실패'));await saving;assert.equal(f.$('brand-name').value,'추가 브랜드');assert.equal(f.controls[0].disabled,false);assert.equal(f.$('settings-error').textContent,'저장 실패');
});
test('schedule save submits current revision without pricing conditions',async()=>{
 const f=fixture();await settle();f.$('schedule-time').value='19:30';f.$('schedule-enabled').checked=true;
 await f.click('schedule-save');assert.equal(f.posts[0].url,'/api/product-refresh/settings');assert.equal(f.posts[0].body.revision,0);assert.equal(f.posts[0].body.scheduleTime,'19:30');assert.equal(f.posts[0].body.fee,undefined);
});
test('settings input Enter and native submit never save; explicit button retains form validation',async()=>{
 const f=fixture();await settle();f.$('brand-name').value='새 이름';assert.equal(f.enter('brand-name').defaultPrevented,true);await f.submit('brand-create-form');await f.submit('schedule-settings-form');assert.equal(f.posts.length,0);
 f.$('brand-create-form').valid=false;await f.click('brand-create-save');assert.equal(f.posts.length,0);f.$('brand-create-form').valid=true;await f.click('brand-create-save');assert.equal(f.posts[0].body.name,'새 이름');assert.equal(f.$('brand-name').value,'');
 assert.equal(f.enter('schedule-time').defaultPrevented,undefined);
});
